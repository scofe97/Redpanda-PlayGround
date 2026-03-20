package com.study.playground.pipeline.domain;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 파이프라인 내 단일 Job 실행 단위.
 *
 * <p>{@link #jobOrder}가 작을수록 먼저 실행된다.
 * 각 Job 실행은 독립적인 상태 머신을 갖고, {@link JobExecutionStatus}에 따라 다음 Job 진행 여부가 결정된다.</p>
 *
 * <p>{@link #waitingForWebhook}은 DB에 저장하지 않는 런타임 전용 플래그다.
 * DEPLOY Job처럼 외부 시스템의 완료 신호를 기다려야 할 때 폴링 루프에서 이 값을 확인한다.</p>
 */
@Getter
@Setter
public class PipelineJobExecution {

    /** DB Auto Increment PK. UUID를 쓰지 않는 이유: Job 실행은 실행 내에서만 의미를 가지므로 숫자 PK로 충분하다. */
    private Long id;

    /** 이 Job 실행이 속한 파이프라인 실행 ID. 외래 키 역할을 한다. */
    private UUID executionId;

    /** 실행 순서. 작은 숫자가 먼저 실행되며, 동일한 실행 내에서 유일하다. */
    private Integer jobOrder;

    /** Job의 종류. 실행 시 어떤 워커를 호출할지 결정하는 데 사용된다. */
    private PipelineJobType jobType;

    /** 이 Job 실행이 속한 Job 정의 ID. null이면 기존 티켓 기반 실행에서 생성된 Job 실행이다. */
    private Long jobId;

    /** 화면 표시용 Job 이름. jobType과 달리 자유 형식 문자열이다. */
    private String jobName;

    /** 이 Job 실행의 현재 상태. */
    private JobExecutionStatus status;

    /** Job 실행 중 수집된 로그 전문. 길이 제한은 DB 컬럼 설정에 따른다. */
    private String log;

    /** Job 실행이 시작된 시각. */
    private LocalDateTime startedAt;

    /** Job 실행이 종료(SUCCESS/FAILED/COMPENSATED)된 시각. */
    private LocalDateTime completedAt;

    /**
     * 외부 웹훅 완료 대기 여부를 나타내는 런타임 전용 플래그.
     * {@code transient}로 선언하여 DB에 저장되지 않는다.
     * 폴링 스케줄러가 이 값을 보고 타임아웃 감지 대상에서 제외할 수 있다.
     */
    private transient boolean waitingForWebhook = false;
}
