package com.study.playground.pipeline.pipeline.domain.port.in;

import com.study.playground.pipeline.pipeline.domain.model.Pipeline;

import java.util.List;

public interface CreatePipelineUseCase {

    /**
     * 파이프라인을 생성하고 첫 번째 버전(v1)과 스텝을 등록한다.
     *
     * @param projectId   프로젝트 ID
     * @param name        파이프라인 이름
     * @param description 설명
     * @param jobIds      스텝 순서대로의 Job ID 목록
     * @param createdBy   등록자
     * @return 생성된 파이프라인
     */
    Pipeline create(String projectId, String name, String description, List<String> jobIds, String createdBy);
}
