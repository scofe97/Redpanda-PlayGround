package com.study.playground.pipeline.dto;

import com.study.playground.pipeline.domain.PipelineExecution;
import com.study.playground.pipeline.domain.PipelineJobExecution;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 파이프라인 실행 정보를 클라이언트에 전달하는 응답 DTO.
 *
 * <p>도메인 객체({@link PipelineExecution})를 직접 노출하지 않고 DTO로 변환하는 이유:
 * 내부 도메인 구조 변경이 API 계약에 영향을 주지 않도록 격리하기 위함이다.</p>
 *
 * <p>{@link #status}는 enum 이름 문자열로 직렬화한다.
 * 클라이언트가 enum 값 추가에 유연하게 대응할 수 있도록 문자열 타입을 선택했다.</p>
 *
 * <p>{@link #trackingUrl}은 클라이언트가 SSE 스트림에 구독할 수 있는 URL을 제공한다.
 * 클라이언트가 URL을 직접 조합하지 않아도 되므로 결합도를 낮춘다.</p>
 */
public record PipelineExecutionResponse(
        UUID executionId,
        Long ticketId,
        /** enum 이름 문자열. 예: "PENDING", "RUNNING", "SUCCESS", "FAILED" */
        String status,
        List<PipelineJobExecutionResponse> jobExecutions,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        String errorMessage,
        /** 실시간 진행 상황을 구독할 수 있는 SSE 엔드포인트 URL. */
        String trackingUrl
) {
    /**
     * 조회 응답용 팩토리 메서드. Job 실행 목록을 포함한 전체 상태를 반환한다.
     *
     * <p>{@code jobExecutions}가 null인 경우 빈 리스트로 처리하는 이유:
     * Mapper가 Job 실행이 없는 실행을 조회할 때 null을 반환할 수 있어, 클라이언트 NPE를 방지하기 위함이다.</p>
     *
     * @param execution     조회된 파이프라인 실행 도메인 객체
     * @param jobExecutions 실행에 속한 Job 실행 목록 (null 허용)
     */
    public static PipelineExecutionResponse from(
            PipelineExecution execution,
            List<PipelineJobExecution> jobExecutions) {
        return new PipelineExecutionResponse(
                execution.getId()
                , execution.getTicketId()
                , execution.getStatus().name()
                , jobExecutions != null ? jobExecutions.stream().map(PipelineJobExecutionResponse::from).toList() : List.of()
                , execution.getStartedAt()
                , execution.getCompletedAt()
                , execution.getErrorMessage()
                , buildTrackingUrl(execution)
        );
    }

    /**
     * 실행 접수(202 Accepted) 응답용 팩토리 메서드.
     *
     * <p>파이프라인 시작 직후에는 Job 실행 정보가 아직 의미 없으므로 포함하지 않는다.
     * 클라이언트는 {@link #trackingUrl}로 SSE를 구독하여 이후 진행 상황을 실시간으로 수신한다.</p>
     *
     * @param execution 방금 생성된 파이프라인 실행 도메인 객체
     */
    public static PipelineExecutionResponse accepted(PipelineExecution execution) {
        return new PipelineExecutionResponse(
                execution.getId()
                , execution.getTicketId()
                , execution.getStatus().name()
                , null
                , null
                , null
                , null
                , buildTrackingUrl(execution)
        );
    }

    /**
     * 실행 추적 URL을 생성한다. 티켓 기반 실행은 티켓 SSE 엔드포인트를,
     * DAG 모드(ticketId가 null)는 실행 ID 기반 엔드포인트를 사용한다.
     */
    private static String buildTrackingUrl(PipelineExecution execution) {
        if (execution.getTicketId() != null) {
            return "/api/tickets/" + execution.getTicketId() + "/pipeline/events";
        }
        return "/api/pipelines/executions/" + execution.getId() + "/events";
    }
}
