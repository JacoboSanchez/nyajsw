package io.github.jacobosanchez.nyajsw.lifecycle;

import static io.github.jacobosanchez.nyajsw.lifecycle.State.FAILED;
import static io.github.jacobosanchez.nyajsw.lifecycle.State.IDLE;
import static io.github.jacobosanchez.nyajsw.lifecycle.State.RESTART_WAIT;
import static io.github.jacobosanchez.nyajsw.lifecycle.State.RUNNING;
import static io.github.jacobosanchez.nyajsw.lifecycle.State.STARTING;
import static io.github.jacobosanchez.nyajsw.lifecycle.State.STOPPED;
import static io.github.jacobosanchez.nyajsw.lifecycle.State.STOPPING;
import static org.testng.Assert.assertEquals;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class StateTest {

    /** The transitions of plan §4.6 as far as P3.1 implements them; every pair not listed is refused. */
    private static final Map<State, Set<State>> EXPECTED = new EnumMap<>(Map.of(
            IDLE, EnumSet.of(STARTING, STOPPED, FAILED),
            STARTING, EnumSet.of(RUNNING, FAILED),
            RUNNING, EnumSet.of(STOPPING, RESTART_WAIT, IDLE, STOPPED, FAILED),
            STOPPING, EnumSet.of(STOPPED, FAILED),
            RESTART_WAIT, EnumSet.of(STARTING, STOPPED, FAILED),
            STOPPED, EnumSet.noneOf(State.class),
            FAILED, EnumSet.noneOf(State.class)));

    @DataProvider
    public Object[][] pairs() {
        List<Object[]> pairs = new ArrayList<>();
        for (State from : State.values()) {
            for (State to : State.values()) {
                pairs.add(new Object[] {from, to, EXPECTED.get(from).contains(to)});
            }
        }
        return pairs.toArray(Object[][]::new);
    }

    @Test(dataProvider = "pairs")
    public void transitionIsAllowedExactlyWhenTheTableSaysSo(State from, State to, boolean allowed) {
        assertEquals(from.canMoveTo(to), allowed, from + " -> " + to);
    }

    @Test
    public void everyStateIsInTheTable() {
        assertEquals(EXPECTED.keySet(), EnumSet.allOf(State.class));
    }

    @Test
    public void onlyStoppedAndFailedAreTerminal() {
        for (State state : State.values()) {
            assertEquals(state.isTerminal(), state == STOPPED || state == FAILED, state.name());
        }
    }

    @Test
    public void everyStateThatIsNotTerminalCanFail() {
        for (State state : State.values()) {
            assertEquals(state.canMoveTo(FAILED), !state.isTerminal(), state.name());
        }
    }

    @Test
    public void noStateMovesToItself() {
        for (State state : State.values()) {
            assertEquals(state.canMoveTo(state), false, state.name());
        }
    }

    @Test
    public void successorsAreACopy() {
        RUNNING.successors().clear();
        assertEquals(RUNNING.successors(), EXPECTED.get(RUNNING));
    }
}
