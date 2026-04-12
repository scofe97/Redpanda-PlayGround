package com.study.playground.operator.supporttool.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "support_tool")
@Getter
@Setter
public class SupportTool {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private ToolCategory category;

    @Enumerated(EnumType.STRING)
    private ToolImplementation implementation;

    private String name;

    private String url;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type")
    private AuthType authType;

    private String username;

    private String credential;

    @Column(name = "api_token")
    private String apiToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "health_status")
    private JenkinsHealthStatus healthStatus;

    @Column(name = "health_checked_at")
    private LocalDateTime healthCheckedAt;

    private boolean active;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public void updateHealth(JenkinsHealthStatus healthStatus, LocalDateTime healthCheckedAt, String apiToken) {
        this.healthStatus = healthStatus;
        this.healthCheckedAt = healthCheckedAt;
        if (apiToken != null) {
            this.apiToken = apiToken;
        }
    }

    @PrePersist
    void prePersist() {
        var now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (healthStatus == null) healthStatus = JenkinsHealthStatus.UNKNOWN;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
