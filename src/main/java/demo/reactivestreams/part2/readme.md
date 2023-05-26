# Reactive Streams specification in Java


## Code examples


### Cold asynchronous reactive stream

The following class demonstrates an asynchronous Publisher that sends a finite sequence of events from an Iterator. Its structure is similar to the synchronous Producer mentioned earlier. The main difference is that this Publisher processes events in different threads than they were received. To transfer events between threads, the Publisher sends them in wrapper signal classes via a thread-safe queue.


```java
public class AsyncIteratorPublisher<T> implements Flow.Publisher<T> {

   private final Supplier<Iterator<? extends T>> iteratorSupplier;
   private final Executor executor;
   private final int batchSize;

   public AsyncIteratorPublisher(Supplier<Iterator<? extends T>> iteratorSupplier, int batchSize, Executor executor) {
       if (batchSize < 1) {
           throw new IllegalArgumentException();
       }
       this.iteratorSupplier = Objects.requireNonNull(iteratorSupplier);
       this.executor = Objects.requireNonNull(executor);
       this.batchSize = batchSize;
   }

   @Override
   public void subscribe(Flow.Subscriber<? super T> subscriber) {
       // By rule 1.11, a Publisher may support multiple Subscribers and decide
       // whether each Subscription is unicast or multicast.
       new SubscriptionImpl(subscriber).init();
   }

   private class SubscriptionImpl implements Flow.Subscription, Runnable {

       private final Flow.Subscriber<? super T> subscriber;
       private final AtomicLong demand = new AtomicLong(0);
       private final AtomicBoolean cancelled = new AtomicBoolean(false);

       private Iterator<? extends T> iterator;

       SubscriptionImpl(Flow.Subscriber<? super T> subscriber) {
           // By rule 1.9, calling Publisher.subscribe(Subscriber)
           // must throw a NullPointerException when the given parameter is null.
           this.subscriber = Objects.requireNonNull(subscriber);
       }
```


These methods send signals to process the _Publisher.subscribe_, _Subscription.request_, _Subscription.cancel_ events.


```java
       private void init() {
           signal(new Subscribe());
       }

       @Override
       public void request(long n) {
           logger.info("subscription.request: {}", n);
           signal(new Request(n));
       }

       @Override
       public void cancel() {
           logger.info("subscription.cancel");
           signal(new Cancel());
       }
```


These methods process these events in the worker threads of the given Executor instance.


```java
       private void doSubscribe() {
           try {
               iterator = iteratorSupplier.get();
           } catch (Throwable t) {
               // By rule 1.9, a Publisher must call onSubscribe prior onError if Publisher.subscribe(Subscriber) fails.
               subscriber.onSubscribe(new Flow.Subscription() {
                   @Override
                   public void cancel() {
                   }

                   @Override
                   public void request(long n) {
                   }
               });
               // By rule 1.4, if a Publisher fails it must signal an onError.
               doError(t);
           }

           if (!cancelled.get()) {
               subscriber.onSubscribe(this);

               boolean hasNext = false;
               try {
                   hasNext = iterator.hasNext();
               } catch (Throwable t) {
                   // By rule 1.4, if a Publisher fails it must signal an onError.
                   doError(t);
               }

               if (!hasNext) {
                   doCancel();
                   subscriber.onComplete();
               }
           }
       }

       private void doRequest(long n) {
           if (n <= 0) {
               // By rule 3.9, while the Subscription is not cancelled, Subscription.request(long)
               // must signal onError with a IllegalArgumentException if the argument is <= 0.
               doError(new IllegalArgumentException("non-positive subscription request"));
           } else {
               if (demand.get() == Long.MAX_VALUE) {
                   // By rule 3.17, a demand equal or greater than Long.MAX_VALUE
                   // may be considered by the Publisher as "effectively unbounded".
                   return;
               }

               if (demand.get() + n <= 0) {
                   // By rule 3.17, a Subscription must support a demand up to Long.MAX_VALUE.
                   demand.set(Long.MAX_VALUE);
               } else {
                   // By rule 3.8, while the Subscription is not cancelled, Subscription.request(long)
                   // must register the given number of additional elements to be produced to the respective Subscriber.
                   demand.addAndGet(n);
               }

               doNext();
           }
       }

       // By rule 1.2, a Publisher may signal fewer onNext than requested
       // and terminate the Subscription by calling onComplete or onError.
       private void doNext() {
           int batchLeft = batchSize;
           do {
               T next;
               boolean hasNext;
               try {
                   next = iterator.next();
                   hasNext = iterator.hasNext();
               } catch (Throwable t) {
                   // By rule 1.4, if a Publisher fails it must signal an onError.
                   doError(t);
                   return;
               }
               subscriber.onNext(next);

               if (!hasNext) {
                   // By rule 1.6, if a Publisher signals onComplete on a Subscriber,
                   // that Subscriber's Subscription must be considered cancelled.
                   doCancel();
                   // By rule 1.5, if a Publisher terminates successfully it must signal an onComplete.
                   subscriber.onComplete();
               }
           } while (!cancelled.get() && --batchLeft > 0 && demand.decrementAndGet() > 0);

           if (!cancelled.get() && demand.get() > 0) {
               signal(new Next());
           }
       }

       private void doCancel() {
           cancelled.set(true);
       }

       private void doError(Throwable t) {
           // By rule 1.6, if a Publisher signals onError on a Subscriber,
           // that Subscriber's Subscription must be considered cancelled.
           cancelled.set(true);
           subscriber.onError(t);
       }
```


These classes that implement the Signal interface represent the asynchronous signals. The Subscribe class is the signal to create a new Subscription instance. The Request and Cancel classes are the signals for processing the Subscription’s events _request_ and _cancel_. The Next class is the signal to send multiple Subscriber _onNext_ events during a single asynchronous call to avoid frequent thread context switches.


```java
       // These classes represent the asynchronous signals.
       private interface Signal extends Runnable {
       }

       private class Subscribe implements Signal {
           @Override
           public void run() {
               doSubscribe();
           }
       }

       private class Request implements Signal {
           private final long n;

           Request(long n) {
               this.n = n;
           }

           @Override
           public void run() {
               doRequest(n);
           }
       }

       private class Next implements Signal {
           @Override
           public void run() {
               doNext();
           }
       }

       private class Cancel implements Signal {
           @Override
           public void run() {
               doCancel();
           }
       }
```


The unbounded, thread-safe, non-blocking ConcurrentLinkedQueue queue transfers signals between threads. The AtomicBoolean mutex ensures that these asynchronous signals are processed _serially_ in the Executor instance.


```java
       // The non-blocking queue to transfer signals in a thread-safe way.
       private final Queue<Signal> signalsQueue = new ConcurrentLinkedQueue<>();

       // The mutex to establish the happens-before relationship between asynchronous signal calls.
       private final AtomicBoolean mutex = new AtomicBoolean(false);

       private void signal(Signal signal) {
           if (signalsQueue.offer(signal)) {
               tryExecute();
           }
       }

       @Override
       public void run() {
           // By rule 1.3, a Subscriber must ensure that all calls on its Subscriber's
           // onSubscribe, onNext, onError, onComplete signaled to a Subscriber must be signaled serially.
           if (mutex.get()) {
               try {
                   Signal signal = signalsQueue.poll();
                   if (!cancelled.get()) {
                       signal.run();
                   }
               } finally {
                   mutex.set(false);
                   if (!signalsQueue.isEmpty()) {
                       tryExecute();
                   }
               }
           }
       }

       private void tryExecute() {
           if (mutex.compareAndSet(false, true)) {
               try {
                   executor.execute(this);
               } catch (Throwable t) {
                   if (!cancelled.get()) {
                       doCancel();
                       try {
                           // By rule 1.4, if a Publisher fails it must signal an onError.
                           doError(new IllegalStateException(t));
                       } finally {
                           signalsQueue.clear();
                           mutex.set(false);
                       }
                   }
               }
           }
       }
   }
}
```


The following class demonstrates an asynchronous Subscriber that _pulls_ items one by one. As the asynchronous Publisher mentioned earlier, this asynchronous Subscriber processes events in different threads than they were received.


```java
public class AsyncSubscriber<T> implements Flow.Subscriber<T>, Runnable {

   private final int id;
   private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
   private final AtomicBoolean cancelled = new AtomicBoolean(false);
   private final CountDownLatch completed = new CountDownLatch(1);
   private final Executor executor;

   public AsyncSubscriber(int id, Executor executor) {
       this.id = id;
       this.executor = Objects.requireNonNull(executor);
   }
```


These methods send signals to process the Subscriber’s events _onSubscribe_, _onNext_, _onError_, _onComplete_.


```java
   @Override
   public void onSubscribe(Flow.Subscription subscription) {
       logger.info("({}) subscriber.subscribe: {}", id, subscription);
       // By rule 2.13, calling onSubscribe must throw a NullPointerException when the given parameter is null.
       signal(new OnSubscribe(Objects.requireNonNull(subscription)));
   }

   @Override
   public void onNext(T item) {
       logger.info("({}) subscriber.next: {}", id, item);
       // By rule 2.13, calling onNext must throw a NullPointerException when the given parameter is null.
       signal(new OnNext(Objects.requireNonNull(item)));
   }

   @Override
   public void onError(Throwable t) {
       logger.error("({}) subscriber.error", id, t);
       // By rule 2.13, calling onError must throw a NullPointerException when the given parameter is null.
       signal(new OnError(Objects.requireNonNull(t)));
   }

   @Override
   public void onComplete() {
       logger.info("({}) subscriber.complete", id);
       signal(new OnComplete());
   }

   public void awaitCompletion() throws InterruptedException {
       completed.await();
   }

   // This method is invoked when OnNext signals arrive and returns whether more elements are desired.
   protected boolean whenNext(T item) {
       return true;
   }

   // This method is invoked when an OnError signal arrives.
   protected void whenError(Throwable t) {
   }

   // This method is invoked when an OnComplete signal arrives.
   protected void whenComplete() {
       completed.countDown();
   }
```


These methods process these events in the worker threads of the given Executor instance.


```java
   private void doSubscribe(Flow.Subscription subscription) {
       if (this.subscription.get() != null) {
           // By rule 2.5, a Subscriber must call Subscription.cancel() on the given Subscription
           // after an onSubscribe signal if it already has an active Subscription.
           subscription.cancel();
       } else {
           this.subscription.set(subscription);
           // By rule 2.1, a Subscriber must signal demand via Subscription.request(long) to receive onNext signals.
           this.subscription.get().request(1);
       }
   }

   private void doNext(T element) {
       // By rule 2.8, a Subscriber must be prepared to receive one or more onNext signals
       // after having called Subscription.cancel()
       if (!cancelled.get()) {
           if (whenNext(element)) {
               // By rule 2.1, a Subscriber must signal demand via Subscription.request(long) to receive onNext signals.
               subscription.get().request(1);
           } else {
               // By rule 2.6, a Subscriber must call Subscription.cancel() if the Subscription is no longer needed.
               doCancel();
           }
       }
   }

   private void doError(Throwable t) {
       // By rule 2.4, Subscriber.onError(Throwable) must consider the Subscription cancelled
       // after having received the signal.
       cancelled.set(true);
       whenError(t);
   }

   private void doComplete() {
       // By rule 2.4, Subscriber.onComplete() must consider the Subscription cancelled
       // after having received the signal.
       cancelled.set(true);
       whenComplete();
   }

   private void doCancel() {
       cancelled.set(true);
       subscription.get().cancel();
   }
```


These classes that implement the Signal interface represent the asynchronous signals. The OnSubscribe, OnNext, OnError, OnComplete classes are the signals for processing the correspondent Subscriber’s events.


```java
   // These classes represent the asynchronous signals.
   private interface Signal extends Runnable {
   }

   private class OnSubscribe implements Signal {
       private final Flow.Subscription subscription;

       OnSubscribe(Flow.Subscription subscription) {
           this.subscription = subscription;
       }

       @Override
       public void run() {
           doSubscribe(subscription);
       }
   }

   private class OnNext implements Signal {
       private final T element;

       OnNext(T element) {
           this.element = element;
       }

       @Override
       public void run() {
           doNext(element);
       }
   }

   private class OnError implements Signal {
       private final Throwable t;

       OnError(Throwable t) {
           this.t = t;
       }

       @Override
       public void run() {
           doError(t);
       }
   }

   private class OnComplete implements Signal {
       @Override
       public void run() {
           doComplete();
       }
   }
```


The asynchronous Consumer uses the same ConcurrentLinkedQueue queue and AtomicBoolean mutex to process these asynchronous signals _serially_ in the Executor instance.


```java
   // The non-blocking queue to transfer signals in a thread-safe way.
   private final Queue<Signal> signalsQueue = new ConcurrentLinkedQueue<>();

   // The mutex to establish the happens-before relationship between asynchronous signal calls.
   private final AtomicBoolean mutex = new AtomicBoolean(false);

   @Override
   public void run() {
       // By rule 2.7, a Subscriber must ensure that all calls on its Subscription's request, cancel methods
       // are performed serially.
       if (mutex.get()) {
           try {
               Signal signal = signalsQueue.poll();
               if (!cancelled.get()) {
                   signal.run();
               }
           } finally {
               mutex.set(false);
               if (!signalsQueue.isEmpty()) {
                   tryExecute();
               }
           }
       }
   }

   private void signal(Signal signal) {
       if (signalsQueue.offer(signal)) {
           tryExecute();
       }
   }

   private void tryExecute() {
       if (mutex.compareAndSet(false, true)) {
           try {
               executor.execute(this);
           } catch (Throwable t) {
               if (!cancelled.get()) {
                   try {
                       doCancel();
                   } finally {
                       signalsQueue.clear();
                       mutex.set(false);
                   }
               }
           }
       }
   }
}
```


<sub>The GitHub repository has <a href="https://github.com/aliakh/demo-java-reactive-streams/tree/main/src/test/java/demo/reactivestreams/part2">unit tests</a> to verify that the Publisher and Subscriber comply with all the Reactive Streams specification contracts that its TCK checks.</sub>

The following code fragment demonstrates that this asynchronous Publisher transfers the same sequence of events (the same pangram) to these two asynchronous Subscribers.


```java
ExecutorService executorService = Executors.newFixedThreadPool(3);

List<String> words = List.of("The quick brown fox jumps over the lazy dog.".split(" "));
Supplier<Iterator<? extends String>> iteratorSupplier = () -> List.copyOf(words).iterator();
AsyncIteratorPublisher<String> publisher = new AsyncIteratorPublisher<>(iteratorSupplier, 128, executorService);

AsyncSubscriber<String> subscriber1 = new AsyncSubscriber<>(1, executorService);
publisher.subscribe(subscriber1);

AsyncSubscriber<String> subscriber2 = new AsyncSubscriber<>(2, executorService);
publisher.subscribe(subscriber2);

subscriber1.awaitCompletion();
subscriber2.awaitCompletion();

executorService.shutdown();
executorService.awaitTermination(60, TimeUnit.SECONDS);
```


The invocation log of this code fragment shows that the asynchronous Publisher sends the sequence of events in a separate thread, and the asynchronous Subscribers receive these events in other separate threads _at the same time_.


```
12:00:00.043  pool-1-thread-2                   (2) subscriber.subscribe: demo.reactivestreams.part2.AsyncIteratorPublisher$SubscriptionImpl@3f2b429a
12:00:00.043  pool-1-thread-1                   (1) subscriber.subscribe: demo.reactivestreams.part2.AsyncIteratorPublisher$SubscriptionImpl@1282a13d
12:00:00.044  pool-1-thread-1                   subscription.request: 1
12:00:00.044  pool-1-thread-3                   subscription.request: 1
12:00:00.045  pool-1-thread-3                   (1) subscriber.next: The
12:00:00.045  pool-1-thread-1                   (2) subscriber.next: The
12:00:00.045  pool-1-thread-3                   subscription.request: 1
12:00:00.045  pool-1-thread-1                   subscription.request: 1
12:00:00.045  pool-1-thread-3                   (1) subscriber.next: quick
12:00:00.045  pool-1-thread-1                   (2) subscriber.next: quick
12:00:00.045  pool-1-thread-3                   subscription.request: 1
12:00:00.045  pool-1-thread-1                   subscription.request: 1
12:00:00.045  pool-1-thread-2                   (1) subscriber.next: brown
12:00:00.045  pool-1-thread-1                   (2) subscriber.next: brown
12:00:00.045  pool-1-thread-3                   subscription.request: 1
12:00:00.045  pool-1-thread-2                   subscription.request: 1
12:00:00.045  pool-1-thread-3                   (1) subscriber.next: fox
12:00:00.045  pool-1-thread-1                   (2) subscriber.next: fox
12:00:00.045  pool-1-thread-3                   subscription.request: 1
12:00:00.045  pool-1-thread-1                   subscription.request: 1
12:00:00.045  pool-1-thread-2                   (1) subscriber.next: jumps
12:00:00.046  pool-1-thread-3                   subscription.request: 1
12:00:00.046  pool-1-thread-1                   (2) subscriber.next: jumps
12:00:00.046  pool-1-thread-2                   (1) subscriber.next: over
12:00:00.046  pool-1-thread-2                   subscription.request: 1
12:00:00.046  pool-1-thread-1                   subscription.request: 1
12:00:00.046  pool-1-thread-2                   (2) subscriber.next: over
12:00:00.046  pool-1-thread-3                   (1) subscriber.next: the
12:00:00.046  pool-1-thread-2                   subscription.request: 1
12:00:00.046  pool-1-thread-3                   subscription.request: 1
12:00:00.046  pool-1-thread-2                   (2) subscriber.next: the
12:00:00.046  pool-1-thread-3                   (1) subscriber.next: lazy
12:00:00.046  pool-1-thread-2                   subscription.request: 1
12:00:00.046  pool-1-thread-3                   subscription.request: 1
12:00:00.046  pool-1-thread-2                   (2) subscriber.next: lazy
12:00:00.046  pool-1-thread-3                   (1) subscriber.next: dog.
12:00:00.046  pool-1-thread-2                   subscription.request: 1
12:00:00.046  pool-1-thread-3                   (1) subscriber.complete
12:00:00.046  pool-1-thread-1                   subscription.request: 1
12:00:00.046  pool-1-thread-2                   (2) subscriber.next: dog.
12:00:00.046  pool-1-thread-2                   (2) subscriber.complete
12:00:00.046  pool-1-thread-1                   subscription.request: 1
```