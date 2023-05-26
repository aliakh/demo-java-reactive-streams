# Reactive Streams specification in Java


## Code examples


### Cold synchronous reactive stream

The following class demonstrates a synchronous Publisher that sends a finite sequence of events from an Iterator. The _synchronous_ Publisher processes its _subscribe_ event, and the Subscription’s events _request_ and _cancel_ in the caller thread. This _multicast_ Publisher can send events to multiple Subscribers, storing information about each connection in a nested private implementation of the Subscription interface. The Subscription implementation contains the current Iterator instance, the demand, and the cancellation flag. To make a _cold_ Publisher that sends the same sequence of events for each Subscriber, the Publisher stores a Supplier that must return a new Iterator instance for each invocation. The Publisher uses different types of error handling (throwing an exception or calling the Subscriber _onError_ method) according to the Reactive Streams specification.


```java
public class SyncIteratorPublisher<T> implements Flow.Publisher<T> {

   private final Supplier<Iterator<? extends T>> iteratorSupplier;

   public SyncIteratorPublisher(Supplier<Iterator<? extends T>> iteratorSupplier) {
       this.iteratorSupplier = Objects.requireNonNull(iteratorSupplier);
   }

   @Override
   public void subscribe(Flow.Subscriber<? super T> subscriber) {
       // By rule 1.11, a Publisher may support multiple Subscribers and decide
       // whether each Subscription is unicast or multicast.
       new SubscriptionImpl(subscriber);
   }

   private class SubscriptionImpl implements Flow.Subscription {

       private final Flow.Subscriber<? super T> subscriber;
       private final Iterator<? extends T> iterator;
       private final AtomicLong demand = new AtomicLong(0);
       private final AtomicBoolean cancelled = new AtomicBoolean(false);

       SubscriptionImpl(Flow.Subscriber<? super T> subscriber) {
           // By rule 1.9, calling Publisher.subscribe(Subscriber)
           // must throw a NullPointerException when the given parameter is null.
           this.subscriber = Objects.requireNonNull(subscriber);

           Iterator<? extends T> iterator = null;
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
           this.iterator = iterator;

           if (!cancelled.get()) {
               subscriber.onSubscribe(this);
           }
       }

       @Override
       public void request(long n) {
           logger.info("subscription.request: {}", n);

           // By rule 3.9, while the Subscription is not cancelled, Subscription.request(long)
           // must signal onError with a IllegalArgumentException if the argument is <= 0.
           if ((n <= 0) && !cancelled.get()) {
               doCancel();
               subscriber.onError(new IllegalArgumentException("non-positive subscription request"));
               return;
           }

           for (;;) {
               long oldDemand = demand.get();
               if (oldDemand == Long.MAX_VALUE) {
                   // By rule 3.17, a demand equal or greater than Long.MAX_VALUE
                   // may be considered by the Publisher as "effectively unbounded".
                   return;
               }

               // By rule 3.8, while the Subscription is not cancelled, Subscription.request(long)
               // must register the given number of additional elements to be produced to the respective Subscriber.
               long newDemand = oldDemand + n;
               if (newDemand < 0) {
                   // By rule 3.17, a Subscription must support a demand up to Long.MAX_VALUE.
                   newDemand = Long.MAX_VALUE;
               }

               // By rule 3.3, Subscription.request must place an upper bound on possible synchronous recursion
               // between Publisher and Subscriber.
               if (demand.compareAndSet(oldDemand, newDemand)) {
                   if (oldDemand > 0) {
                       return;
                   }
                   break;
               }
           }

           // By rule 1.2, a Publisher may signal fewer onNext than requested
           // and terminate the Subscription by calling onError.
           for (; demand.get() > 0 && iterator.hasNext() && !cancelled.get(); demand.decrementAndGet()) {
               try {
                   subscriber.onNext(iterator.next());
               } catch (Throwable t) {
                   if (!cancelled.get()) {
                       // By rule 1.6, if a Publisher signals onError on a Subscriber,
                       // that Subscriber's Subscription must be considered cancelled.
                       doCancel();
                       // By rule 1.4, if a Publisher fails it must signal an onError.
                       subscriber.onError(t);
                   }
               }
           }

           // By rule 1.2, a Publisher may signal fewer onNext than requested
           // and terminate the Subscription by calling onComplete.
           if (!iterator.hasNext() && !cancelled.get()) {
               // By rule 1.6, if a Publisher signals onComplete on a Subscriber,
               // that Subscriber's Subscription must be considered cancelled.
               doCancel();
               // By rule 1.5, if a Publisher terminates successfully it must signal an onComplete.
               subscriber.onComplete();
           }
       }

       @Override
       public void cancel() {
           logger.info("subscription.cancel");
           doCancel();
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
   }
}
```


The following class demonstrates a synchronous Subscriber that _pulls_ items one by one. The _synchronous_ Subscriber processes its _onSubscribe_, _onNext_, _onError_, _onComplete_ events in the Publisher thread. Like the Publisher, the Subscriber also stores its Subscription (to perform backpressure) and its cancellation flag. The Subscriber uses different types of error handling (throwing an exception or unsubscribing) according to the Reactive Streams specification.


```java
public class SyncSubscriber<T> implements Flow.Subscriber<T> {

   private final int id;
   private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
   private final AtomicBoolean cancelled = new AtomicBoolean(false);
   private final CountDownLatch completed = new CountDownLatch(1);

   public SyncSubscriber(int id) {
       this.id = id;
   }

   @Override
   public void onSubscribe(Flow.Subscription subscription) {
       logger.info("({}) subscriber.subscribe: {}", id, subscription);
       // By rule 2.13, calling onSubscribe must throw a NullPointerException when the given parameter is null.
       Objects.requireNonNull(subscription);

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

   @Override
   public void onNext(T item) {
       logger.info("({}) subscriber.next: {}", id, item);
       // By rule 2.13, calling onNext must throw a NullPointerException when the given parameter is null.
       Objects.requireNonNull(item);

       // By rule 2.8, a Subscriber must be prepared to receive one or more onNext signals
       // after having called Subscription.cancel()
       if (!cancelled.get()) {
           if (whenNext(item)) {
               // By rule 2.1, a Subscriber must signal demand via Subscription.request(long) to receive onNext signals.
               subscription.get().request(1);
           } else {
               // By rule 2.6, a Subscriber must call Subscription.cancel() if the Subscription is no longer needed.
               doCancel();
           }
       }
   }

   @Override
   public void onError(Throwable t) {
       logger.error("({}) subscriber.error", id, t);
       // By rule 2.13, calling onError must throw a NullPointerException when the given parameter is null.
       Objects.requireNonNull(t);

       // By rule 2.4, Subscriber.onError(Throwable) must consider the Subscription cancelled
       // after having received the signal.
       cancelled.set(true);
       whenError(t);
   }

   @Override
   public void onComplete() {
       logger.info("({}) subscriber.complete", id);

       // By rule 2.4, Subscriber.onComplete() must consider the Subscription cancelled
       // after having received the signal.
       cancelled.set(true);
       whenComplete();
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

   private void doCancel() {
       cancelled.set(true);
       subscription.get().cancel();
   }
}
```


<sub>The GitHub repository has <a href="https://github.com/aliakh/demo-java-reactive-streams/tree/main/src/test/java/demo/reactivestreams/part1">unit tests</a> to verify that the Publisher and Subscriber comply with all the Reactive Streams specification contracts that its TCK checks.</sub>

The following code fragment demonstrates that this synchronous Publisher transfers the same sequence of events (_[The quick brown fox jumps over the lazy dog](https://en.wikipedia.org/wiki/The_quick_brown_fox_jumps_over_the_lazy_dog)_ pangram) to these two synchronous Subscribers.


```java
List<String> words = List.of("The quick brown fox jumps over the lazy dog.".split(" "));
Supplier<Iterator<? extends String>> iteratorSupplier = () -> List.copyOf(words).iterator();
SyncIteratorPublisher<String> publisher = new SyncIteratorPublisher<>(iteratorSupplier);

SyncSubscriber<String> subscriber1 = new SyncSubscriber<>(1);
publisher.subscribe(subscriber1);

SyncSubscriber<String> subscriber2 = new SyncSubscriber<>(2);
publisher.subscribe(subscriber2);

subscriber1.awaitCompletion();
subscriber2.awaitCompletion();
```


The invocation log of this code fragment shows that the synchronous Publisher sends the sequence of events in one thread, and the synchronous Subscribers receive these events in the same thread _one at a time_.


```
12:00:00.034  main                              (1) subscriber.subscribe: demo.reactivestreams.part1.SyncIteratorPublisher$SubscriptionImpl@7791a895
12:00:00.037  main                              subscription.request: 1
12:00:00.037  main                              (1) subscriber.next: The
12:00:00.037  main                              subscription.request: 1
12:00:00.037  main                              (1) subscriber.next: quick
12:00:00.037  main                              subscription.request: 1
12:00:00.037  main                              (1) subscriber.next: brown
12:00:00.037  main                              subscription.request: 1
12:00:00.037  main                              (1) subscriber.next: fox
12:00:00.037  main                              subscription.request: 1
12:00:00.037  main                              (1) subscriber.next: jumps
12:00:00.037  main                              subscription.request: 1
12:00:00.037  main                              (1) subscriber.next: over
12:00:00.037  main                              subscription.request: 1
12:00:00.037  main                              (1) subscriber.next: the
12:00:00.037  main                              subscription.request: 1
12:00:00.037  main                              (1) subscriber.next: lazy
12:00:00.037  main                              subscription.request: 1
12:00:00.037  main                              (1) subscriber.next: dog.
12:00:00.037  main                              subscription.request: 1
12:00:00.037  main                              (1) subscriber.complete
12:00:00.037  main                              (2) subscriber.subscribe: demo.reactivestreams.part1.SyncIteratorPublisher$SubscriptionImpl@610694f1
12:00:00.037  main                              subscription.request: 1
12:00:00.037  main                              (2) subscriber.next: The
12:00:00.037  main                              subscription.request: 1
12:00:00.037  main                              (2) subscriber.next: quick
12:00:00.037  main                              subscription.request: 1
12:00:00.037  main                              (2) subscriber.next: brown
12:00:00.037  main                              subscription.request: 1
12:00:00.037  main                              (2) subscriber.next: fox
12:00:00.037  main                              subscription.request: 1
12:00:00.037  main                              (2) subscriber.next: jumps
12:00:00.037  main                              subscription.request: 1
12:00:00.037  main                              (2) subscriber.next: over
12:00:00.037  main                              subscription.request: 1
12:00:00.037  main                              (2) subscriber.next: the
12:00:00.037  main                              subscription.request: 1
12:00:00.037  main                              (2) subscriber.next: lazy
12:00:00.037  main                              subscription.request: 1
12:00:00.037  main                              (2) subscriber.next: dog.
12:00:00.037  main                              subscription.request: 1
12:00:00.037  main                              (2) subscriber.complete
```
