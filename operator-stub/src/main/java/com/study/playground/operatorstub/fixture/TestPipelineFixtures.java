package com.study.playground.operatorstub.fixture;

import java.util.List;
import java.util.Map;

public final class TestPipelineFixtures {

    private TestPipelineFixtures() {}

    public record TestPipeline(String pipelineId, String name, List<TestJob> jobs) {}
    public record TestJob(long jobId, String jobName, int jobOrder, long jenkinsInstanceId, String configJson) {}

    public static List<TestPipeline> all() {
        return List.of(
                new TestPipeline("linear-3", "Linear Chain (3 jobs)", List.of(
                        new TestJob(1L, "executor-test", 1, 1L, null)
                        , new TestJob(2L, "executor-test", 2, 1L, "{\"PARAM1\":\"value1\"}")
                        , new TestJob(3L, "executor-test", 3, 1L, null)
                ))
                , new TestPipeline("single", "Single Job", List.of(
                        new TestJob(1L, "executor-test", 1, 1L, null)
                ))
        );
    }

    public static TestPipeline findById(String pipelineId) {
        return all().stream()
                .filter(p -> p.pipelineId().equals(pipelineId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown pipelineId: " + pipelineId));
    }
}
