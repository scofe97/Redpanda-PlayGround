package com.study.playground.pipeline.port;

/**
 * 외부 도구 등록 정보를 조회하는 포트.
 * pipeline 모듈은 이 인터페이스만 의존하고,
 * 구현체는 app 모듈에서 주입한다.
 */
public interface ToolRegistryPort {

    /** 활성화된 Jenkins 도구 정보를 반환한다. */
    JenkinsToolInfo getActiveJenkinsTool();
}
