package org.viators.orderprocessingsystem.saga;

/**
 * Represents a single step within a saga.
 *
 * A step is the fundamental unit of a saga. It defines:
 *   - execute()    — the forward operation (the thing we're trying to do)
 *   - compensate() — the undo operation (what to do if a later step fails)
 *
 * Design decision — why a functional interface?
 * We declare this as @FunctionalInterface because each step has exactly one
 * primary abstract method (execute). This allows steps to be written as lambdas
 * when their logic is simple, which you'll see in OrderPlacementSaga.
 * For complex steps, we use full classes that implement this interface.
 *
 * Why does compensate() have a default implementation?
 * Not every step has a meaningful compensation. Validation steps (read-only
 * operations) have nothing to undo. Providing a no-op default means validation
 * steps don't need to override it — they just implement execute().
 *
 * Why no parameters on execute() or compensate()?
 * Steps receive the data they need at construction time, stored as fields.
 * This keeps the SagaOrchestrator generic — it doesn't need to know what data
 * any step operates on. See SagaContext for how steps share state with each other.
 */
@FunctionalInterface
public interface SagaStep {

    /**
     * Executes the forward operation.
     * Throws Exception if the step fails — the orchestrator catches this and triggers compensation.
     */
    void execute() throws Exception;

    /**
     * Compensates (undoes) the effects of a successful execute().
     *
     * This is only called if this step's execute() succeeded AND a later step failed.
     * If this step's execute() itself failed, this method is NOT called.
     *
     * Implementations must be:
     *   - Idempotent: safe to call multiple times without causing additional side effects.
     *     If the system retries compensation, the result must be the same as calling it once.
     *   - Non-throwing: compensation failures should be caught and logged inside the
     *     implementation. Never propagate an exception from compensate() — the orchestrator
     *     will continue compensating other steps regardless, but your logs need the error.
     *
     * The default implementation is a no-op, for read-only steps that have no side effects to undo.
     */
    default void compensate() {
        // No-op by default — override in steps that have side effects
    }

    /**
     * A human-readable name for this step, used in logging.
     * Override this to give your step a meaningful name that appears in saga execution logs.
     * Helps enormously when debugging failed sagas.
     */
    default String name() {
        return this.getClass().getSimpleName();
    }
}
