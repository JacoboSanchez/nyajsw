package io.jsl.nyajsw.lifecycle;

import java.util.List;
import java.util.Objects;

/**
 * The steps of a stop, as {@link StopStrategyRegistry#chain} builds them from {@code
 * wrapper.stop.strategy}: the graceful strategies in order, which share the stop budget, then the
 * strategy that ends the application by force.
 *
 * @param graceful the strategies that ask the application to end, possibly none
 * @param last     {@code terminate}, run when the budget is spent
 * @param warnings problems found in the configured list, for the log
 */
public record StopChain(List<Step> graceful, Step last, List<String> warnings) {

    public StopChain {
        graceful = List.copyOf(graceful);
        Objects.requireNonNull(last, "last");
        warnings = List.copyOf(warnings);
    }

    /**
     * A strategy with the name it was configured under, for log messages.
     *
     * @param name     the name in {@code wrapper.stop.strategy}
     * @param strategy the strategy
     */
    public record Step(String name, StopStrategy strategy) {

        public Step {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(strategy, "strategy");
        }
    }
}
