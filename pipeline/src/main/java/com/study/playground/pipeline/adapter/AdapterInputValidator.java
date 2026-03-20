package com.study.playground.pipeline.adapter;

import com.study.playground.common.dto.CommonErrorCode;
import com.study.playground.common.exception.BusinessException;

import java.util.regex.Pattern;

public final class AdapterInputValidator {

    private static final Pattern SAFE_PATH = Pattern.compile("^[a-zA-Z0-9_./-]+$");

    private AdapterInputValidator() {}

    public static String validatePathParam(String value, String paramName) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, paramName + "은(는) 필수입니다");
        }
        if (!SAFE_PATH.matcher(value).matches()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, paramName + "에 허용되지 않는 문자가 포함되어 있습니다: " + value);
        }
        if (value.contains("..")) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, paramName + "에 경로 순회가 감지되었습니다");
        }
        return value;
    }

    public static void validateBaseUrl(String url, String expectedBase) {
        if (url == null || !url.startsWith(expectedBase)) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "URL이 허용된 서버를 가리키지 않습니다");
        }
    }
}
