package com.study.playground.supporttool.domain;

/**
 * 미들웨어 구현체 타입을 정의한다.
 * 각 구현체는 하나의 ToolCategory에 속한다.
 */
public enum ToolImplementation {
    // CI_CD_TOOL
    JENKINS,
    GITHUB_ACTIONS,

    // CONTAINER_REGISTRY
    HARBOR,
    DOCKER_REGISTRY,
    ECR,

    // LIBRARY
    NEXUS,
    ARTIFACTORY,

    // STORAGE
    MINIO,
    S3,

    // CLUSTER_APPLICATION
    ARGOCD,
    FLUX,

    // VCS
    GITLAB,
    GITHUB,
    BITBUCKET
}
