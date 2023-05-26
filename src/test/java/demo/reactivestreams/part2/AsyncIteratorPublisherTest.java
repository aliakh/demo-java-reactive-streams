package demo.reactivestreams.part2;

import org.reactivestreams.tck.TestEnvironment;
import org.reactivestreams.tck.flow.FlowPublisherVerification;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

@Test
public class AsyncIteratorPublisherTest extends FlowPublisherVerification<Integer> {

    private ExecutorService executorService;

    public AsyncIteratorPublisherTest() {
        super(new TestEnvironment());
    }

    @BeforeClass
    void before() {
        executorService = Executors.newFixedThreadPool(4);
    }

    @AfterClass
    void after() {
        if (executorService != null) {
            executorService.shutdown();
        }
    }

    @Override
    public Flow.Publisher<Integer> createFlowPublisher(long elements) {
        return new AsyncIteratorPublisher<>(
            () -> Stream
                .iterate(0, UnaryOperator.identity())
                .limit(elements)
                .iterator(),
            1024,
            executorService
        );
    }

    @Override
    public Flow.Publisher<Integer> createFailedFlowPublisher() {
        return new AsyncIteratorPublisher<>(
            () -> {
                throw new RuntimeException();
            },
            1024,
            executorService
        );
    }
}
