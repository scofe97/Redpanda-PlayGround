package com.study.playground.pipeline.dto;

import com.study.playground.pipeline.domain.PipelineJobExecution;

import java.time.LocalDateTime;

/**
 * 단일 파이프라인 Job 실행 정보를 클라이언트에 전달하는 응답 DTO.
 *
 * <p>도메인 {@link PipelineJobExecution}에서 {@code waitingForWebhook}처럼 런타임 전용 필드는
 * 여기서 제외한다. 클라이언트가 알아야 할 정보는 {@code status}(WAITING_WEBHOOK)로 충분하다.</p>
 *
 * <p>{@code jobType}, {@code status}를 enum이 아닌 String으로 노출하는 이유:
 * 서버가 새 enum 값을 추가해도 클라이언트 역직렬화가 깨지지 않도록 하기 위함이다.</p>
 */
public record PipelineJobExecutionResponse(
        Long id,
        /** 실행 순서. 작은 숫자가 먼저 실행된다. */
        Integer jobOrder,
        /** enum 이름 문자열. 예: "BUILD", "DEPLOY", "ARTIFACT_DOWNLOAD" */
        String jobType,
        String jobName,
        /** enum 이름 문자열. 예: "PENDING", "RUNNING", "SUCCESS", "FAILED", "WAITING_WEBHOOK" */
        String status,
        String log,
        LocalDateTime startedAt,
        LocalDateTime completedAt
) {
    /**
     * 도메인 객체를 DTO로 변환하는 팩토리 메서드.
     *
     * <p>스트림 처리 등 함수형 컨텍스트에서 메서드 레퍼런스({@code PipelineJobExecutionResponse::from})로
     * 사용할 수 있도록 정적 팩토리로 구성했다.</p>
     *
     * @param je 변환할 도메인 Job 실행 객체
     */
    public static PipelineJobExecutionResponse from(PipelineJobExecution je) {
        return new PipelineJobExecutionResponse(
                je.getId()
                , je.getJobOrder()
                , je.getJobType().name()
                , je.getJobName()
                , je.getStatus().name()
                , je.getLog()
                , je.getStartedAt()
                , je.getCompletedAt()
        );
    }
}
