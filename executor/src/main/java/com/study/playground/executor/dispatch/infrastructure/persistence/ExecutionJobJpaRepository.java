package com.study.playground.executor.dispatch.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

import java.time.LocalDateTime;
import java.util.List;

public interface ExecutionJobJpaRepository extends JpaRepository<ExecutionJobEntity, String> {

    @Query(value = """
            SELECT ej.* FROM execution_job ej
            WHERE ej.excn_stts = 'PENDING'
            ORDER BY ej.priority ASC, ej.priority_dt ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<ExecutionJobEntity> findDispatchableJobs(@Param("limit") int limit);

    @Query("SELECT COUNT(e) FROM ExecutionJobEntity e WHERE e.status IN :statuses")
    int countByStatusIn(@Param("statuses") List<String> statuses);

    List<ExecutionJobEntity> findByStatus(String status);

    List<ExecutionJobEntity> findByStatusAndBgngDtBefore(String status, LocalDateTime cutoff);

    List<ExecutionJobEntity> findByPipelineExcnId(String pipelineExcnId);

    /**
     * 동일 jobId(정의)가 특정 상태에 있는지 확인한다. 중복 실행 방지용.
     */
    boolean existsByJobIdAndStatusIn(String jobId, List<String> statuses);

    Optional<ExecutionJobEntity> findByJobIdAndBuildNo(String jobId, Integer buildNo);
}
