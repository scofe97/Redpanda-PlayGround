package com.study.playground.pipeline.domain;

/**
 * 파이프라인을 구성하는 스텝의 종류.
 *
 * <p>각 타입은 실행 시 호출되는 워커 로직과 1:1로 대응한다.
 * 스텝 순서는 {@link PipelineStep#stepOrder}로 결정되며,
 * 타입은 어떤 작업을 수행할지만 나타낸다.</p>
 */
public enum PipelineStepType {

    /** 소스 코드 저장소를 클론하는 스텝. 빌드의 선행 조건이다. */
    GIT_CLONE,

    /** 소스를 컴파일하고 테스트를 실행하는 스텝. */
    BUILD,

    /**
     * 외부 저장소(Nexus 등)에서 빌드 산출물을 내려받는 스텝.
     * BUILD 결과를 직접 사용하지 않고 별도 아티팩트 저장소를 거칠 때 필요하다.
     */
    ARTIFACT_DOWNLOAD,

    /** 컨테이너 레지스트리에서 이미지를 Pull하는 스텝. 배포 전 선행 조건이다. */
    IMAGE_PULL,

    /**
     * 실제 서버나 클러스터에 애플리케이션을 배포하는 스텝.
     * 외부 시스템(웹훅)으로 완료를 통보받아야 하므로 {@code WAITING_WEBHOOK} 상태를 가질 수 있다.
     */
    DEPLOY
}
