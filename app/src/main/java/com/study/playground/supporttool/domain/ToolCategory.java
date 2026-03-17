package com.study.playground.supporttool.domain;

/**
 * 미들웨어 역할(카테고리)을 정의한다.
 * 동일 카테고리 내에서 구현체(ToolImplementation)를 교체할 수 있다.
 */
public enum ToolCategory {
    CI_CD_TOOL,
    CONTAINER_REGISTRY,
    LIBRARY,
    STORAGE,
    CLUSTER_APPLICATION,
    VCS
}
