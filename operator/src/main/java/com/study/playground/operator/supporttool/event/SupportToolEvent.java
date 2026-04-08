package com.study.playground.operator.supporttool.event;

import com.study.playground.operator.supporttool.domain.SupportTool;

/**
 * SupportTool 생성/삭제 시 발행되는 도메인 이벤트.
 * connector 패키지가 이 이벤트를 구독하여 커넥터를 생성/삭제한다.
 * 이를 통해 supporttool → connector 의존을 제거하고 순환을 방지한다.
 */
public sealed interface SupportToolEvent {

    record Created(SupportTool tool) implements SupportToolEvent {}

    record Deleted(Long toolId) implements SupportToolEvent {}
}
