package com.study.playground.pipeline.pipeline.domain.port.in;

import java.util.List;

public interface UpdatePipelineUseCase {

    /**
     * 파이프라인 메타를 수정하고 새 버전을 생성한다.
     *
     * @param pipelineId     파이프라인 ID
     * @param name           변경할 이름
     * @param description    변경할 설명
     * @param failContinue   실패 시 계속 여부
     * @param jobIds         새 버전의 스텝 Job ID 목록 (순서대로)
     * @param versionDesc    버전 메모
     * @param updatedBy      수정자
     */
    void update(String pipelineId, String name, String description, boolean failContinue
            , List<String> jobIds, String versionDesc, String updatedBy);
}
