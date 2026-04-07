package com.study.playground.executor.dispatch.infrastructure.persistence;

import com.study.playground.executor.dispatch.domain.model.ExecutionJob;
import com.study.playground.executor.dispatch.domain.model.ExecutionJobStatus;
import org.springframework.stereotype.Component;

/**
 * Domain Model ↔ JPA Entity 매핑.
 */
@Component
public class ExecutionJobMapper {

    public ExecutionJob toDomain(ExecutionJobEntity entity) {
        return ExecutionJob.reconstitute(
                entity.getJobExcnId()
                , entity.getPipelineExcnId()
                , entity.getJobId()
                , entity.getBuildNo()
                , ExecutionJobStatus.valueOf(entity.getStatus())
                , entity.getPriority()
                , entity.getPriorityDt()
                , entity.getRetryCnt()
                , entity.getBgngDt()
                , entity.getEndDt()
                , entity.getLogFileYn()
                , entity.getRegDt()
                , entity.getRgtrId()
                , entity.getMdfcnDt()
                , entity.getMdfrId()
                , entity.getVersion()
        );
    }

    public ExecutionJobEntity toEntity(ExecutionJob domain) {
        var entity = new ExecutionJobEntity();
        entity.setJobExcnId(domain.getJobExcnId());
        entity.setPipelineExcnId(domain.getPipelineExcnId());
        entity.setJobId(domain.getJobId());
        entity.setBuildNo(domain.getBuildNo());
        entity.setStatus(domain.getStatus().name());
        entity.setPriority(domain.getPriority());
        entity.setPriorityDt(domain.getPriorityDt());
        entity.setRetryCnt(domain.getRetryCnt());
        entity.setBgngDt(domain.getBgngDt());
        entity.setEndDt(domain.getEndDt());
        entity.setLogFileYn(domain.getLogFileYn());
        entity.setRegDt(domain.getRegDt());
        entity.setRgtrId(domain.getRgtrId());
        entity.setMdfcnDt(domain.getMdfcnDt());
        entity.setMdfrId(domain.getMdfrId());
        entity.setVersion(domain.getVersion());
        return entity;
    }
}
