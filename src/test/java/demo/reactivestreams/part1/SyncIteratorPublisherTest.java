package demo.reactivestreams.part1;

import org.reactivestreams.tck.TestEnvironment;
import org.reactivestreams.tck.flow.FlowPublisherVerification;
import org.testng.annotations.Test;

import java.util.concurrent.Flow;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

@Test
public class SyncIteratorPublisherTest extends FlowPublisherVerification<Integer> {

    public SyncIteratorPublisherTest() {
        super(new TestEnvironment());
    }

    @Override
    public Flow.Publisher<Integer> createFlowPublisher(long elements) {
        return new SyncIteratorPublisher<>(
            () -> Stream
                .iterate(0, UnaryOperator.identity())
                .limit(elements)
                .iterator()
        );
    }

    @Override
    public Flow.Publisher<Integer> createFailedFlowPublisher() {
        return new SyncIteratorPublisher<>(
            () -> {
                throw new RuntimeException();
            }
        );
    }
}
