package com.study.playground.common.dto;

import lombok.Getter;

@Getter
public class ApiError {

    private final String code;
    private final String message;
    private final Exposure exposure;

    private ApiError(String code, String message, Exposure exposure) {
        this.code = code;
        this.message = message;
        this.exposure = exposure;
    }

    public static ApiError of(ErrorCode errorCode, String message) {
        return new ApiError(errorCode.getCode(), message, errorCode.getExposure());
    }

    public static ApiError of(ErrorCode errorCode) {
        return new ApiError(errorCode.getCode(), errorCode.getDefaultMessage(), errorCode.getExposure());
    }
}
