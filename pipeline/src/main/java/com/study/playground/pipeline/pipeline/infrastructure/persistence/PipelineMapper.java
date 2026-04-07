package com.study.playground.pipeline.pipeline.infrastructure.persistence;

import com.study.playground.pipeline.pipeline.domain.model.Pipeline;
import com.study.playground.pipeline.pipeline.domain.model.PipelineStep;
import com.study.playground.pipeline.pipeline.domain.model.PipelineVersion;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PipelineMapper {

    public Pipeline toDomain(PipelineEntity entity) {
        var p = Pipeline.create(
                entity.getPipelineId(), entity.getProjectId()
                , entity.getName(), entity.getDescription()
                , entity.getCreatedBy()
        );
        if (entity.isFailContinue()) {
            p.update(p.getName(), p.getDescription(), true, p.getUpdatedBy());
        }
        return p;
    }

    public PipelineEntity toEntity(Pipeline domain) {
        var e = new PipelineEntity();
        e.setPipelineId(domain.getPipelineId());
        e.setProjectId(domain.getProjectId());
        e.setName(domain.getName());
        e.setDescription(domain.getDescription());
        e.setFailContinue(domain.isFailContinue());
        e.setInOutType(domain.getInOutType());
        e.setDeleted(domain.isDeleted());
        e.setCreatedAt(domain.getCreatedAt());
        e.setCreatedBy(domain.getCreatedBy());
        e.setUpdatedAt(domain.getUpdatedAt());
        e.setUpdatedBy(domain.getUpdatedBy());
        return e;
    }

    public PipelineVersion toVersionDomain(PipelineVersionEntity entity, List<PipelineStepEntity> stepEntities) {
        var v = PipelineVersion.create(
                entity.getPipelineId(), entity.getVersion()
                , entity.getDescription(), entity.getCreatedBy()
        );
        v.setVersionId(entity.getVersionId());
        for (var se : stepEntities) {
            v.addStep(se.getJobId(), se.getStepOrder(), se.getCreatedBy());
        }
        return v;
    }

    public PipelineVersionEntity toVersionEntity(PipelineVersion domain) {
        var e = new PipelineVersionEntity();
        e.setVersionId(domain.getVersionId());
        e.setPipelineId(domain.getPipelineId());
        e.setVersion(domain.getVersion());
        e.setDescription(domain.getDescription());
        e.setCreatedAt(domain.getCreatedAt());
        e.setCreatedBy(domain.getCreatedBy());
        return e;
    }

    public PipelineStepEntity toStepEntity(PipelineStep domain, Long versionId) {
        var e = new PipelineStepEntity();
        e.setVersionId(versionId);
        e.setJobId(domain.getJobId());
        e.setStepOrder(domain.getStepOrder());
        e.setCreatedAt(domain.getCreatedAt());
        e.setCreatedBy(domain.getCreatedBy());
        return e;
    }
}
