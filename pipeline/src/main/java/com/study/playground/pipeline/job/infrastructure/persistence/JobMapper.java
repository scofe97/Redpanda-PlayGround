package com.study.playground.pipeline.job.infrastructure.persistence;

import com.study.playground.pipeline.job.domain.model.Job;
import com.study.playground.pipeline.job.domain.model.JobCategory;
import com.study.playground.pipeline.job.domain.model.JobType;
import org.springframework.stereotype.Component;

@Component
public class JobMapper {

    public Job toDomain(JobEntity entity) {
        var job = Job.create(
                entity.getJobId(), entity.getProjectId(), entity.getPresetId()
                , JobCategory.valueOf(entity.getCategoryCode())
                , JobType.valueOf(entity.getTypeCode())
                , entity.getRgtrId()
        );
        job.setTags(entity.getTags());
        return job;
    }

    public JobEntity toEntity(Job domain) {
        var entity = new JobEntity();
        entity.setJobId(domain.getJobId());
        entity.setProjectId(domain.getProjectId());
        entity.setPresetId(domain.getPresetId());
        entity.setCategoryCode(domain.getCategory().name());
        entity.setTypeCode(domain.getType().name());
        entity.setLockYn(domain.isLocked() ? "Y" : "N");
        entity.setTags(domain.getTags());
        entity.setLinkJobId(domain.getLinkJobId());
        entity.setDelYn(domain.isDeleted() ? "Y" : "N");
        entity.setRegDt(domain.getRegDt());
        entity.setRgtrId(domain.getRgtrId());
        entity.setMdfcnDt(domain.getMdfcnDt());
        entity.setMdfrId(domain.getMdfrId());
        return entity;
    }
}
