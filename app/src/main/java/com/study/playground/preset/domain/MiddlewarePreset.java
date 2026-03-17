package com.study.playground.preset.domain;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 미들웨어 프리셋 — 역할(카테고리)별 도구 조합을 정의한다.
 *
 * <p>프리셋은 "이 팀/프로젝트는 CI에 Jenkins, VCS에 GitLab, Registry에 Harbor를 사용한다"는
 * 도구 조합을 하나의 이름으로 묶은 것이다. 파이프라인이 프리셋을 참조하면,
 * 프리셋의 도구를 변경하는 것만으로 해당 프리셋을 사용하는 모든 파이프라인의
 * 도구가 교체된다.
 */
@Getter
@Setter
public class MiddlewarePreset {
    private Long id;
    private String name;
    private String description;
    private List<PresetEntry> entries = new ArrayList<>();
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
