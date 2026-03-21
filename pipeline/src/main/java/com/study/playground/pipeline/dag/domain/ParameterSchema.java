package com.study.playground.pipeline.dag.domain;

/**
 * 파이프라인 파라미터 스키마 정의.
 *
 * <p>파이프라인 정의 시 어떤 파라미터를 받을 수 있는지 선언한다.
 * type은 STRING, NUMBER, BOOLEAN을 지원하지만 PoC 범위에서는 모두 String으로 전달되며,
 * 타입 필드는 문서화·UI 힌트 용도로만 사용한다.</p>
 *
 * @param name         파라미터 이름. [A-Za-z0-9_]만 허용.
 * @param type         파라미터 타입 (STRING, NUMBER, BOOLEAN). 문서화 용도.
 * @param defaultValue 기본값. required=false이고 사용자가 값을 제공하지 않으면 이 값을 사용한다.
 * @param required     필수 여부. true이면 실행 시 반드시 값을 제공해야 한다.
 */
public record ParameterSchema(String name, String type, String defaultValue, boolean required) {
}
