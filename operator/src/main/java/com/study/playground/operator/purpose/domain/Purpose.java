package com.study.playground.operator.purpose.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 목적 — 역할(카테고리)별 도구 조합을 정의한다.
 *
 * <p>목적은 "이 팀/프로젝트는 CI에 Jenkins, VCS에 GitLab, Registry에 Harbor를 사용한다"는
 * 도구 조합을 하나의 이름으로 묶은 것이다. 파이프라인이 목적을 참조하면,
 * 목적의 도구를 변경하는 것만으로 해당 목적을 사용하는 모든 파이프라인의
 * 도구가 교체된다.
 */
@Entity
@Table(name = "purpose")
@Getter
@Setter
public class Purpose {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String description;

    @Column(name = "project_id")
    private Long projectId;

    @OneToMany(mappedBy = "purpose", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PurposeEntry> entries = new ArrayList<>();

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        var now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
