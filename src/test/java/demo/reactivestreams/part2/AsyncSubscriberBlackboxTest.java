package demo.reactivestreams.part2;

import org.reactivestreams.tck.TestEnvironment;
import org.reactivestreams.tck.flow.FlowSubscriberBlackboxVerification;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;

@Test
public class AsyncSubscriberBlackboxTest extends FlowSubscriberBlackboxVerification<Integer> {

    private ExecutorService executorService;

    public AsyncSubscriberBlackboxTest() {
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
    public Flow.Subscriber<Integer> createFlowSubscriber() {
        return new AsyncSubscriber<>(0, executorService);
    }

    @Override
    public Integer createElement(int element) {
        return element;
    }
}
