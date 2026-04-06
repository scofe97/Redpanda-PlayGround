package com.study.playground.executor.dispatch.domain.port.out;

import com.study.playground.executor.dispatch.domain.model.ExecutionJob;
import com.study.playground.executor.dispatch.domain.model.ExecutionJobStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * ExecutionJob 영속성 out-port (조회 + 저장 통합).
 * infrastructure의 PersistenceAdapter가 JPA로 구현한다.
 */
public interface ExecutionJobPort {

    // === 조회 ===

    /** Job 실행 건 식별자로 단건 조회한다. */
    Optional<ExecutionJob> findById(String jobExcnId);

    /** 해당 식별자의 Job이 이미 존재하는지 확인한다. 멱등성 체크용. */
    boolean existsById(String jobExcnId);

    /** 디스패치 가능한 PENDING Job을 우선순위 순으로 조회한다. FOR UPDATE SKIP LOCKED. */
    List<ExecutionJob> findDispatchableJobs(int limit);

    /** 특정 상태의 Job 목록을 조회한다. */
    List<ExecutionJob> findByStatus(ExecutionJobStatus status);

    /** 타임아웃 감지용 — RUNNING 상태이고 시작일시가 기준 시각보다 이전인 Job. */
    List<ExecutionJob> findByStatusAndBgngDtBefore(ExecutionJobStatus status, LocalDateTime cutoff);

    /** 파이프라인 실행 건에 속한 모든 Job을 조회한다. */
    List<ExecutionJob> findByPipelineExcnId(String pipelineExcnId);

    /** 주어진 상태 목록에 해당하는 활성 Job 수. 슬롯 계산용. */
    int countByStatusIn(List<ExecutionJobStatus> statuses);

    /** 동일 jobId(정의)가 특정 상태에 있는지 확인한다. 중복 실행 방지용. */
    boolean existsByJobIdAndStatusIn(String jobId, List<ExecutionJobStatus> statuses);

    /** jobId + buildNo로 ExecutionJob을 조회한다. 콜백 매칭용. */
    Optional<ExecutionJob> findByJobIdAndBuildNo(String jobId, int buildNo);

    /** jobId로 QUEUED 또는 RUNNING 상태의 활성 Job을 조회한다. STARTED 콜백 매칭용. */
    Optional<ExecutionJob> findActiveByJobId(String jobId);

    // === 저장 ===

    /** Job을 저장(신규 또는 갱신)하고 저장된 결과를 반환한다. */
    ExecutionJob save(ExecutionJob job);
}
