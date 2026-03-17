package org.viators.orderprocessingsystem.saga;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Generic saga orchestrator.
 *
 * Executes a list of SagaSteps sequentially. If any step fails, all previously
 * completed steps are compensated in reverse order.
 *
 * Why @Component?
 * The orchestrator is stateless — it holds no instance fields beyond what's passed
 * into execute(). This makes it safe to be a singleton Spring bean. Multiple threads
 * can call execute() concurrently without interference because all state is in the
 * method's local variables.
 *
 * Thread safety:
 * This class is stateless and therefore inherently thread-safe. Each call to execute()
 * creates its own completedSteps list, which lives on the stack of the calling thread.
 */
@Component
@Slf4j
public class SagaOrchestrator {

    /**
     * Executes a saga defined by an ordered list of steps.
     *
     * Algorithm:
     *   1. Iterate through each step and call execute().
     *   2. Track each successfully completed step in completedSteps.
     *   3. If a step throws, immediately begin compensation: iterate completedSteps
     *      in reverse, calling compensate() on each.
     *   4. If a compensation step itself throws, log the error and continue compensating
     *      remaining steps — never abort compensation early.
     *   5. After all compensations are attempted, re-throw the original exception so the
     *      caller knows the saga failed and can return an appropriate error response.
     *
     * Why re-throw the original exception?
     * The caller needs to know the saga failed and why. The original exception carries
     * the business-meaningful message (e.g., "Insufficient stock for product X").
     * Swallowing it and throwing a generic "Saga failed" loses context that's valuable
     * for the error response.
     *
     * @param steps the ordered list of steps to execute
     * @throws Exception the original exception from the failed step, after compensation
     */
    public void execute(List<SagaStep> steps) throws Exception {
        // Tracks steps that have successfully executed, so we know what to compensate.
        // We add to this list AFTER a step succeeds — never before.
        List<SagaStep> completedSteps = new ArrayList<>();

        for (SagaStep step : steps) {
            try {
                log.info("[Sage] Executing step: {}", step.name());
                step.execute();
                completedSteps.add(step);
                log.info("[Saga] Step completed: {}",  step.name());
            } catch (Exception e) {
                log.error("[Saga] Step failed: {} - {}", step.name(), e.getMessage());
                log.info("Beginning compensation for {} completed step(s)", completedSteps.size());

                // Compensate in reverse order
                compensate(completedSteps);

                // Re-throw the original exception so the caller gets a meaningful error
                throw e;
            }
        }

        log.info("[Saga] All {} step(s) completed successfully", steps.size());
    }

    /**
     * Runs compensation in reverse order of execution.
     *
     * Compensation MUST be resilient. Even if one compensation step fails, we continue
     * compensating the remaining steps. Stopping early leaves more state inconsistent, not less.
     *
     * This is why each compensation failure is caught and logged rather than propagated.
     * If compensation itself fails, the system is in an inconsistent state that requires
     * manual intervention — but we log enough information to allow that intervention.
     *
     * @param completedSteps the steps to compensate, in forward-execution order.
     *                       This method reverses the order internally.
     */
    private void compensate(List<SagaStep> completedSteps) {
        for (int i = completedSteps.size() - 1; i >= 0; i--) {
            SagaStep step = completedSteps.get(i);

            try {
                log.info("[Saga] Compensating step: {}", step.name());
                step.compensate();
                log.info("[Saga] Compensation complete: {}", step.name());
            } catch (Exception e) {
                // Log and CONTINUE — never abort compensation
                log.error("[Saga] COMPENSATION FAILED for step: {} — {}. " +
                        "Manual intervention may be required.",
                    step.name(), e.getMessage(), e);
            }
        }
    }
}
