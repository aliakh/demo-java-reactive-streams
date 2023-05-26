package demo.reactivestreams.part1;

import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;

public class SyncPublisherSyncSubscriberRunner {

    public static void main(String[] args) throws InterruptedException {
        List<String> words = List.of("The quick brown fox jumps over the lazy dog.".split(" "));
        Supplier<Iterator<? extends String>> iteratorSupplier = () -> List.copyOf(words).iterator();
        SyncIteratorPublisher<String> publisher = new SyncIteratorPublisher<>(iteratorSupplier);

        SyncSubscriber<String> subscriber1 = new SyncSubscriber<>(1);
        publisher.subscribe(subscriber1);

        SyncSubscriber<String> subscriber2 = new SyncSubscriber<>(2);
        publisher.subscribe(subscriber2);

        subscriber1.awaitCompletion();
        subscriber2.awaitCompletion();
    }
}
