package demo.reactivestreams.part3;

import demo.reactivestreams.part1.SyncSubscriber;
import demo.reactivestreams.part2.AsyncIteratorPublisher;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class AsyncPublisherSyncSubscriberRunner {

    public static void main(String[] args) throws InterruptedException {
        ExecutorService executorService = Executors.newFixedThreadPool(1);

        List<String> words = List.of("The quick brown fox jumps over the lazy dog.".split(" "));
        Supplier<Iterator<? extends String>> iteratorSupplier = () -> List.copyOf(words).iterator();
        AsyncIteratorPublisher<String> publisher = new AsyncIteratorPublisher<>(iteratorSupplier, 1024, executorService);

        SyncSubscriber<String> subscriber1 = new SyncSubscriber<>(1);
        publisher.subscribe(subscriber1);

        SyncSubscriber<String> subscriber2 = new SyncSubscriber<>(2);
        publisher.subscribe(subscriber2);

        subscriber1.awaitCompletion();
        subscriber2.awaitCompletion();

        executorService.shutdown();
        executorService.awaitTermination(60, TimeUnit.SECONDS);
    }
}
