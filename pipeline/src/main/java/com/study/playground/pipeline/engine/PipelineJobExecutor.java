package com.study.playground.pipeline.engine;

import com.study.playground.pipeline.domain.PipelineExecution;
import com.study.playground.pipeline.domain.PipelineJobExecution;

/**
 * 파이프라인 개별 Job의 실행 계약을 정의하는 인터페이스.
 *
 * <p>각 구현체는 하나의 Job 타입(GIT_CLONE, BUILD, ARTIFACT_DOWNLOAD 등)에 대응한다.
 * {@link PipelineEngine}이 Job 타입을 키로 구현체를 조회하여 디스패치하므로,
 * 새로운 Job 타입 추가 시 이 인터페이스를 구현하고 엔진의 Map에 등록하면 된다.
 *
 * <p>SAGA 보상을 위해 {@link #compensate} 메서드를 제공한다. 읽기 전용이거나
 * 멱등한 Job은 기본 no-op 구현을 그대로 사용하고, 부수효과를 되돌려야 하는 Job만
 * 오버라이드한다.
 */
public interface PipelineJobExecutor {

    /**
     * Job을 실행한다.
     *
     * <p>엔진은 항상 execution 컨텍스트와 함께 이 메서드를 호출한다.
     * execution이 불필요한 동기 Job(Nexus, Registry)은 파라미터를 무시하면 된다.
     *
     * @param execution    파이프라인 실행 전체 컨텍스트 (실행 ID, 메타데이터 등)
     * @param jobExecution 실행할 Job 정보
     * @throws Exception 실행 중 발생한 모든 예외 (엔진이 catch하여 SAGA 보상을 트리거한다)
     */
    void execute(PipelineExecution execution, PipelineJobExecution jobExecution) throws Exception;

    /**
     * 이전에 성공한 Job의 효과를 보상(되돌리기)한다.
     * SAGA 롤백 시 역순으로 호출된다.
     * 기본 구현은 no-op (읽기 전용이거나 멱등한 Job에 안전).
     *
     * @param execution    파이프라인 실행 컨텍스트
     * @param jobExecution 보상할 Job 정보
     * @throws Exception 보상 중 발생한 예외 (엔진이 COMPENSATION_FAILED로 기록한다)
     */
    default void compensate(PipelineExecution execution, PipelineJobExecution jobExecution) throws Exception {
        // 기본값은 no-op — 부수효과를 되돌려야 하는 Job에서 오버라이드
    }
}
