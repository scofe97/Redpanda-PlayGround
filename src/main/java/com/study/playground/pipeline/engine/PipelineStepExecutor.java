package com.study.playground.pipeline.engine;

import com.study.playground.pipeline.domain.PipelineExecution;
import com.study.playground.pipeline.domain.PipelineStep;

public interface PipelineStepExecutor {
    void execute(PipelineStep step) throws Exception;

    default void execute(PipelineExecution execution, PipelineStep step) throws Exception {
        execute(step);
    }

    /**
     * Compensates the effect of a previously successful step execution.
     * Called during SAGA rollback in reverse order.
     * Default implementation is no-op (safe for read-only or idempotent steps).
     */
    default void compensate(PipelineExecution execution, PipelineStep step) throws Exception {
        // No-op by default — override in steps that have side effects to reverse
    }
}
