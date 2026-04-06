package com.study.playground.kafka.topic;

/**
 * Kafka 토픽 이름 상수.
 * 모든 Producer/Consumer에서 이 상수를 사용하여 하드코딩을 제거한다.
 *
 * 네이밍 규칙: {app}.{domain}.{type}.{name}
 * - commands: 명령 ("이것을 해라") — 수신자가 액션을 수행해야 함
 * - events:   통보 ("이것이 일어났다") — 발생 사실 알림
 * - queries:  조회 ("이것을 알려달라") — 응답 기대
 * - dlq:      처리 불가 메시지 격리
 */
public final class Topics {

    private Topics() {}

    // === global dlq ===
    public static final String DLQ = "playground.dlq";

    // === executor commands ===
    public static final String EXECUTOR_CMD_JOB_DISPATCH = "playground.executor.commands.job-dispatch";
    public static final String EXECUTOR_CMD_JOB_EXECUTE = "playground.executor.commands.job-execute";

    // === executor events ===
    public static final String EXECUTOR_EVT_JOB_STARTED = "playground.executor.events.job-started";
    public static final String EXECUTOR_EVT_JOB_COMPLETED = "playground.executor.events.job-completed";

    // === executor notify (Avro → operator-stub) ===
    public static final String EXECUTOR_NOTIFY_JOB_STARTED = "playground.executor.notify.job-started";
    public static final String EXECUTOR_NOTIFY_JOB_COMPLETED = "playground.executor.notify.job-completed";

    // === executor dlq ===
    public static final String EXECUTOR_DLQ_JOB = "playground.executor.dlq.job";

    // === ticket events ===
    public static final String TICKET_EVENTS = "playground.ticket.events";

    // === audit events ===
    public static final String AUDIT_EVENTS = "playground.audit.events";

    // === pipeline events ===
    public static final String PIPELINE_EVT_COMPLETED = "playground.pipeline.events.completed";
}
