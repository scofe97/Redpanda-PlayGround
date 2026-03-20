package com.study.playground.pipeline.engine;

import com.study.playground.pipeline.domain.PipelineJob;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * DAG(Directed Acyclic Graph) 유효성을 검증한다.
 *
 * <p>Kahn's algorithm으로 순환 탐지와 위상 정렬을 동시에 수행한다.
 * BFS 기반이므로 DFS보다 구현이 단순하고, 순환 발견 시 "어떤 노드가 순환에
 * 포함되었는지"를 남은 노드 집합으로 즉시 알 수 있다는 장점이 있다.</p>
 *
 * <p>연결성 검증도 함께 수행한다. 단절 그래프는 실행 순서가 모호하므로 거부한다.</p>
 */
@Component
public class DagValidator {

    /**
     * Job 목록의 DAG 유효성을 검증한다.
     *
     * @param jobs 검증할 Job 목록 (dependsOnJobIds가 로드된 상태)
     * @throws IllegalStateException DAG 조건(비순환, 연결성)을 위반할 때
     */
    public void validate(List<PipelineJob> jobs) {
        if (jobs == null || jobs.isEmpty()) {
            return;
        }
        runKahns(jobs);
    }

    /**
     * 위상 정렬 결과를 반환한다. validate()와 동일한 Kahn's algorithm을 사용하며
     * 정렬된 Job ID 리스트를 반환한다.
     *
     * @param jobs 위상 정렬할 Job 목록
     * @return 위상 정렬된 Job ID 리스트
     */
    public List<Long> topologicalSort(List<PipelineJob> jobs) {
        return runKahns(jobs);
    }

    /**
     * Kahn's algorithm을 실행하여 위상 정렬과 검증을 동시에 수행한다.
     *
     * @param jobs 검증 및 정렬할 Job 목록
     * @return 위상 정렬된 Job ID 리스트
     * @throws IllegalStateException DAG 조건(비순환, 연결성)을 위반할 때
     */
    private List<Long> runKahns(List<PipelineJob> jobs) {
        // Job ID → Job 매핑
        Map<Long, PipelineJob> jobMap = new HashMap<>();
        for (var job : jobs) {
            jobMap.put(job.getId(), job);
        }

        // 진입 차수(in-degree) 계산
        Map<Long, Integer> inDegree = new HashMap<>();
        Map<Long, List<Long>> adjacency = new HashMap<>(); // 후속 Job 목록

        for (var job : jobs) {
            inDegree.putIfAbsent(job.getId(), 0);
            adjacency.putIfAbsent(job.getId(), new ArrayList<>());
        }

        for (var job : jobs) {
            if (job.getDependsOnJobIds() != null) {
                for (Long depId : job.getDependsOnJobIds()) {
                    if (!jobMap.containsKey(depId)) {
                        throw new IllegalStateException(
                                "Job '%s'이 존재하지 않는 Job ID %d에 의존합니다"
                                        .formatted(job.getJobName(), depId));
                    }
                    adjacency.get(depId).add(job.getId());
                    inDegree.merge(job.getId(), 1, Integer::sum);
                }
            }
        }

        // Kahn's algorithm: BFS로 위상 정렬
        Queue<Long> queue = new LinkedList<>();
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        if (queue.isEmpty()) {
            throw new IllegalStateException("루트 Job이 없습니다 (모든 Job이 의존성을 가짐 — 순환 의심)");
        }

        List<Long> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            sorted.add(current);

            for (Long successor : adjacency.get(current)) {
                int newDegree = inDegree.get(successor) - 1;
                inDegree.put(successor, newDegree);
                if (newDegree == 0) {
                    queue.add(successor);
                }
            }
        }

        // 순환 탐지: 처리되지 않은 노드가 있으면 순환이 존재한다
        if (sorted.size() != jobs.size()) {
            List<String> cycleJobs = jobs.stream()
                    .filter(j -> inDegree.get(j.getId()) > 0)
                    .map(PipelineJob::getJobName)
                    .toList();
            throw new IllegalStateException(
                    "순환 의존성이 감지되었습니다: " + String.join(", ", cycleJobs));
        }

        // 연결성 검증: 루트에서 모든 노드에 도달 가능한지 BFS로 확인
        validateConnectivity(jobs, adjacency);

        return sorted;
    }

    private void validateConnectivity(
            List<PipelineJob> jobs
            , Map<Long, List<Long>> adjacency) {
        // 엣지가 없으면 전체 병렬 실행 — 연결성 검증 불필요
        boolean hasEdges = adjacency.values().stream().anyMatch(list -> !list.isEmpty());
        if (!hasEdges) {
            return;
        }

        // 양방향 인접 리스트 구축 (무방향 연결성 검사)
        Map<Long, Set<Long>> undirected = new HashMap<>();
        for (var job : jobs) {
            undirected.putIfAbsent(job.getId(), new HashSet<>());
        }
        for (var entry : adjacency.entrySet()) {
            for (Long successor : entry.getValue()) {
                undirected.get(entry.getKey()).add(successor);
                undirected.get(successor).add(entry.getKey());
            }
        }

        // BFS로 첫 번째 노드에서 도달 가능한 노드 수 확인
        Set<Long> visited = new HashSet<>();
        Queue<Long> bfs = new LinkedList<>();
        Long startNode = jobs.get(0).getId();
        bfs.add(startNode);
        visited.add(startNode);

        while (!bfs.isEmpty()) {
            Long current = bfs.poll();
            for (Long neighbor : undirected.get(current)) {
                if (visited.add(neighbor)) {
                    bfs.add(neighbor);
                }
            }
        }

        if (visited.size() != jobs.size()) {
            throw new IllegalStateException(
                    "단절된 그래프입니다: %d개 Job 중 %d개만 연결되어 있습니다"
                            .formatted(jobs.size(), visited.size()));
        }
    }
}
