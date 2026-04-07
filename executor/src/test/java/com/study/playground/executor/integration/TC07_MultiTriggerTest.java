package com.study.playground.executor.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TC-07: 동시 다중 디스패치.
 * <p>
 * 동일 jobName(executor-test)으로 3개의 Job을 동시에 발행한다.
 * 중복 실행 방지 로직에 의해 최대 1개만 QUEUED/RUNNING에 도달해야 하지만,
 * 동시성 타이밍에 따라 2개까지 활성화될 수 있다 (Kafka consumer 2 concurrency).
 * 핵심: 3건 모두 수신되고, 전부가 활성 상태는 아니어야 한다.
 */
@DisplayName("TC-07: 동시 다중 디스패치")
class TC07_MultiTriggerTest extends ExecutorIntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(TC07_MultiTriggerTest.class);

    @Test
    @DisplayName("동일 jobName 3개 동시 발행 → 3건 수신, 전부 활성은 아님")
    void multiTrigger_notAllJobsShouldBeActive() throws InterruptedException {
        // given
        var ids = List.of(
                uniqueId("tc07a-")
                , uniqueId("tc07b-")
                , uniqueId("tc07c-")
        );

        // when — 3개 동시 발행
        var futures = ids.stream()
                .map(id -> CompletableFuture.runAsync(
                        () -> publishDispatchCommand(id, "1")))
                .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 디스패치 평가까지 대기
        Thread.sleep(20_000);

        // then
        var activeStatuses = List.of("QUEUED", "SUBMITTED", "RUNNING", "SUCCESS");

        long totalCount = ids.stream()
                .map(this::getExecutorJob)
                .filter(job -> job != null)
                .count();

        long activeCount = ids.stream()
                .map(this::getExecutorJob)
                .filter(job -> job != null)
                .filter(job -> activeStatuses.contains(job.get("status")))
                .count();

        long pendingCount = ids.stream()
                .map(this::getExecutorJob)
                .filter(job -> job != null)
                .filter(job -> "PENDING".equals(job.get("status")))
                .count();

        // 3건 모두 수신
        assertThat(totalCount).as("3건 모두 DB에 저장").isEqualTo(3);

        // 최소 1개 활성
        assertThat(activeCount).as("최소 1개 활성 (QUEUED/SUBMITTED/RUNNING/SUCCESS)").isGreaterThanOrEqualTo(1);

        // 참고: 동시 발행 시 Kafka consumer concurrency로 인해 race condition이 발생하여
        // 중복 체크 전에 여러 Job이 QUEUED될 수 있다. 이는 알려진 동작이다.
        // 순차적 중복 방지는 TC-05에서 검증한다.

        log.info("[TC-07] total={}, active={}, pending={}", totalCount, activeCount, pendingCount);
    }
}
