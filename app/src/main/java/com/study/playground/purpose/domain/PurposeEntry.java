package com.study.playground.purpose.domain;

import com.study.playground.supporttool.domain.ToolCategory;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * 목적 항목 — 카테고리 하나에 도구 하나를 매핑한다.
 *
 * <p>목적당 동일 카테고리는 하나만 허용된다(DB UNIQUE 제약).
 * SupportTool은 다른 Aggregate이므로 ID로만 참조한다.
 */
@Entity
@Table(name = "purpose_entry")
@Getter
@Setter
public class PurposeEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purpose_id")
    private Purpose purpose;

    @Enumerated(EnumType.STRING)
    private ToolCategory category;

    @Column(name = "tool_id")
    private Long toolId;
}
