package com.study.playground.executor.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TC-06: 멱등성 (동일 jobExcnId 중복 발행).
 * <p>
 * 같은 jobExcnId로 dispatch command를 2회 발행하면,
 * ReceiveJobService의 existsById 체크에 의해 두 번째는 무시되어야 한다.
 * DB에 1건만 존재해야 한다.
 */
@DisplayName("TC-06: 멱등성 (동일 jobExcnId 중복 발행)")
class TC06_IdempotencyTest extends ExecutorIntegrationTestBase {

    @Test
    @DisplayName("동일 jobExcnId 2회 발행 → DB에 1건만 저장")
    void idempotency_duplicateJobExcnIdShouldBeIgnored() throws InterruptedException {
        // given
        String jobExcnId = uniqueId("tc06-");

        // when — 동일 jobExcnId로 2회 발행
        publishDispatchCommand(jobExcnId, "1");
        Thread.sleep(5_000); // 첫 번째 처리 완료 대기

        publishDispatchCommand(jobExcnId, "1");
        Thread.sleep(3_000); // 두 번째 처리 시도 대기

        // then — DB에 정확히 1건만 존재해야 한다
        assertThat(countJobsInDbById(jobExcnId)).isEqualTo(1);

        // API로도 정상 조회 가능해야 한다
        var job = getExecutorJob(jobExcnId);
        assertThat(job).isNotNull();
        assertThat(job.get("jobExcnId")).isEqualTo(jobExcnId);
    }
}
