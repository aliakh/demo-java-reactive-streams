package demo.reactivestreams.part5;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

public class WatchEventSubmissionProcessor extends SubmissionPublisher<String>
    implements Flow.Processor<WatchEvent<Path>, String> {

    private static final Logger logger = LoggerFactory.getLogger(WatchEventSubmissionProcessor.class);

    private final String fileExtension;

    private Flow.Subscription subscription;

    public WatchEventSubmissionProcessor(String fileExtension) {
        this.fileExtension = fileExtension;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        logger.info("processor.subscribe: {}", subscription);
        this.subscription = subscription;
        this.subscription.request(1);
    }

    @Override
    public void onNext(WatchEvent<Path> watchEvent) {
        logger.info("processor.next: path {}, action {}", watchEvent.context(), watchEvent.kind());
        if (watchEvent.context().toString().endsWith(fileExtension)) {
            submit(String.format("file %s is %s", watchEvent.context(), decode(watchEvent.kind())));
        }
        subscription.request(1);
    }

    private String decode(WatchEvent.Kind<Path> kind) {
        if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
            return "created";
        } else if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
            return "modified";
        } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
            return "deleted";
        } else {
            throw new RuntimeException();
        }
    }

    @Override
    public void onError(Throwable t) {
        logger.error("processor.error", t);
        closeExceptionally(t);
    }

    @Override
    public void onComplete() {
        logger.info("processor.completed");
        close();
    }
}
