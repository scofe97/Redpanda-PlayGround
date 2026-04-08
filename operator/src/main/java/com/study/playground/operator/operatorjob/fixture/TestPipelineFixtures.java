package com.study.playground.operator.operatorjob.fixture;

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
                , new TestPipeline("seq-4-k8s", "Sequential 4 Jobs (K8s)", List.of(
                        new TestJob(1L, "executor-test", 1, 1L, null)
                        , new TestJob(2L, "executor-test", 2, 1L, null)
                        , new TestJob(3L, "executor-test", 3, 1L, null)
                        , new TestJob(4L, "executor-test", 4, 1L, null)
                ))
                , new TestPipeline("mixed-3-multi-jenkins", "Mixed 3 Jobs (Multi-Jenkins)", List.of(
                        new TestJob(1L, "executor-test", 1, 1L, null)      // Jenkins-1
                        , new TestJob(2L, "executor-test", 2, 6L, null)    // Jenkins-2
                        , new TestJob(3L, "executor-test", 3, 1L, null)    // Jenkins-1
                ))
                , new TestPipeline("mixed-3-jenkins2-first", "Mixed 3 Jobs (Jenkins-2 First)", List.of(
                        new TestJob(2L, "executor-test", 1, 6L, null)      // Jenkins-2
                        , new TestJob(1L, "executor-test", 2, 1L, null)    // Jenkins-1
                        , new TestJob(3L, "executor-test", 3, 6L, null)    // Jenkins-2
                ))
        );
    }

    public static TestPipeline findById(String pipelineId) {
        return all().stream()
                .filter(p -> p.pipelineId().equals(pipelineId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown pipelineId: " + pipelineId));
    }

    public static TestJob findJobByName(String jobName) {
        return all().stream()
                .flatMap(p -> p.jobs().stream())
                .filter(j -> j.jobName().equals(jobName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown jobName: " + jobName));
    }
}
