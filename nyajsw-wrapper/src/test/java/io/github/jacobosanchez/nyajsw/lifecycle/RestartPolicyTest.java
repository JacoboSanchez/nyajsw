package io.github.jacobosanchez.nyajsw.lifecycle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.time.Duration;

import org.testng.annotations.Test;

public class RestartPolicyTest {

    private static final Duration SUCCESSFUL = Duration.ofSeconds(60);

    @Test
    public void anInvocationShorterThanTheSuccessfulTimeIsAFailure() {
        RestartPolicy policy = new RestartPolicy(2, SUCCESSFUL);

        policy.ended(SUCCESSFUL.minusNanos(1));
        assertEquals(policy.failures(), 1);
        assertTrue(policy.mayRestart());

        policy.ended(Duration.ZERO);
        assertEquals(policy.failures(), 2);
        assertFalse(policy.mayRestart());
    }

    @Test
    public void anInvocationOfExactlyTheSuccessfulTimeResetsTheCount() {
        RestartPolicy policy = new RestartPolicy(2, SUCCESSFUL);
        policy.ended(Duration.ZERO);

        policy.ended(SUCCESSFUL);

        assertEquals(policy.failures(), 0);
        assertTrue(policy.mayRestart());
    }

    @Test
    public void aLaunchErrorIsAFailure() {
        RestartPolicy policy = new RestartPolicy(1, SUCCESSFUL);

        policy.failed();

        assertEquals(policy.failures(), 1);
        assertFalse(policy.mayRestart());
    }

    @Test
    public void aLimitBelowOneCountsAsOne() {
        for (int limit : new int[] {0, -3}) {
            RestartPolicy policy = new RestartPolicy(limit, SUCCESSFUL);
            assertEquals(policy.maxFailedInvocations(), 1);
            assertTrue(policy.mayRestart(), "nothing has failed yet");
            policy.ended(Duration.ZERO);
            assertFalse(policy.mayRestart());
        }
    }
}
