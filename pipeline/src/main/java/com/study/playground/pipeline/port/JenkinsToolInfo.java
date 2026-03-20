package com.study.playground.pipeline.port;

/**
 * Jenkins 접속에 필요한 최소 정보.
 * supporttool 도메인에 대한 의존 없이 pipeline 모듈 내부에서 사용한다.
 */
public record JenkinsToolInfo(
        String url,
        String username,
        String credential
) {}
