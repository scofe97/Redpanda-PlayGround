package com.study.playground.common.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {

    INVALID_INPUT("COMMON_001", "입력값이 올바르지 않습니다.", Exposure.PUBLIC, 400),
    RESOURCE_NOT_FOUND("COMMON_002", "요청한 리소스를 찾을 수 없습니다.", Exposure.PUBLIC, 404),
    INTERNAL_ERROR("COMMON_003", "내부 서버 오류가 발생했습니다.", Exposure.INTERNAL, 500),
    DUPLICATE_RESOURCE("COMMON_004", "이미 존재하는 리소스입니다.", Exposure.PUBLIC, 409),
    INVALID_STATE("COMMON_005", "현재 상태에서 수행할 수 없는 작업입니다.", Exposure.PUBLIC, 409);

    private final String code;
    private final String defaultMessage;
    private final Exposure exposure;
    private final int httpStatus;
}
