/**
 * Kafka 공통 모듈 설명.
 *
 * <p>이 패키지는 애플리케이션 도메인과 분리된 Kafka 공통 기능을 제공한다.
 *
 * <ul>
 *   <li>config: Producer/에러 핸들러/토픽 생성 설정</li>
 *   <li>topic: 토픽 이름 상수 및 토픽 Bean 정의</li>
 *   <li>serialization: Avro 직렬화/역직렬화 유틸</li>
 *   <li>interceptor: CloudEvents 헤더 표준화</li>
 *   <li>dlq: 공통 DLQ 소비/로그 처리</li>
 * </ul>
 *
 * <p>목표는 app 모듈이 Kafka 저수준 설정을 중복 구현하지 않도록 공통 경계를 유지하는 것이다.
 */
package com.study.playground.kafka;
