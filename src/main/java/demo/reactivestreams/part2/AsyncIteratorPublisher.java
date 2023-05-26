package demo.reactivestreams.part2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public class AsyncIteratorPublisher<T> implements Flow.Publisher<T> {

    private static final Logger logger = LoggerFactory.getLogger(AsyncIteratorPublisher.class);

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
                    // that Subscriber’s Subscription must be considered cancelled.
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
            // that Subscriber’s Subscription must be considered cancelled.
            cancelled.set(true);
            subscriber.onError(t);
        }

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
