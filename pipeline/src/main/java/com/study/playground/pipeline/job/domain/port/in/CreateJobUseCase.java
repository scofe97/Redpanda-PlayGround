package com.study.playground.pipeline.job.domain.port.in;

import com.study.playground.pipeline.job.domain.model.Job;
import com.study.playground.pipeline.job.domain.model.JobCategory;
import com.study.playground.pipeline.job.domain.model.JobType;

/**
 * Job 등록 유스케이스 (in-port).
 *
 * DB(TB_TPS_IJ_001)에 Job 기본정보를 등록한다.
 * Jenkins 연동은 수행하지 않는다 — Jenkins 잡 생성은 {@link CreateJenkinsJobUseCase}가 담당한다.
 *
 * <pre>
 * ┌─────────────────┐         ┌──────────────────────┐
 * │ CreateJobUseCase │ ──DB──→ │ TB_TPS_IJ_001 (Job)  │
 * └─────────────────┘         └──────────────────────┘
 * </pre>
 */
public interface CreateJobUseCase {

    /**
     * Job 기본정보를 DB에 등록한다.
     *
     * @param projectId 소속 프로젝트 ID
     * @param presetId  소속 목적(Preset) ID
     * @param category  작업 범주 (BLD, DPLY, TEST)
     * @param type      작업 타입 (GUIDE, FREE)
     * @param createdBy 등록자 ID
     * @return 생성된 Job 도메인 모델
     */
    Job create(String projectId, String presetId, JobCategory category, JobType type, String createdBy);
}
