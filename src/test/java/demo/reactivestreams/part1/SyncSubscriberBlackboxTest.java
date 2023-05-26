package demo.reactivestreams.part1;

import org.reactivestreams.tck.TestEnvironment;
import org.reactivestreams.tck.flow.FlowSubscriberBlackboxVerification;
import org.testng.annotations.Test;

import java.util.concurrent.Flow;

@Test
public class SyncSubscriberBlackboxTest extends FlowSubscriberBlackboxVerification<Integer> {

    public SyncSubscriberBlackboxTest() {
        super(new TestEnvironment());
    }

    @Override
    public Flow.Subscriber<Integer> createFlowSubscriber() {
        return new SyncSubscriber<>(0);
    }

    @Override
    public Integer createElement(int element) {
        return element;
    }
}
