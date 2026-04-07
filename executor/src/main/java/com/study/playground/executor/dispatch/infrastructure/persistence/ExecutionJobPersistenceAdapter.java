package com.study.playground.executor.dispatch.infrastructure.persistence;

import com.study.playground.executor.dispatch.domain.model.ExecutionJob;
import com.study.playground.executor.dispatch.domain.model.ExecutionJobStatus;
import com.study.playground.executor.dispatch.domain.port.out.ExecutionJobPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * ExecutionJobPort 구현.
 * JPA Repository를 감싸고 Entity ↔ Domain 변환을 수행한다.
 */
@Component
@RequiredArgsConstructor
public class ExecutionJobPersistenceAdapter implements ExecutionJobPort {

    private final ExecutionJobJpaRepository jpaRepository;
    private final ExecutionJobMapper mapper;

    @Override
    public Optional<ExecutionJob> findById(String jobExcnId) {
        return jpaRepository.findById(jobExcnId).map(mapper::toDomain);
    }

    @Override
    public boolean existsById(String jobExcnId) {
        return jpaRepository.existsById(jobExcnId);
    }

    @Override
    public List<ExecutionJob> findDispatchableJobs(int limit) {
        return jpaRepository.findDispatchableJobs(limit).stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public List<ExecutionJob> findByStatus(ExecutionJobStatus status) {
        return jpaRepository.findByStatus(status.name()).stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public List<ExecutionJob> findByStatusAndBgngDtBefore(ExecutionJobStatus status, LocalDateTime cutoff) {
        return jpaRepository.findByStatusAndBgngDtBefore(status.name(), cutoff).stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public List<ExecutionJob> findByPipelineExcnId(String pipelineExcnId) {
        return jpaRepository.findByPipelineExcnId(pipelineExcnId).stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public int countByStatusIn(List<ExecutionJobStatus> statuses) {
        List<String> statusNames = statuses.stream()
                .map(Enum::name)
                .toList();
        return jpaRepository.countByStatusIn(statusNames);
    }

    @Override
    public boolean existsByJobIdAndStatusIn(String jobId, List<ExecutionJobStatus> statuses) {
        var statusNames = statuses.stream().map(Enum::name).toList();
        return jpaRepository.existsByJobIdAndStatusIn(jobId, statusNames);
    }

    @Override
    public Optional<ExecutionJob> findByJobIdAndBuildNo(String jobId, int buildNo) {
        return jpaRepository.findByJobIdAndBuildNo(jobId, buildNo)
                .map(mapper::toDomain);
    }

    @Override
    public ExecutionJob save(ExecutionJob job) {
        ExecutionJobEntity entity = mapper.toEntity(job);
        ExecutionJobEntity saved = jpaRepository.save(entity);
        return mapper.toDomain(saved);
    }
}
