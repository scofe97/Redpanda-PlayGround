package com.study.playground.pipeline.domain;

/**
 * 파이프라인 Job의 종류.
 *
 * <p>{@link PipelineStepType}과 별도로 존재하는 이유: StepType은 실행 엔진 내부의
 * 워커 디스패치에 사용되고, JobType은 사용자가 파이프라인을 구성할 때 선택하는
 * 고수준 작업 유형이다. JobType → StepType 매핑은 JobExecutorRegistry가 담당한다.</p>
 */
public enum PipelineJobType {

    /** 소스 코드를 빌드한다. GIT_CLONE + BUILD 스텝으로 확장된다. */
    BUILD,

    /** 빌드 산출물을 대상 환경에 배포한다. */
    DEPLOY,

    /** 외부 저장소에서 빌드 산출물을 별도로 임포트한다. */
    IMPORT,

    /** Nexus 등 아티팩트 저장소에서 산출물을 다운로드한다. */
    ARTIFACT_DOWNLOAD,

    /** 컨테이너 레지스트리에서 이미지를 Pull한다. */
    IMAGE_PULL;

    /** Jenkins 폴더명을 반환한다. BUILD → "build", ARTIFACT_DOWNLOAD → "artifact-download". */
    public String toFolderName() {
        return name().toLowerCase().replace("_", "-");
    }
}
