package com.study.playground.pipeline.dag.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;

import com.study.playground.pipeline.domain.PipelineJobType;
import java.time.LocalDateTime;
import java.util.List;

/**
 * DAG의 노드 — 파이프라인 내 하나의 실행 단위를 정의한다.
 *
 * <p>PipelineStep(실행 추적)과 분리된 이유: Job은 "무엇을 실행할지"를 정의하고,
 * Step은 "실제 실행 결과"를 기록한다. 같은 Job 정의로 여러 번 실행하면
 * 각 실행마다 새 Step이 생성된다.</p>
 *
 * <p>{@link #dependsOnJobIds}는 DAG 엣지를 표현한다. 이 Job이 실행되려면
 * 목록에 있는 모든 Job이 먼저 성공해야 한다. 빈 목록이면 루트 Job이다.</p>
 */
@Getter
@Setter
public class PipelineJob {

    private Long id;

    /** 이 Job이 속한 파이프라인 정의 ID. */
    private Long pipelineDefinitionId;

    /** Job 이름. 화면 표시 및 식별용. */
    private String jobName;

    /** Job 타입. 어떤 실행기(executor)를 호출할지 결정한다. */
    private PipelineJobType jobType;

    /** 실행 순서 힌트. 같은 라운드 내에서의 우선순위. 위상 정렬 결과가 우선한다. */
    private Integer executionOrder;

    /** 연결된 미들웨어 프리셋 ID. null이면 프리셋 없이 직접 설정을 사용한다. */
    private Long presetId;

    /** DB 매핑 편의용 프리셋 이름. LEFT JOIN으로 채워지며 DB 컬럼은 아니다. */
    private String presetName;

    /** Job 설정 JSON. 타입별로 다른 스키마를 가진다. */
    private String configJson;

    /** 사용자 정의 Jenkinsfile 스크립트. DB = Source of Truth. */
    private String jenkinsScript;

    /** Jenkins 연동 상태. PENDING, ACTIVE, FAILED, DELETING. */
    private String jenkinsStatus;

    private LocalDateTime createdAt;

    /** 파라미터 스키마 JSON. 이 Job에 주입할 파라미터의 이름·타입·기본값·필수 여부를 정의한다. */
    private String parameterSchemaJson;

    /**
     * 이 Job이 의존하는 선행 Job ID 목록 (DAG 엣지).
     * pipeline_job_dependency 테이블에서 로드된다.
     */
    private List<Long> dependsOnJobIds;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** parameterSchemaJson을 파싱하여 ParameterSchema 리스트로 반환한다. */
    public List<ParameterSchema> parameterSchemas() {
        if (parameterSchemaJson == null || parameterSchemaJson.isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(parameterSchemaJson, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
