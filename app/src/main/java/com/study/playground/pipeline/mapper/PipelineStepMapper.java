package com.study.playground.pipeline.mapper;

import com.study.playground.pipeline.domain.PipelineStep;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 파이프라인 스텝 레코드에 대한 MyBatis Mapper 인터페이스.
 *
 * <p>스텝은 항상 실행({@code executionId})에 종속되므로, 대부분의 조회는
 * {@code executionId}를 조건으로 사용한다.</p>
 */
@Mapper
public interface PipelineStepMapper {

    /**
     * 여러 스텝을 한 번의 쿼리로 일괄 삽입한다.
     *
     * <p>파이프라인 시작 시 모든 스텝을 PENDING 상태로 미리 생성한다.
     * 배치 INSERT를 사용하는 이유: 스텝 수(보통 5개 내외)만큼 개별 INSERT를 날리면
     * 불필요한 왕복이 발생하기 때문이다.</p>
     *
     * @param executionId 스텝들이 속한 실행 UUID
     * @param steps       삽입할 스텝 목록
     */
    void insertBatch(
            @Param("executionId") UUID executionId,
            @Param("steps") List<PipelineStep> steps);

    /**
     * 실행에 속한 모든 스텝을 stepOrder 오름차순으로 조회한다.
     *
     * @param executionId 대상 실행 UUID
     * @return 스텝 목록 (없으면 빈 리스트)
     */
    List<PipelineStep> findByExecutionId(@Param("executionId") UUID executionId);

    /**
     * 스텝의 상태, 로그, 완료 시각을 갱신한다.
     *
     * <p>세 필드를 하나의 메서드로 묶은 이유: 상태 전이 시 로그와 완료 시각은
     * 항상 함께 결정되므로, 개별 갱신보다 일관성 측면에서 안전하다.</p>
     *
     * @param id          갱신할 스텝 ID
     * @param status      새로운 상태 이름 (enum.name())
     * @param log         실행 로그 (없으면 null)
     * @param completedAt 종료 시각 (진행 중이면 null)
     */
    void updateStatus(
            @Param("id") Long id,
            @Param("status") String status,
            @Param("log") String log,
            @Param("completedAt") LocalDateTime completedAt);

    /**
     * 실행 내에서 특정 순서의 스텝을 조회한다.
     *
     * <p>파이프라인 진행 중 "다음 실행할 스텝"을 찾거나, 웹훅 수신 시
     * 대상 스텝을 특정하는 데 사용된다.</p>
     *
     * @param executionId 대상 실행 UUID
     * @param stepOrder   조회할 스텝 순서 번호
     * @return 해당 스텝, 없으면 null
     */
    PipelineStep findByExecutionIdAndStepOrder(
            @Param("executionId") UUID executionId,
            @Param("stepOrder") int stepOrder);

    /**
     * 지정 시간(분) 이상 {@code WAITING_WEBHOOK} 상태로 머문 스텝을 조회한다.
     *
     * <p>웹훅 타임아웃 감지용 스케줄러가 주기적으로 호출한다.
     * 반환된 스텝은 FAILED로 전이되고 파이프라인 보상 로직이 트리거된다.</p>
     *
     * @param minutes 타임아웃 기준 시간(분)
     * @return 타임아웃된 스텝 목록
     */
    List<PipelineStep> findWaitingWebhookStepsOlderThan(@Param("minutes") int minutes);

    /**
     * CAS(Compare-And-Swap) 방식 상태 업데이트: expectedStatus가 현재 상태와 일치할 때만 변경.
     *
     * <p>분산 환경에서 동일 스텝을 여러 소비자가 동시에 처리할 때 중복 상태 전이를 막는다.
     * 반환값이 0이면 다른 소비자가 이미 상태를 변경한 것이므로 현재 처리를 무시해야 한다.</p>
     *
     * @param id             갱신할 스텝 ID
     * @param expectedStatus 전이 조건이 되는 현재 기대 상태
     * @param newStatus      전이할 새 상태
     * @param log            실행 로그 (없으면 null)
     * @param completedAt    종료 시각 (없으면 null)
     * @return 업데이트된 행 수 (0이면 상태가 이미 변경되어 CAS 실패)
     */
    int updateStatusIfCurrent(
            @Param("id") Long id,
            @Param("expectedStatus") String expectedStatus,
            @Param("newStatus") String newStatus,
            @Param("log") String log,
            @Param("completedAt") LocalDateTime completedAt);
}
