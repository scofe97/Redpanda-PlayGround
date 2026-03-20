package com.study.playground.kafka.outbox;

/**
 * Outbox 이벤트를 aggregate 타입에 따라 라우팅하는 확장 포인트.
 *
 * <p>기본적으로 OutboxPoller는 모든 이벤트를 Kafka로 발행한다.
 * 특정 aggregate 타입(예: JENKINS)을 Kafka 대신 다른 시스템으로 전달해야 할 때
 * 이 인터페이스를 구현하고 Spring Bean으로 등록한다.</p>
 */
public interface OutboxEventHandler {

    /** 이 핸들러가 처리할 수 있는 aggregate 타입인지 판단한다. */
    boolean supports(String aggregateType);

    /**
     * 이벤트를 처리한다. 성공 시 정상 반환, 실패 시 예외를 던진다.
     *
     * @throws Exception 처리 실패 시. OutboxPoller가 retry/dead 처리한다.
     */
    void handle(OutboxEvent event) throws Exception;

    /**
     * 이벤트가 최대 재시도를 초과하여 DEAD 상태로 전환될 때 호출된다.
     * 보상 로직(예: 상태를 FAILED로 변경)을 구현한다.
     */
    default void onDead(OutboxEvent event) {}
}
