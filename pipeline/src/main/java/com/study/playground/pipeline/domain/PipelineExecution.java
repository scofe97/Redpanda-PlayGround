package com.study.playground.pipeline.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 파이프라인 실행 집계 루트(Aggregate Root).
 *
 * <p>하나의 티켓({@link #ticketId})에 대한 단일 실행 단위를 표현한다.
 * 재실행 시에는 새 {@code PipelineExecution}이 생성되므로, 이력 관리가 자연스럽게 된다.</p>
 *
 * <p>{@link #jobExecutions}는 실행 시 함께 로드되는 연관 컬렉션이다.
 * MyBatis resultMap의 collection 매핑으로 채워지며, 독립적으로 조회할 수도 있다.</p>
 */
@Getter
@Setter
public class PipelineExecution {

    /** 파이프라인 실행을 고유하게 식별하는 UUID. 생성 시점에 애플리케이션에서 발급한다. */
    private UUID id;

    /** 이 파이프라인 실행이 속한 티켓 ID. 티켓 단위로 이력을 조회할 수 있다. */
    private Long ticketId;

    /** 전체 파이프라인의 현재 진행 상태. */
    private PipelineStatus status;

    /** 첫 번째 Job 실행이 시작된 시각. PENDING에서 RUNNING으로 전이될 때 기록된다. */
    private LocalDateTime startedAt;

    /** 파이프라인이 SUCCESS 또는 FAILED로 종료된 시각. */
    private LocalDateTime completedAt;

    /**
     * 파이프라인 실패 원인 메시지.
     * SUCCESS인 경우 null이다. 실패한 Job 실행의 에러 메시지를 상위로 전파한다.
     */
    private String errorMessage;

    /**
     * W3C traceparent 문자열. 파이프라인 생성 시점의 OTel trace context를 저장한다.
     * webhook 재개, 타임아웃 등 비동기 경로에서 원래 trace에 연결하기 위해 사용된다.
     * 형식: 00-{traceId(32hex)}-{spanId(16hex)}-{flags(2hex)}
     */
    private String traceParent;

    /**
     * 이 실행이 기반한 파이프라인 정의 ID. null이면 기존 티켓 기반 실행이다.
     * DAG 실행 여부를 판단하는 분기 조건으로 사용된다.
     */
    private Long pipelineDefinitionId;

    /** 레코드 최초 생성 시각. DB에서 DEFAULT NOW()로 채워진다. */
    private LocalDateTime createdAt;

    /** 실행 시 주입된 파라미터 JSON. key-value 맵을 JSON으로 직렬화하여 저장한다. */
    private String parametersJson;

    /**
     * 이 실행에 속한 Job 실행 목록. jobOrder 오름차순으로 정렬되어 있다.
     * MyBatis collection 매핑을 통해 조인 쿼리 결과로 채워진다.
     */
    private List<PipelineJobExecution> jobExecutions;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** parametersJson을 파싱하여 파라미터 맵으로 반환한다. */
    public Map<String, String> parameters() {
        if (parametersJson == null || parametersJson.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(parametersJson, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
