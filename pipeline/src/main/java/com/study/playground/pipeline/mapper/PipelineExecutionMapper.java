package com.study.playground.pipeline.mapper;

import com.study.playground.pipeline.domain.PipelineExecution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 파이프라인 실행 레코드에 대한 MyBatis Mapper 인터페이스.
 *
 * <p>SQL은 XML Mapper 파일에 정의되어 있다. 복잡한 resultMap(스텝 컬렉션 조인)을
 * 어노테이션보다 XML에서 관리하는 것이 가독성과 유지보수에 유리하기 때문이다.</p>
 */
@Mapper
public interface PipelineExecutionMapper {

    /**
     * 새 파이프라인 실행 레코드를 삽입한다.
     * ID는 호출자가 미리 생성한 UUID를 사용한다(DB Auto Increment 미사용).
     *
     * @param execution 삽입할 실행 도메인 객체
     */
    void insert(PipelineExecution execution);

    /**
     * UUID로 특정 실행을 조회한다. 스텝 컬렉션도 함께 로드된다(MyBatis collection 매핑).
     *
     * @param id 조회할 실행 UUID
     * @return 실행 객체, 없으면 null
     */
    PipelineExecution findById(@Param("id") UUID id);

    /**
     * 티켓의 가장 최근 파이프라인 실행을 조회한다.
     *
     * <p>재실행이 허용되므로 하나의 티켓에 여러 실행이 존재할 수 있다.
     * 현재 상태를 확인할 때는 가장 최근 실행이 기준이 된다.</p>
     *
     * @param ticketId 대상 티켓 ID
     * @return 가장 최근 실행 객체, 없으면 null
     */
    PipelineExecution findLatestByTicketId(@Param("ticketId") Long ticketId);

    /**
     * 티켓에 속한 모든 파이프라인 실행 이력을 조회한다. createdAt 내림차순으로 정렬된다.
     *
     * @param ticketId 대상 티켓 ID
     * @return 실행 이력 목록 (없으면 빈 리스트)
     */
    List<PipelineExecution> findByTicketId(@Param("ticketId") Long ticketId);

    /**
     * 특정 파이프라인 정의에 속한 모든 실행 이력을 조회한다. createdAt 내림차순.
     *
     * @param pipelineDefinitionId 대상 정의 ID
     * @return 실행 이력 목록 (없으면 빈 리스트)
     */
    List<PipelineExecution> findByPipelineDefinitionId(@Param("pipelineDefinitionId") Long pipelineDefinitionId);

    /**
     * 파이프라인 실행의 상태를 갱신한다.
     *
     * <p>{@code completedAt}과 {@code errorMessage}를 함께 갱신하는 이유:
     * 상태 전이와 종료 시각, 에러 메시지는 항상 동시에 결정되므로 단일 쿼리로 처리한다.</p>
     *
     * @param id           갱신할 실행 UUID
     * @param status       새로운 상태 이름 (enum.name())
     * @param completedAt  종료 시각 (진행 중이면 null)
     * @param errorMessage 실패 원인 메시지 (성공이면 null)
     */
    void updateStatus(
            @Param("id") UUID id,
            @Param("status") String status,
            @Param("completedAt") LocalDateTime completedAt,
            @Param("errorMessage") String errorMessage);
}
