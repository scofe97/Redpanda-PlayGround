package com.study.playground.kafka.outbox.domain;

import java.time.LocalDateTime;

/**
 * Outbox 이벤트의 재시도 정책을 담당하는 순수 도메인 서비스.
 *
 * <p>Spring 어노테이션 없이 순수 Java로 작성되었으며,
 * {@code OutboxAutoConfiguration}에서 {@code @Bean}으로 등록된다.
 * (executor 모듈의 {@code DispatchService}와 동일한 패턴)
 *
 * <h3>재시도 전략: 지수 백오프</h3>
 * <p>재시도 간격은 {@code 2^retryCount} 초로 증가한다.
 * 예: 1초 → 2초 → 4초 → 8초 → 16초.
 * 최대 재시도 횟수 초과 시 이벤트는 DEAD 상태로 전환된다.
 */
public class OutboxRetryPolicy {

    /**
     * 최대 재시도 횟수를 초과했는지 판정한다.
     *
     * @param retryCount 현재까지 시도한 횟수 (null이면 0으로 취급)
     * @param maxRetries 허용 최대 재시도 횟수 (OutboxProperties에서 주입)
     * @return 초과 여부. true이면 DEAD로 전환해야 한다.
     */
    public boolean isMaxRetriesExceeded(Integer retryCount, int maxRetries) {
        int count = retryCount != null ? retryCount : 0;
        return count >= maxRetries;
    }

    /**
     * 지수 백오프 기반으로 다음 재시도 시각을 계산한다.
     *
     * <p>계산식: {@code now + 2^retryCount} 초.
     * retryCount=0이면 1초 후, retryCount=1이면 2초 후, ... retryCount=4이면 16초 후.
     *
     * @param retryCount 현재까지 시도한 횟수 (null이면 0으로 취급)
     * @return 다음 재시도 시각
     */
    public LocalDateTime calculateNextRetryAt(Integer retryCount) {
        int count = retryCount != null ? retryCount : 0;
        long delaySeconds = (long) Math.pow(2, count);
        return LocalDateTime.now().plusSeconds(delaySeconds);
    }
}
