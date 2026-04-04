package com.study.playground.pipeline.job.domain.port.in;

/**
 * Jenkins 잡 생성 유스케이스 (in-port).
 *
 * 이미 DB에 등록된 Job을 기반으로 Jenkins 서버에 폴더 구조와 파이프라인 잡을 생성한다.
 * Job 등록은 수행하지 않는다 — DB 등록은 {@link CreateJobUseCase}가 담당한다.
 *
 * <pre>
 * ┌──────────────────────┐           ┌──────────────────────────────┐
 * │ CreateJenkinsJobUseCase│ ──REST──→ │ Jenkins: /{projId}/{presetId}/{jobId} │
 * └──────────────────────┘           └──────────────────────────────┘
 * </pre>
 *
 * 분리 이유: Jenkins 잡 생성 실패 시 독립적으로 재시도할 수 있어야 한다.
 */
public interface CreateJenkinsJobUseCase {

    /**
     * Jenkins에 {projectId}/{presetId}/{jobId} 폴더 구조와 파이프라인 잡을 생성한다.
     * 폴더가 이미 존재하면 건너뛴다.
     *
     * @param jobId    DB에 등록된 Job ID
     * @param presetId 목적(Preset) ID — Jenkins 인스턴스 결정 + 폴더명
     */
    void create(String jobId, String presetId);
}
