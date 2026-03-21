package com.study.playground.pipeline.engine;

import com.study.playground.common.dto.CommonErrorCode;
import com.study.playground.common.exception.BusinessException;
import com.study.playground.pipeline.dag.domain.ParameterSchema;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 파이프라인 실행 파라미터의 검증·치환·병합을 담당하는 유틸리티.
 *
 * <p>세 가지 책임을 가진다:
 * <ol>
 *   <li>validate — 스키마 기반 필수 파라미터 검증 + 기본값 적용</li>
 *   <li>resolve — 문자열 내 ${PARAM_NAME} 플레이스홀더 치환</li>
 *   <li>merge — 시스템 파라미터와 사용자 파라미터 합성 (시스템 우선)</li>
 * </ol>
 *
 * <p>보안: 파라미터 이름은 [A-Za-z0-9_]만 허용하여 인젝션을 방지한다.</p>
 */
public final class ParameterResolver {

    private static final Pattern PARAM_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]+$");
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");

    private ParameterResolver() {
    }

    /**
     * 스키마를 기반으로 사용자 파라미터를 검증하고 기본값을 적용한다.
     *
     * @param schemas    파라미터 스키마 목록 (null이면 빈 목록 취급)
     * @param userParams 사용자가 제공한 파라미터 (null이면 빈 맵 취급)
     * @return 기본값이 적용된 최종 파라미터 맵
     * @throws BusinessException 필수 파라미터가 누락되었거나 이름이 유효하지 않을 때
     */
    public static Map<String, String> validate(
            List<ParameterSchema> schemas
            , Map<String, String> userParams) {
        if (schemas == null || schemas.isEmpty()) {
            return userParams != null ? new HashMap<>(userParams) : new HashMap<>();
        }

        var params = userParams != null ? new HashMap<>(userParams) : new HashMap<String, String>();
        var missing = new ArrayList<String>();

        for (var schema : schemas) {
            if (!PARAM_NAME_PATTERN.matcher(schema.name()).matches()) {
                throw new BusinessException(CommonErrorCode.INVALID_INPUT
                        , "유효하지 않은 파라미터 이름: " + schema.name() + " ([A-Za-z0-9_]만 허용)");
            }

            if (!params.containsKey(schema.name()) || params.get(schema.name()) == null) {
                if (schema.defaultValue() != null) {
                    params.put(schema.name(), schema.defaultValue());
                } else if (schema.required()) {
                    missing.add(schema.name());
                }
            }
        }

        if (!missing.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT
                    , "필수 파라미터가 누락되었습니다: " + String.join(", ", missing));
        }

        return params;
    }

    /**
     * 문자열 내 ${PARAM_NAME} 플레이스홀더를 파라미터 값으로 치환한다.
     *
     * @param template 치환 대상 문자열 (null이면 null 반환)
     * @param params   파라미터 맵
     * @return 치환된 문자열
     */
    public static String resolve(String template, Map<String, String> params) {
        if (template == null || params == null || params.isEmpty()) {
            return template;
        }

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String paramName = matcher.group(1);
            String value = params.getOrDefault(paramName, matcher.group(0));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 시스템 파라미터와 사용자 파라미터를 합성한다.
     * 시스템 파라미터가 우선하므로, 사용자가 EXECUTION_ID 등을 override할 수 없다.
     *
     * @param systemParams 시스템이 주입하는 파라미터 (EXECUTION_ID, STEP_ORDER 등)
     * @param userParams   사용자가 제공한 파라미터
     * @return 합성된 파라미터 맵
     */
    public static Map<String, String> merge(
            Map<String, String> systemParams
            , Map<String, String> userParams) {
        var merged = new HashMap<String, String>();
        if (userParams != null) {
            merged.putAll(userParams);
        }
        if (systemParams != null) {
            merged.putAll(systemParams); // 시스템이 우선
        }
        return merged;
    }
}
