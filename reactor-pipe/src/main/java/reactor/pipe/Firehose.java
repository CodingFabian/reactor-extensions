package reactor.pipe;

import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.processor.RingBufferWorkProcessor;
import reactor.core.support.Assert;
import reactor.fn.Consumer;
import reactor.fn.Function;
import reactor.fn.Predicate;
import reactor.fn.Supplier;
import reactor.fn.timer.HashWheelTimer;
import reactor.fn.tuple.Tuple;
import reactor.fn.tuple.Tuple2;
import reactor.pipe.concurrent.LazyVar;
import reactor.pipe.key.Key;
import reactor.pipe.registry.*;
import reactor.pipe.stream.FirehoseSubscriber;
import reactor.pipe.stream.FirehoseSubscription;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongBinaryOperator;


public class Firehose<K extends Key> {

  private final static int DEFAULT_THREAD_POOL_SIZE = 4;
  private final static int DEFAULT_RING_BUFFER_SIZE = 65536;

  private final DefaultingRegistry<K>         consumerRegistry;
  private final Consumer<Throwable>           errorHandler;
  private final LazyVar<HashWheelTimer>       timer;
  private final Processor<Runnable, Runnable> processor;
  private final ThreadLocal<Boolean>          inDispatcherContext;
  private final Executor                      catchUpExecutor;
  private final FirehoseSubscription          firehoseSubscription;

  public Firehose() {
    this(new ConcurrentRegistry<K>(),
         RingBufferWorkProcessor.<Runnable>create(Executors.newFixedThreadPool(DEFAULT_THREAD_POOL_SIZE),
                                                  DEFAULT_RING_BUFFER_SIZE),
         DEFAULT_THREAD_POOL_SIZE,
         new Consumer<Throwable>() {
           @Override
           public void accept(Throwable throwable) {
             System.out.printf("Exception caught while dispatching: %s\n", throwable.getMessage());
             throwable.printStackTrace();
           }
         });
  }

  public Firehose(Consumer<Throwable> errorHandler) {
    this(new ConcurrentRegistry<K>(),
         RingBufferWorkProcessor.<Runnable>create(Executors.newFixedThreadPool(DEFAULT_THREAD_POOL_SIZE),
                                                  DEFAULT_RING_BUFFER_SIZE),
         DEFAULT_THREAD_POOL_SIZE,
         errorHandler);
  }

  public Firehose(DefaultingRegistry<K> registry,
                  Processor<Runnable, Runnable> processor,
                  int concurrency,
                  Consumer<Throwable> dispatchErrorHandler) {
    this.consumerRegistry = registry;
    this.errorHandler = dispatchErrorHandler;
    this.processor = processor;

    this.catchUpExecutor = Executors.newFixedThreadPool(concurrency,
                                                        new ThreadFactory() {
                                                          private AtomicInteger i = new AtomicInteger(0);

                                                          @Override
                                                          public Thread newThread(Runnable r) {
                                                            return new Thread(r,
                                                                              "catchUpExecutorPool-" + i.getAndIncrement());
                                                          }
                                                        });

    this.inDispatcherContext = new ThreadLocal<>();

    {
      for (int i = 0; i < concurrency; i++) {
        this.processor.subscribe(Subscribers.unbounded((Runnable runnable,
                                                        SubscriptionWithContext<Void> voidSubscriptionWithContext) -> {
                                                         runnable.run();
                                                       },
                                                       dispatchErrorHandler));
      }

      this.firehoseSubscription = new FirehoseSubscription();
      this.processor.onSubscribe(firehoseSubscription);
      //this.processor.onSubscribe(SignalType.NOOP_SUBSCRIPTION);
    }


    this.timer = new LazyVar<>(new Supplier<HashWheelTimer>() {
      @Override
      public HashWheelTimer get() {
        return new HashWheelTimer(10); // TODO: configurable hash wheel size!
      }
    });
  }

  public Firehose<K> fork(ExecutorService executorService,
                          int concurrency,
                          int ringBufferSize) {
    return new Firehose<K>(this.consumerRegistry,
                           RingBufferWorkProcessor.<Runnable>create(executorService, ringBufferSize),
                           concurrency,
                           this.errorHandler);
  }

  public <V> Firehose notify(final K key, final V ev) {
    Assert.notNull(key, "Key cannot be null.");
    Assert.notNull(ev, "Event cannot be null.");


    while ((inDispatcherContext.get() == null || !inDispatcherContext.get()) &&
           !this.firehoseSubscription.maybeClaimSlot()) {
      System.out.println("blocked publisher");
      //LockSupport.parkNanos(10000);
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }

    Boolean inContext = inDispatcherContext.get();
    if (inContext != null && inContext) {
      // Since we're already in the context, we can dispatch syncronously
      try {
        dispatch(key, ev);
      } catch (Throwable outer) {
        errorHandler.accept(outer);
      }
//      catchUpExecutor.execute(() -> {
//        notify(key, ev);
//      });
    } else {

      processor.onNext(() -> {
        try {
          inDispatcherContext.set(true);
          dispatch(key, ev);
        } catch (Throwable outer) {
          errorHandler.accept(outer);
        } finally {
          inDispatcherContext.set(false);
        }
      });
    }
    //    }

    return this;
  }

  private <V> void dispatch(final K key, final V ev) {
    for (Registration<K> reg : consumerRegistry.select(key)) {
      try {
        reg.getObject().accept(key, ev);
      } catch (Throwable inner) {
        errorHandler.accept(inner);
      }
    }
  }

  public <V> Firehose on(final K key, final KeyedConsumer<K, V> consumer) {
    consumerRegistry.register(key, consumer);
    return this;
  }

  public <V> Firehose on(final K key, final SimpleConsumer<V> consumer) {
    consumerRegistry.register(key, new KeyedConsumer<K, V>() {
      @Override
      public void accept(final K key, final V value) {
        consumer.accept(value);
      }
    });
    return this;
  }

  public Firehose miss(final KeyMissMatcher<K> matcher,
                       Function<K, Map<K, KeyedConsumer>> supplier) {
    consumerRegistry.addKeyMissMatcher(matcher, supplier);
    return this;
  }

  public boolean unregister(K key) {
    return consumerRegistry.unregister(key);
  }

  public boolean unregister(Predicate<K> pred) {
    return consumerRegistry.unregister(pred);
  }

  public <V> Registry<K> getConsumerRegistry() {
    return this.consumerRegistry;
  }

  public HashWheelTimer getTimer() {
    return this.timer.get();
  }

  public void shutdown() {
    processor.onComplete();

  }

  public <V> Subscriber<Tuple2<K, V>> makeSubscriber() {
    Firehose<K> ref = this;

    return new Subscriber<Tuple2<K, V>>() {
      private volatile Subscription subscription;

      @Override
      public void onSubscribe(Subscription subscription) {
        this.subscription = subscription;
        subscription.request(1L);
      }

      @Override
      public void onNext(Tuple2<K, V> tuple) {
        ref.notify(tuple.getT1(), tuple.getT2());
        subscription.request(1L);
      }

      @Override
      public void onError(Throwable throwable) {
        ref.errorHandler.accept(throwable);
      }

      @Override
      public void onComplete() {
        subscription.cancel();
      }
    };
  }

  public <V> Publisher<Tuple2<K, V>> makePublisher(K subscriptionKey) {
    Firehose<K> ref = this;


    return new Publisher<Tuple2<K, V>>() {
      @Override
      public void subscribe(Subscriber<? super Tuple2<K, V>> subscriber) {

        AtomicLong requested = new AtomicLong(0);

        Subscription subscription = new Subscription() {
          @Override
          public void request(long l) {
            if (l < 1) {
              throw new RuntimeException("Can't request a non-positive number");
            }

            requested.accumulateAndGet(l, new LongBinaryOperator() {
              @Override
              public long applyAsLong(long old, long diff) {
                long sum = old + diff;
                if (sum < 0 || sum == Long.MAX_VALUE) {
                  return Long.MAX_VALUE; // Effectively unbounded
                } else {
                  return sum;
                }
              }
            });
          }

          @Override
          public void cancel() {
            ref.unregister(subscriptionKey);
          }
        };

        subscriber.onSubscribe(subscription);

        ref.on(subscriptionKey, new SimpleConsumer<V>() {
          @Override
          public void accept(V value) {
            long r = requested.accumulateAndGet(-1, new LongBinaryOperator() {
              @Override
              public long applyAsLong(long old, long diff) {
                long sum = old + diff;

                if (old == Long.MAX_VALUE || sum <= 0) {
                  return Long.MAX_VALUE; // Effectively unbounded
                } else if (old == 1) {
                  return 0;
                } else {
                  return sum;
                }
              }
            });
            if (r >= 0) {
              subscriber.onNext(Tuple.of(subscriptionKey, value));
            }
          }
        });
      }
    };
  }
}
