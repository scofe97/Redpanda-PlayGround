package com.study.playground.pipeline.mapper;

import com.study.playground.pipeline.domain.PipelineJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Job 독립 CRUD용 MyBatis Mapper.
 *
 * <p>Pipeline 정의 내 Job 일괄 처리를 담당하는 {@link PipelineJobMapper}와 달리,
 * 이 Mapper는 Job 단건 CRUD와 Job 단독 실행을 위한 조회를 제공한다.</p>
 */
@Mapper
public interface JobMapper {

    /** Job을 단건 삽입한다. useGeneratedKeys로 id가 채워진다. */
    void insert(PipelineJob job);

    /**
     * Job을 단건 조회한다. middleware_preset과 LEFT JOIN하여 presetName을 함께 반환한다.
     * 존재하지 않으면 null을 반환한다.
     */
    PipelineJob findById(@Param("id") Long id);

    /** 전체 Job을 created_at 내림차순으로 조회한다. */
    List<PipelineJob> findAll();

    /** 주어진 ID 목록에 해당하는 Job을 조회한다. */
    List<PipelineJob> findByIds(@Param("ids") List<Long> ids);

    /** Job의 jobName, jobType, presetId, configJson을 수정한다. */
    void update(PipelineJob job);

    /** Job과 해당 Job의 의존성(pipeline_job_dependency)을 함께 삭제한다. */
    void delete(@Param("id") Long id);

    /** Job의 Jenkins 연동 상태를 변경한다. */
    void updateJenkinsStatus(@Param("id") Long id, @Param("jenkinsStatus") String jenkinsStatus);

    /** 지정한 Jenkins 상태의 Job 목록을 조회한다. */
    List<PipelineJob> findByJenkinsStatus(@Param("jenkinsStatus") String jenkinsStatus);
}
