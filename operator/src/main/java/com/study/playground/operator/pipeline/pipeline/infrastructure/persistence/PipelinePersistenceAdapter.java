package com.study.playground.operator.pipeline.pipeline.infrastructure.persistence;

import com.study.playground.operator.pipeline.pipeline.domain.model.Pipeline;
import com.study.playground.operator.pipeline.pipeline.domain.model.PipelineStep;
import com.study.playground.operator.pipeline.pipeline.domain.model.PipelineVersion;
import com.study.playground.operator.pipeline.pipeline.domain.port.out.LoadPipelinePort;
import com.study.playground.operator.pipeline.pipeline.domain.port.out.SavePipelinePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class PipelinePersistenceAdapter implements LoadPipelinePort, SavePipelinePort {

    private final PipelineJpaRepository pipelineRepo;
    private final PipelineVersionJpaRepository versionRepo;
    private final PipelineStepJpaRepository stepRepo;
    private final PipelineMapper mapper;

    @Override
    public Optional<Pipeline> findById(String pipelineId) {
        return pipelineRepo.findById(pipelineId).map(mapper::toDomain);
    }

    @Override
    public List<Pipeline> findByProjectId(String projectId) {
        return pipelineRepo.findByProjectIdAndDeletedFalse(projectId).stream()
                .map(mapper::toDomain).toList();
    }

    @Override
    public List<Pipeline> findAll() {
        return pipelineRepo.findByDeletedFalse().stream()
                .map(mapper::toDomain).toList();
    }

    @Override
    public Optional<PipelineVersion> findLatestVersion(String pipelineId) {
        return versionRepo.findTopByPipelineIdOrderByVersionDesc(pipelineId)
                .map(verEntity -> {
                    var steps = stepRepo.findByVersionIdOrderByStepOrderAsc(verEntity.getVersionId());
                    return mapper.toVersionDomain(verEntity, steps);
                });
    }

    @Override
    public int getNextVersionNumber(String pipelineId) {
        return versionRepo.findNextVersionNumber(pipelineId);
    }

    @Override
    public Pipeline save(Pipeline pipeline) {
        var saved = pipelineRepo.save(mapper.toEntity(pipeline));
        return mapper.toDomain(saved);
    }

    @Override
    public PipelineVersion saveVersion(PipelineVersion version) {
        var verEntity = mapper.toVersionEntity(version);
        var savedVer = versionRepo.save(verEntity);

        for (PipelineStep step : version.getSteps()) {
            var stepEntity = mapper.toStepEntity(step, savedVer.getVersionId());
            stepRepo.save(stepEntity);
        }

        version.setVersionId(savedVer.getVersionId());
        return version;
    }
}
