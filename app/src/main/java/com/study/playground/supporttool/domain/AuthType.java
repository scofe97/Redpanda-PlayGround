package com.study.playground.supporttool.domain;

public enum AuthType {
    BASIC,           // username + password/token → Basic Auth 헤더
    BEARER,          // credential → Bearer token 헤더
    PRIVATE_TOKEN,   // credential → Private-Token 헤더 (GitLab)
    NONE             // 인증 없음
}
