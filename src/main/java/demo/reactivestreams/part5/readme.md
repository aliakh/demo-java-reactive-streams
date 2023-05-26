# Reactive Streams specification in Java


## Code examples


### Hot asynchronous reactive stream

The following class demonstrates an asynchronous Publisher that sends an infinite sequence of events from a [WatchService](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/file/WatchService.html) implementation for a file system. The _asynchronous_ Publisher is inherited from the [SubmissionPublisher](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/SubmissionPublisher.html) class and reuses its Executor for asynchronous invocations. This Publisher is _hot_ and sends events about file changes in the given folder.


```java
public class WatchServiceSubmissionPublisher extends SubmissionPublisher<WatchEvent<Path>> {

   private final Future<?> task;

   WatchServiceSubmissionPublisher(String folderName) {
       ExecutorService executorService = (ExecutorService) getExecutor();

       task = executorService.submit(() -> {
           try {
               WatchService watchService = FileSystems.getDefault().newWatchService();

               Path folder = Paths.get(folderName);
               folder.register(watchService,
                   StandardWatchEventKinds.ENTRY_CREATE,
                   StandardWatchEventKinds.ENTRY_MODIFY,
                   StandardWatchEventKinds.ENTRY_DELETE
               );

               WatchKey key;
               while ((key = watchService.take()) != null) {
                   for (WatchEvent<?> event : key.pollEvents()) {
                       WatchEvent.Kind<?> kind = event.kind();
                       if (kind == StandardWatchEventKinds.OVERFLOW) {
                           continue;
                       }

                       WatchEvent<Path> watchEvent = (WatchEvent<Path>) event;

                       logger.info("publisher.submit: path {}, action {}", watchEvent.context(), watchEvent.kind());
                       submit(watchEvent);
                   }

                   boolean valid = key.reset();
                   if (!valid) {
                       break;
                   }
               }

               watchService.close();
           } catch (IOException | InterruptedException e) {
               throw new RuntimeException(e);
           }
       });
   }

   @Override
   public void close() {
       logger.info("publisher.close");
       task.cancel(false);
       super.close();
   }
}
```


The following class demonstrates an asynchronous Processor that filters events (by the given file extension) and transforms them (from WatchEvent to String). As a Subscriber, this Processor implements the Subscriber methods _onSubscribe_, _onNext_, _onError_, _onComplete_ to connect to an upstream Producer and _pull_ items one by one. As a Publisher, this Processor uses the SubmissionPublisher _submit_ method to send items to a downstream Subscriber.


```java
public class WatchEventSubmissionProcessor extends SubmissionPublisher<String>
   implements Flow.Processor<WatchEvent<Path>, String> {

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
```


The following code fragment demonstrates that this Publisher generates events about changes in the current user's home directory, this Processor filters the events related to text files and transforms them to Strings, and the synchronous Subscriber mentioned earlier logs them.


```java
String folderName = System.getProperty("user.home");
String fileExtension = ".txt";

try (SubmissionPublisher<WatchEvent<Path>> publisher = new WatchServiceSubmissionPublisher(folderName);
    WatchEventSubmissionProcessor processor = new WatchEventSubmissionProcessor(fileExtension)) {

   SyncSubscriber<String> subscriber = new SyncSubscriber<>();
   processor.subscribe(subscriber);
   publisher.subscribe(processor);

   TimeUnit.SECONDS.sleep(60);

   publisher.close();

   subscriber.awaitCompletion();
}
```


The invocation log of this code fragment shows that the Publisher and the Processor use the internal implementation of the Subscription interface, the nested package-private SubmissionPublisher.BufferedSubscription class. These classes process events asynchronously by default in the worker threads of the common Fork/Join thread pool.


```
12:00:00.052  ForkJoinPool.commonPool-worker-3  processor.subscribe: java.util.concurrent.SubmissionPublisher$BufferedSubscription@6f753c95
12:00:00.052  ForkJoinPool.commonPool-worker-2  subscriber.subscribe: java.util.concurrent.SubmissionPublisher$BufferedSubscription@1668d43d
12:00:10.058  ForkJoinPool.commonPool-worker-1  publisher.submit: path example.txt, action ENTRY_CREATE
12:00:10.058  ForkJoinPool.commonPool-worker-4  processor.next: path example.txt, action ENTRY_CREATE
12:00:10.064  ForkJoinPool.commonPool-worker-5  subscriber.next: file example.txt is created
12:00:20.072  ForkJoinPool.commonPool-worker-1  publisher.submit: path example.txt, action ENTRY_MODIFY
12:00:20.073  ForkJoinPool.commonPool-worker-5  processor.next: path example.txt, action ENTRY_MODIFY
12:00:20.073  ForkJoinPool.commonPool-worker-1  publisher.submit: path example.txt, action ENTRY_MODIFY
12:00:20.073  ForkJoinPool.commonPool-worker-4  subscriber.next: file example.txt is modified
12:00:20.073  ForkJoinPool.commonPool-worker-2  processor.next: path example.txt, action ENTRY_MODIFY
12:00:20.073  ForkJoinPool.commonPool-worker-6  subscriber.next: file example.txt is modified
12:00:30.085  ForkJoinPool.commonPool-worker-1  publisher.submit: path example.txt, action ENTRY_DELETE
12:00:30.086  ForkJoinPool.commonPool-worker-6  processor.next: path example.txt, action ENTRY_DELETE
12:00:30.086  ForkJoinPool.commonPool-worker-6  subscriber.next: file example.txt is deleted
12:01:00.057  main                              publisher.close
12:01:00.057  ForkJoinPool.commonPool-worker-7  processor.completed
12:01:00.058  ForkJoinPool.commonPool-worker-7  subscriber.complete
12:01:00.058  main                              publisher.close
```
