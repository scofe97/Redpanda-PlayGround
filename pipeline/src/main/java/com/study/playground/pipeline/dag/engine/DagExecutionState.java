package com.study.playground.pipeline.dag.engine;

import com.study.playground.pipeline.dag.domain.FailurePolicy;
import com.study.playground.pipeline.dag.domain.PipelineJob;

import java.util.*;

/**
 * DAG 실행의 런타임 상태를 추적한다.
 *
 * <p>실행당 하나의 인스턴스가 생성되며, 실행당 ReentrantLock이 상태 변경을 직렬화한다.
 * ConcurrentHashMap에 저장되므로 참조 교체 자체는 원자적이다.</p>
 *
 * <p>불변 필드(jobs, dependencyGraph 등)와 가변 필드(completedJobIds 등)를 명시적으로 분리한다.
 * 가변 상태 변경은 전용 메서드(markCompleted 등)를 통해서만 가능하고,
 * 외부에는 읽기 전용 뷰만 노출한다.</p>
 */
public class DagExecutionState {

    private final Map<Long, PipelineJob> jobs;
    private final Map<Long, Set<Long>> dependencyGraph;
    private final Map<Long, Set<Long>> successorGraph;
    private final Map<Long, Integer> jobIdToJobOrder;
    private final FailurePolicy failurePolicy;

    private final Set<Long> completedJobIds;
    private final Set<Long> runningJobIds;
    private final Set<Long> failedJobIds;
    private final Set<Long> skippedJobIds;

    private DagExecutionState(
            Map<Long, PipelineJob> jobs
            , Map<Long, Set<Long>> dependencyGraph
            , Map<Long, Set<Long>> successorGraph
            , Map<Long, Integer> jobIdToJobOrder
            , FailurePolicy failurePolicy) {
        this.jobs = jobs;
        this.dependencyGraph = dependencyGraph;
        this.successorGraph = successorGraph;
        this.jobIdToJobOrder = jobIdToJobOrder;
        this.failurePolicy = failurePolicy;
        this.completedJobIds = new HashSet<>();
        this.runningJobIds = new HashSet<>();
        this.failedJobIds = new HashSet<>();
        this.skippedJobIds = new HashSet<>();
    }

    /**
     * Job 목록으로부터 초기 실행 상태를 구성한다.
     *
     * @param jobList         실행할 Job 목록
     * @param jobIdToJobOrder Job ID → Job order 매핑
     * @return 초기 상태 (모든 Job이 미실행)
     */
    public static DagExecutionState initialize(
            List<PipelineJob> jobList
            , Map<Long, Integer> jobIdToJobOrder) {
        return initialize(jobList, jobIdToJobOrder, FailurePolicy.STOP_ALL);
    }

    /**
     * Job 목록과 실패 정책으로 초기 실행 상태를 구성한다.
     *
     * @param jobList         실행할 Job 목록
     * @param jobIdToJobOrder Job ID → Job order 매핑
     * @param failurePolicy   실패 시 적용할 정책
     * @return 초기 상태 (모든 Job이 미실행)
     */
    public static DagExecutionState initialize(
            List<PipelineJob> jobList
            , Map<Long, Integer> jobIdToJobOrder
            , FailurePolicy failurePolicy) {
        var jobs = new LinkedHashMap<Long, PipelineJob>();
        var deps = new HashMap<Long, Set<Long>>();
        var successors = new HashMap<Long, Set<Long>>();

        for (var job : jobList) {
            jobs.put(job.getId(), job);
            deps.put(job.getId(), job.getDependsOnJobIds() != null
                    ? new HashSet<>(job.getDependsOnJobIds())
                    : new HashSet<>());
            successors.put(job.getId(), new HashSet<>());
        }

        // 후속 그래프 구축
        for (var entry : deps.entrySet()) {
            for (Long depId : entry.getValue()) {
                successors.get(depId).add(entry.getKey());
            }
        }

        return new DagExecutionState(
                Collections.unmodifiableMap(jobs)
                , Collections.unmodifiableMap(deps)
                , Collections.unmodifiableMap(successors)
                , Collections.unmodifiableMap(jobIdToJobOrder)
                , failurePolicy != null ? failurePolicy : FailurePolicy.STOP_ALL
        );
    }

    // ── 상태 변경 메서드 ──────────────────────────────────────────────

    public void markCompleted(Long jobId) {
        completedJobIds.add(jobId);
    }

    public void markRunning(Long jobId) {
        runningJobIds.add(jobId);
    }

    public void markFailed(Long jobId) {
        failedJobIds.add(jobId);
    }

    public void markSkipped(Long jobId) {
        skippedJobIds.add(jobId);
    }

    public void removeRunning(Long jobId) {
        runningJobIds.remove(jobId);
    }

    // ── 읽기 전용 접근자 ──────────────────────────────────────────────

    public Map<Long, PipelineJob> jobs() {
        return jobs;
    }

    public Map<Long, Integer> jobIdToJobOrder() {
        return jobIdToJobOrder;
    }

    public FailurePolicy failurePolicy() {
        return failurePolicy;
    }

    /** 실행 중인 Job ID의 방어적 복사본을 반환한다. */
    public Set<Long> runningJobIds() {
        return Set.copyOf(runningJobIds);
    }

    /** 현재 실행 중인 Job 수를 반환한다. */
    public int runningCount() {
        return runningJobIds.size();
    }

    /**
     * 모든 의존성이 충족되어 실행 가능한 Job ID를 찾는다.
     * 이미 실행 중이거나 완료/실패/SKIP된 Job은 제외한다.
     *
     * @return 실행 가능한 Job ID 목록
     */
    public List<Long> findReadyJobIds() {
        var ready = new ArrayList<Long>();
        for (var entry : dependencyGraph.entrySet()) {
            Long jobId = entry.getKey();
            if (completedJobIds.contains(jobId)
                    || runningJobIds.contains(jobId)
                    || failedJobIds.contains(jobId)
                    || skippedJobIds.contains(jobId)) {
                continue;
            }
            // 모든 의존성이 완료되었는지 확인
            if (completedJobIds.containsAll(entry.getValue())) {
                ready.add(jobId);
            }
        }
        return ready;
    }

    /** 모든 Job이 종료 상태(성공/실패/SKIP)인지 확인한다. */
    public boolean isAllDone() {
        return completedJobIds.size() + failedJobIds.size() + skippedJobIds.size() == jobs.size();
    }

    /** 실패한 Job이 하나라도 있는지 확인한다. */
    public boolean hasFailure() {
        return !failedJobIds.isEmpty();
    }

    /** 실패한 Job 수를 반환한다. */
    public int failedCount() {
        return failedJobIds.size();
    }

    /** 실패한 Job ID의 방어적 복사본을 반환한다. */
    public Set<Long> failedJobIds() {
        return Set.copyOf(failedJobIds);
    }

    /** Job이 종료 상태(완료/실패/SKIP)인지 확인한다. */
    public boolean isTerminated(Long jobId) {
        return completedJobIds.contains(jobId)
                || failedJobIds.contains(jobId)
                || skippedJobIds.contains(jobId);
    }

    /**
     * 실패한 Job의 전이적 하위(transitive downstream) Job ID를 BFS로 수집한다.
     * SKIP_DOWNSTREAM 정책에서 사용한다.
     *
     * <p>이미 RUNNING 상태인 Job은 제외한다. 실행 중인 Job은 완료 후
     * onJobCompleted에서 재평가된다.</p>
     *
     * @param failedJobId 실패한 Job ID
     * @return 전이적 하위 Job ID 집합 (실패한 Job 자신은 포함하지 않음)
     */
    public Set<Long> allDownstream(Long failedJobId) {
        var downstream = new LinkedHashSet<Long>();
        var queue = new LinkedList<Long>();
        queue.add(failedJobId);

        while (!queue.isEmpty()) {
            Long current = queue.poll();
            Set<Long> succs = successorGraph.getOrDefault(current, Set.of());
            for (Long succ : succs) {
                if (!downstream.contains(succ) && !runningJobIds.contains(succ)) {
                    downstream.add(succ);
                    queue.add(succ);
                }
            }
        }
        return downstream;
    }

    /** 역방향 위상 순서로 완료된 Job ID를 반환한다 (SAGA 보상용). */
    public List<Long> completedJobIdsInReverseTopologicalOrder() {
        // BFS로 위상 정렬 후 역순
        var inDegree = new HashMap<Long, Integer>();
        for (Long jobId : completedJobIds) {
            inDegree.put(jobId, 0);
        }
        for (Long jobId : completedJobIds) {
            Set<Long> succs = successorGraph.getOrDefault(jobId, Set.of());
            for (Long succ : succs) {
                if (completedJobIds.contains(succ)) {
                    inDegree.merge(succ, 1, Integer::sum);
                }
            }
        }

        var queue = new LinkedList<Long>();
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        var sorted = new ArrayList<Long>();
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            sorted.add(current);
            Set<Long> succs = successorGraph.getOrDefault(current, Set.of());
            for (Long succ : succs) {
                if (completedJobIds.contains(succ)) {
                    int newDeg = inDegree.get(succ) - 1;
                    inDegree.put(succ, newDeg);
                    if (newDeg == 0) {
                        queue.add(succ);
                    }
                }
            }
        }

        // 역순으로 반환 (leaf → root)
        Collections.reverse(sorted);
        return sorted;
    }
}
