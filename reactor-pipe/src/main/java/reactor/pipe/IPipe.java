package reactor.pipe;

import reactor.fn.*;
import reactor.pipe.concurrent.Atom;
import reactor.pipe.consumer.KeyedConsumer;
import reactor.pipe.key.Key;
import reactor.pipe.registry.KeyMissMatcher;

import java.util.List;

public interface IPipe<INIT, CURRENT> {

  <NEXT> IPipe<INIT, NEXT> map(Function<CURRENT, NEXT> mapper);

  // TODO: rename to scan in RX terms
  <NEXT> IPipe<INIT, NEXT> map(Supplier<Function<CURRENT, NEXT>> supplier);

  <ST, NEXT> IPipe<INIT, NEXT> map(BiFunction<Atom<ST>, CURRENT, NEXT> mapper,
                                   ST init);

  IPipe<INIT, CURRENT> filter(Predicate<CURRENT> predicate);

  IPipe<INIT, List<CURRENT>> slide(UnaryOperator<List<CURRENT>> drop);

  IPipe<INIT, List<CURRENT>> partition(Predicate<List<CURRENT>> emit);

  <SRC extends Key> PipeEnd<INIT, CURRENT> consume(KeyedConsumer<SRC, CURRENT> consumer);

  <SRC extends Key> PipeEnd<INIT, CURRENT> consume(Supplier<KeyedConsumer<SRC, CURRENT>> supplier);

  PipeEnd<INIT, CURRENT> consume(Consumer<CURRENT> consumer);


  public interface PipeEnd<INIT, CURRENT> {
    void subscribe(Key key, Firehose firehose);

    <K extends Key> void subscribe(KeyMissMatcher<K> matcher, Firehose firehose);
  }
}