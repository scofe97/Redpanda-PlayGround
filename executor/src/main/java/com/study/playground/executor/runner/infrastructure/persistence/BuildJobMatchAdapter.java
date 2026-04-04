package com.study.playground.executor.runner.infrastructure.persistence;

import com.study.playground.executor.dispatch.domain.model.ExecutionJob;
import com.study.playground.executor.dispatch.infrastructure.persistence.ExecutionJobJpaRepository;
import com.study.playground.executor.dispatch.infrastructure.persistence.ExecutionJobMapper;
import com.study.playground.executor.runner.domain.port.out.ResolveJobByBuildPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * ResolveJobByBuildPort 구현.
 * executor DB에서 jobId + buildNo로 ExecutionJob을 매칭한다.
 */
@Component
@RequiredArgsConstructor
public class BuildJobMatchAdapter implements ResolveJobByBuildPort {

    private final ExecutionJobJpaRepository jpaRepository;
    private final ExecutionJobMapper mapper;

    @Override
    public Optional<ExecutionJob> findByJobIdAndBuildNo(String jobId, int buildNo) {
        return jpaRepository.findByJobIdAndBuildNo(jobId, buildNo)
                .map(mapper::toDomain);
    }
}
