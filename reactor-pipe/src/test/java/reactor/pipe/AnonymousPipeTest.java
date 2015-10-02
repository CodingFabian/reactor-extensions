package reactor.pipe;

import org.junit.Test;
import org.pcollections.TreePVector;
import reactor.core.processor.RingBufferWorkProcessor;
import reactor.fn.Consumer;
import reactor.pipe.concurrent.AVar;
import reactor.pipe.concurrent.Atom;
import reactor.pipe.key.Key;
import reactor.pipe.registry.ConcurrentRegistry;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class AnonymousPipeTest extends AbstractFirehoseTest {

  @Test
  public void testMap() throws InterruptedException {
    NamedPipe<Integer> pipe = new NamedPipe<>(firehose);
    AVar<Integer> res = new AVar<>();

    pipe.anonymous(Key.wrap("source"))
        .map((i) -> i + 1)
        .map(i -> i * 2)
        .consume(res::set);


    pipe.notify(Key.wrap("source"), 1);

    assertThat(res.get(1, TimeUnit.SECONDS), is(4));
  }

  @Test
  public void statefulMapTest() throws InterruptedException {
    AVar<Integer> res = new AVar<>(3);
    NamedPipe<Integer> intPipe = new NamedPipe<>(firehose);

    intPipe.anonymous(Key.wrap("key1"))
           .map((i) -> i + 1)
           .map((Atom<Integer> state, Integer i) -> {
                  return state.update(old -> old + i);
                },
                0)
           .consume(res::set);

    intPipe.notify(Key.wrap("key1"), 1);
    intPipe.notify(Key.wrap("key1"), 2);
    intPipe.notify(Key.wrap("key1"), 3);

    assertThat(res.get(LATCH_TIMEOUT, LATCH_TIME_UNIT), is(9));
  }

  @Test
  public void testFilter() throws InterruptedException {
    NamedPipe<Integer> pipe = new NamedPipe<>(firehose);
    AVar<Integer> res = new AVar<>();

    pipe.anonymous(Key.wrap("source"))
        .map(i -> i + 1)
        .filter(i -> i % 2 != 0)
        .map(i -> i * 2)

        .consume(res::set);


    pipe.notify(Key.wrap("source"), 1);
    pipe.notify(Key.wrap("source"), 2);

    assertThat(res.get(1, TimeUnit.SECONDS), is(6));
  }

  @Test
  public void testPartition() throws InterruptedException {
    NamedPipe<Integer> pipe = new NamedPipe<>(firehose);
    AVar<List<Integer>> res = new AVar<>();

    pipe.anonymous(Key.wrap("source"))
        .partition((i) -> {
          return i.size() == 5;
        })
        .consume(res::set);

    pipe.notify(Key.wrap("source"), 1);
    pipe.notify(Key.wrap("source"), 2);
    pipe.notify(Key.wrap("source"), 3);
    pipe.notify(Key.wrap("source"), 4);
    pipe.notify(Key.wrap("source"), 5);
    pipe.notify(Key.wrap("source"), 6);
    pipe.notify(Key.wrap("source"), 7);

    assertThat(res.get(1, TimeUnit.SECONDS), is(TreePVector.from(Arrays.asList(1, 2, 3, 4, 5))));
  }

  @Test
  public void testSlide() throws InterruptedException {
    NamedPipe<Integer> pipe = new NamedPipe<>(firehose);
    AVar<List<Integer>> res = new AVar<>(6);

    pipe.anonymous(Key.wrap("source"))
        .slide(i -> {
          return i.subList(i.size() > 5 ? i.size() - 5 : 0,
                           i.size());
        })
        .consume(res::set);

    pipe.notify(Key.wrap("source"), 1);
    pipe.notify(Key.wrap("source"), 2);
    pipe.notify(Key.wrap("source"), 3);
    pipe.notify(Key.wrap("source"), 4);
    pipe.notify(Key.wrap("source"), 5);
    pipe.notify(Key.wrap("source"), 6);

    assertThat(res.get(1, TimeUnit.SECONDS), is(TreePVector.from(Arrays.asList(2,3,4,5,6))));
  }

  @Test
  public void testNotify() throws InterruptedException {
    NamedPipe<Integer> pipe = new NamedPipe<>(firehose);
    AVar<Integer> res = new AVar<>();

    AnonymousPipe<Integer> s = pipe.anonymous(Key.wrap("source"));

    s.map((i) -> i + 1)
     .map(i -> i * 2)
     .consume(res::set);

    pipe.notify(Key.wrap("source"), 1);

    assertThat(res.get(10, TimeUnit.SECONDS), is(4));
  }

  @Test
  public void testUnregister() throws InterruptedException {
    NamedPipe<Integer> pipe = new NamedPipe<>(firehose);
    CountDownLatch latch = new CountDownLatch(1);

    AnonymousPipe<Integer> s = pipe.anonymous(Key.wrap("source"));

    s.map((i) -> i + 1)
     .map(i -> i * 2)
     .consume(i -> latch.countDown());

    pipe.notify(Key.wrap("source"), 1);
    latch.await(10, TimeUnit.SECONDS);
    s.unregister();

    assertThat(pipe.firehose().getConsumerRegistry().stream().count(), is(0L));
  }

  @Test
  public void testRedirect() throws InterruptedException {
    Key destination = Key.wrap("destination");
    NamedPipe<Integer> pipe = new NamedPipe<>(firehose);
    AVar<Integer> res = new AVar<>();

    AnonymousPipe<Integer> s = pipe.anonymous(Key.wrap("source"));

    s.map((i) -> i + 1)
     .map(i -> i * 2)
     .redirect(destination);

    pipe.consume(destination, (Integer i) -> res.set(i));

    pipe.notify(Key.wrap("source"), 1);

    assertThat(res.get(1, TimeUnit.SECONDS), is(4));
  }

  @Test
  public void testConsume() throws InterruptedException {
    NamedPipe<Integer> pipe = new NamedPipe<>(firehose);
    AVar<Integer> resValue = new AVar<>();
    AVar<Key> resKey = new AVar<>();

    AnonymousPipe<Integer> s = pipe.anonymous(Key.wrap("source"));

    s.map((i) -> i + 1)
     .map(i -> i * 2)
     .consume((k, v) -> {
       resKey.set(k);
       resValue.set(v);
     });


    pipe.notify(Key.wrap("source"), 1);

    assertThat(resKey.get(1, TimeUnit.SECONDS).getPart(0), is("source"));
    assertThat(resValue.get(1, TimeUnit.SECONDS), is(4));
  }

  @Test
  public void testSmoke() throws InterruptedException { // Tests backpressure and in-thread dispatches
    Firehose<Key> concurrentFirehose = new Firehose<>(new ConcurrentRegistry<>(),
                                                      RingBufferWorkProcessor.create(Executors.newFixedThreadPool(4),
                                                                                     256),
                                                      4,
                                                      new Consumer<Throwable>() {
                                                        @Override
                                                        public void accept(Throwable throwable) {
                                                          System.out.printf("Exception caught while dispatching: %s\n",
                                                                            throwable.getMessage());
                                                          throwable.printStackTrace();
                                                        }
                                                      });

    int iterations = 2000;
    CountDownLatch latch = new CountDownLatch(iterations);

    NamedPipe<Integer> pipe = new NamedPipe<>(firehose);
    pipe.anonymous(Key.wrap("key1"))
        .map((i) -> i + 1)
        .map(i -> {
          try {
            Thread.sleep(10);
          } catch (InterruptedException e) {
            e.printStackTrace();
          }
          return i * 2;
        })
        .consume((i_) -> latch.countDown());

    for (int i = 0; i < iterations; i++) {
      firehose.notify(Key.wrap("key1"), i);
      if (i % 500 == 0) {
        System.out.println("Processed " + i + " keys");
      }
    }

    latch.await(5, TimeUnit.MINUTES);
    assertThat(latch.getCount(), is(0L));

    concurrentFirehose.shutdown();
  }
}
