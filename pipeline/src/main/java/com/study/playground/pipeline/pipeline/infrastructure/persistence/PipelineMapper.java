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
                , entity.getRgtrId()
        );
        if ("Y".equals(entity.getFailContinueYn())) {
            p.update(p.getName(), p.getDescription(), true, p.getMdfrId());
        }
        return p;
    }

    public PipelineEntity toEntity(Pipeline domain) {
        var e = new PipelineEntity();
        e.setPipelineId(domain.getPipelineId());
        e.setProjectId(domain.getProjectId());
        e.setName(domain.getName());
        e.setDescription(domain.getDescription());
        e.setFailContinueYn(domain.isFailContinue() ? "Y" : "N");
        e.setInOutSe(domain.getInOutSe());
        e.setDelYn(domain.isDeleted() ? "Y" : "N");
        e.setRegDt(domain.getRegDt());
        e.setRgtrId(domain.getRgtrId());
        e.setMdfcnDt(domain.getMdfcnDt());
        e.setMdfrId(domain.getMdfrId());
        return e;
    }

    public PipelineVersion toVersionDomain(PipelineVersionEntity entity, List<PipelineStepEntity> stepEntities) {
        var v = PipelineVersion.create(
                entity.getPipelineId(), entity.getVersion()
                , entity.getDescription(), entity.getRgtrId()
        );
        v.setVersionId(entity.getVersionId());
        for (var se : stepEntities) {
            v.addStep(se.getJobId(), se.getSeq(), se.getRgtrId());
        }
        return v;
    }

    public PipelineVersionEntity toVersionEntity(PipelineVersion domain) {
        var e = new PipelineVersionEntity();
        e.setVersionId(domain.getVersionId());
        e.setPipelineId(domain.getPipelineId());
        e.setVersion(domain.getVersion());
        e.setDescription(domain.getDescription());
        e.setRegDt(domain.getRegDt());
        e.setRgtrId(domain.getRgtrId());
        return e;
    }

    public PipelineStepEntity toStepEntity(PipelineStep domain, Long versionId) {
        var e = new PipelineStepEntity();
        e.setVersionId(versionId);
        e.setJobId(domain.getJobId());
        e.setSeq(domain.getSeq());
        e.setRegDt(domain.getRegDt());
        e.setRgtrId(domain.getRgtrId());
        return e;
    }
}
