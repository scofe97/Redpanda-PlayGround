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
                , entity.getCreatedBy()
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
        entity.setLocked(domain.isLocked());
        entity.setTags(domain.getTags());
        entity.setLinkJobId(domain.getLinkJobId());
        entity.setDeleted(domain.isDeleted());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setCreatedBy(domain.getCreatedBy());
        entity.setUpdatedAt(domain.getUpdatedAt());
        entity.setUpdatedBy(domain.getUpdatedBy());
        return entity;
    }
}
