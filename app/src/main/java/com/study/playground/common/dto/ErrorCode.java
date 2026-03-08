package com.study.playground.common.dto;

public interface ErrorCode {
    String getCode();
    String getDefaultMessage();
    Exposure getExposure();
    int getHttpStatus();
}
