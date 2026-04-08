package com.study.playground.operator.pipeline.job.infrastructure.persistence;

import com.study.playground.operator.pipeline.job.domain.model.Job;
import com.study.playground.operator.pipeline.job.domain.port.out.LoadJobPort;
import com.study.playground.operator.pipeline.job.domain.port.out.SaveJobPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class JobPersistenceAdapter implements LoadJobPort, SaveJobPort {

    private final JobJpaRepository jpaRepository;
    private final JobMapper mapper;

    @Override
    public Optional<Job> findById(String jobId) {
        return jpaRepository.findById(jobId).map(mapper::toDomain);
    }

    @Override
    public List<Job> findByProjectId(String projectId) {
        return jpaRepository.findByProjectIdAndDeletedFalse(projectId).stream()
                .map(mapper::toDomain).toList();
    }

    @Override
    public List<Job> findAll() {
        return jpaRepository.findByDeletedFalse().stream()
                .map(mapper::toDomain).toList();
    }

    @Override
    public Job save(Job job) {
        var saved = jpaRepository.save(mapper.toEntity(job));
        return mapper.toDomain(saved);
    }
}
