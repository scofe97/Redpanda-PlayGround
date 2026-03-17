package com.study.playground.preset.domain;

import com.study.playground.supporttool.domain.ToolCategory;
import com.study.playground.supporttool.domain.ToolImplementation;
import lombok.Getter;
import lombok.Setter;

/**
 * 프리셋 항목 — 카테고리 하나에 도구 하나를 매핑한다.
 *
 * <p>프리셋당 동일 카테고리는 하나만 허용된다(DB UNIQUE 제약).
 * toolName, toolUrl, toolImplementation은 조회 시 JOIN으로 채워지는 읽기 전용 필드다.
 */
@Getter
@Setter
public class PresetEntry {
    private Long id;
    private Long presetId;
    private ToolCategory category;
    private Long toolId;

    // JOIN으로 채워지는 읽기 전용 필드
    private String toolName;
    private String toolUrl;
    private ToolImplementation toolImplementation;
}
