package demo.reactivestreams.part4;

import demo.reactivestreams.part1.SyncSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.SubmissionPublisher;

public class SubmissionPublisherRunner {

    private static final Logger logger = LoggerFactory.getLogger(SubmissionPublisherRunner.class);

    public static void main(String[] args) throws InterruptedException {
        SubmissionPublisher<String> publisher = new SubmissionPublisher<>();

        SyncSubscriber<String> subscriber1 = new SyncSubscriber<>(1);
        publisher.subscribe(subscriber1);

        SyncSubscriber<String> subscriber2 = new SyncSubscriber<>(2);
        publisher.subscribe(subscriber2);

        List<String> words = List.of("The quick brown fox jumps over the lazy dog.".split(" "));
        Iterator<String> iterator = words.iterator();
        iterator.forEachRemaining(item -> {
            logger.info("publisher.submit: {}", item);
            publisher.submit(item);
        });

        logger.info("publisher.close");
        publisher.close();

        subscriber1.awaitCompletion();
        subscriber2.awaitCompletion();
    }
}
