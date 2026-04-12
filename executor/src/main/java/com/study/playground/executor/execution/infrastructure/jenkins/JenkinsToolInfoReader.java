package com.study.playground.executor.execution.infrastructure.jenkins;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class JenkinsToolInfoReader {

    private final JdbcTemplate jdbcTemplate;

    private final Map<Long, CachedToolInfo> toolInfoCache = new ConcurrentHashMap<>();

    private static final Duration TOOL_CACHE_TTL = Duration.ofSeconds(30);

    public JenkinsToolInfo get(long jenkinsInstanceId) {
        var cached = toolInfoCache.get(jenkinsInstanceId);
        if (cached != null && Instant.now().isBefore(cached.cachedAt().plus(TOOL_CACHE_TTL))) {
            return cached.info();
        }

        var sql = "SELECT url, username, api_token, health_status, health_checked_at "
                + "FROM operator.support_tool WHERE id = ?";
        var info = jdbcTemplate.queryForObject(sql, (rs, rowNum) -> new JenkinsToolInfo(
                jenkinsInstanceId,
                rs.getString("url"),
                rs.getString("username"),
                rs.getString("api_token"),
                rs.getString("health_status"),
                rs.getTimestamp("health_checked_at") != null
                        ? rs.getTimestamp("health_checked_at").toLocalDateTime()
                        : null
        ), jenkinsInstanceId);
        toolInfoCache.put(jenkinsInstanceId, new CachedToolInfo(info, Instant.now()));
        return info;
    }

    record CachedToolInfo(JenkinsToolInfo info, Instant cachedAt) {}

    public record JenkinsToolInfo(long instanceId,
                                  String url,
                                  String username,
                                  String apiToken,
                                  String healthStatus,
                                  LocalDateTime healthCheckedAt) {}
}
