package com.study.playground.pipeline.dag.domain;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 파이프라인 정의 — Job 구성의 설계도.
 *
 * <p>실행(PipelineExecution)과 분리된 이유는 동일한 파이프라인 정의를
 * 여러 번 실행할 수 있어야 하기 때문이다. 정의는 "무엇을 어떤 순서로 실행할지"를,
 * 실행은 "언제 실행했고 결과가 무엇인지"를 담당한다.</p>
 */
@Getter
@Setter
public class PipelineDefinition {

    private Long id;

    /** 파이프라인 이름. 사용자가 구분하기 위한 식별자. */
    private String name;

    /** 파이프라인 설명. 목적이나 구성 의도를 기록한다. */
    private String description;

    /** 정의 상태. ACTIVE면 실행 가능, INACTIVE면 실행 불가. */
    private String status;

    private LocalDateTime createdAt;

    /** Job 실패 시 적용할 정책. 기본값은 STOP_ALL. */
    private FailurePolicy failurePolicy;

    /** 이 정의에 포함된 Job 목록. executionOrder 오름차순으로 정렬된다. */
    private List<PipelineJob> jobs;

    /** 매핑된 모든 Job의 파라미터 스키마를 합산하여 반환한다. */
    public List<ParameterSchema> collectParameterSchemas() {
        if (jobs == null || jobs.isEmpty()) return List.of();
        return jobs.stream()
                .flatMap(j -> j.parameterSchemas().stream())
                .toList();
    }
}
