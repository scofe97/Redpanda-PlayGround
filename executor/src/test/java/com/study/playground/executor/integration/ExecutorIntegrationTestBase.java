package com.study.playground.executor.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.study.playground.avro.executor.ExecutorJobDispatchCommand;
import com.study.playground.kafka.serialization.AvroSerializer;
import com.study.playground.kafka.topic.Topics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * GCP 실 인프라 연동 통합 테스트 베이스.
 * <p>
 * PostgreSQL, Redpanda(Kafka), Jenkins, Schema Registry 모두 GCP에 위치한다.
 * 테스트 코드가 Operator 역할을 수행하여 Avro dispatch command를 발행하고,
 * Executor REST API와 DB를 통해 결과를 검증한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class ExecutorIntegrationTestBase {

    private static final String LOG_BASE_PATH = "/tmp/executor-test-logs";
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final Set<String> REQUIRED_LISTENER_TOPICS = Set.of(
            Topics.EXECUTOR_CMD_JOB_DISPATCH
            , Topics.EXECUTOR_CMD_JOB_EXECUTE
            , Topics.EXECUTOR_EVT_JOB_STARTED
            , Topics.EXECUTOR_EVT_JOB_COMPLETED
    );

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected KafkaTemplate<String, byte[]> kafkaTemplate;

    @Autowired
    protected AvroSerializer avroSerializer;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @BeforeEach
    void cleanUp() {
        waitForListenerAssignments();
        ensureOperatorSupportToolColumns();
        ensureJenkinsRuntimeAccess();
        jdbcTemplate.execute("DELETE FROM executor.outbox_event");
        jdbcTemplate.execute("DELETE FROM executor.execution_job");
        jdbcTemplate.update("DELETE FROM operator.job WHERE created_by = ?", "executor-test");
        cleanLogDirectory();
    }

    private void waitForListenerAssignments() {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);

        while (System.currentTimeMillis() < deadline) {
            var requiredContainers = kafkaListenerEndpointRegistry.getListenerContainers().stream()
                    .filter(this::isRequiredListenerContainer)
                    .toList();

            if (!requiredContainers.isEmpty() && requiredContainers.stream().allMatch(this::hasAssignedPartition)) {
                return;
            }

            sleep(500);
        }

        var states = kafkaListenerEndpointRegistry.getListenerContainers().stream()
                .filter(this::isRequiredListenerContainer)
                .map(container -> "topics=" + Arrays.toString(container.getContainerProperties().getTopics())
                        + ", running=" + container.isRunning()
                        + ", assigned=" + container.getAssignedPartitions())
                .toList();

        throw new IllegalStateException("Kafka listener assignment timeout: " + states);
    }

    private boolean isRequiredListenerContainer(MessageListenerContainer container) {
        var topics = container.getContainerProperties().getTopics();
        if (topics == null || topics.length == 0) {
            return false;
        }
        return Arrays.stream(topics).anyMatch(REQUIRED_LISTENER_TOPICS::contains);
    }

    private boolean hasAssignedPartition(MessageListenerContainer container) {
        var assigned = container.getAssignedPartitions();
        return container.isRunning() && assigned != null && !assigned.isEmpty();
    }

    private void ensureOperatorSupportToolColumns() {
        jdbcTemplate.execute("""
                ALTER TABLE operator.support_tool
                ADD COLUMN IF NOT EXISTS health_status VARCHAR(10) NOT NULL DEFAULT 'UNKNOWN'
                """);
        jdbcTemplate.execute("""
                ALTER TABLE operator.support_tool
                ADD COLUMN IF NOT EXISTS health_checked_at TIMESTAMP
                """);
        jdbcTemplate.execute("""
                ALTER TABLE operator.support_tool
                ADD COLUMN IF NOT EXISTS api_token VARCHAR(500)
                """);
    }

    private void ensureJenkinsRuntimeAccess() {
        var tools = jdbcTemplate.query("""
                        SELECT id, url, username, credential, api_token
                        FROM operator.support_tool
                        WHERE implementation = 'JENKINS'
                          AND active = true
                        """
                , (rs, rowNum) -> new JenkinsRuntimeSeed(
                        rs.getLong("id")
                        , rs.getString("url")
                        , rs.getString("username")
                        , rs.getString("credential")
                        , rs.getString("api_token")
                ));

        for (var tool : tools) {
            try {
                var token = ensureApiToken(tool);
                jdbcTemplate.update("""
                                UPDATE operator.support_tool
                                   SET api_token = ?
                                     , health_status = 'HEALTHY'
                                     , health_checked_at = NOW()
                                 WHERE id = ?
                                """
                        , token, tool.id());
            } catch (RuntimeException e) {
                jdbcTemplate.update("""
                                UPDATE operator.support_tool
                                   SET health_status = 'UNHEALTHY'
                                     , health_checked_at = NOW()
                                 WHERE id = ?
                                """
                        , tool.id());
            }
        }
    }

    private String ensureApiToken(JenkinsRuntimeSeed tool) {
        if (tool.apiToken() != null && !tool.apiToken().isBlank() && isApiTokenUsable(tool, tool.apiToken())) {
            return tool.apiToken();
        }
        return issueApiToken(tool);
    }

    private boolean isApiTokenUsable(JenkinsRuntimeSeed tool, String apiToken) {
        try {
            var request = HttpRequest.newBuilder(URI.create(tool.url() + "/api/json"))
                    .header("Authorization", basicAuth(tool.username(), apiToken))
                    .GET()
                    .build();
            var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private String issueApiToken(JenkinsRuntimeSeed tool) {
        try {
            var crumbRequest = HttpRequest.newBuilder(URI.create(tool.url() + "/crumbIssuer/api/json"))
                    .header("Authorization", basicAuth(tool.username(), tool.credential()))
                    .GET()
                    .build();
            var crumbResponse = HTTP_CLIENT.send(crumbRequest, HttpResponse.BodyHandlers.ofString());
            if (crumbResponse.statusCode() != 200) {
                throw new IllegalStateException("Failed to fetch Jenkins crumb: status=" + crumbResponse.statusCode());
            }

            var crumb = objectMapper.readTree(crumbResponse.body()).path("crumb").asText(null);
            if (crumb == null || crumb.isBlank()) {
                throw new IllegalStateException("Jenkins crumb missing in response");
            }
            var cookie = crumbResponse.headers().firstValue("Set-Cookie")
                    .map(value -> value.split(";", 2)[0])
                    .orElseThrow(() -> new IllegalStateException("Jenkins crumb session cookie missing"));

            var tokenName = "executor-it-" + UUID.randomUUID();
            var formBody = "newTokenName=" + URLEncoder.encode(tokenName, StandardCharsets.UTF_8);
            var tokenRequest = HttpRequest.newBuilder(URI.create(
                            tool.url() + "/user/" + tool.username()
                                    + "/descriptorByName/jenkins.security.ApiTokenProperty/generateNewToken"))
                    .header("Authorization", basicAuth(tool.username(), tool.credential()))
                    .header("Jenkins-Crumb", crumb)
                    .header("Cookie", cookie)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .build();
            var tokenResponse = HTTP_CLIENT.send(tokenRequest, HttpResponse.BodyHandlers.ofString());
            if (tokenResponse.statusCode() != 200) {
                throw new IllegalStateException("Failed to issue Jenkins API token: status=" + tokenResponse.statusCode());
            }

            var token = objectMapper.readTree(tokenResponse.body()).path("data").path("tokenValue").asText(null);
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("Jenkins API token missing in response");
            }
            return token;
        } catch (Exception e) {
            throw new RuntimeException("Failed to prepare Jenkins runtime access for integration test", e);
        }
    }

    private String basicAuth(String username, String secret) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    // ── Kafka publishing ──

    /**
     * Avro dispatch command를 Kafka에 발행한다.
     * 테스트 코드가 Operator 역할을 대행하는 것이다.
     */
    protected void publishDispatchCommand(
            String jobExcnId
            , String jobId
    ) {
        var cmd = ExecutorJobDispatchCommand.newBuilder()
                .setJobExcnId(jobExcnId)
                .setPipelineExcnId(null)
                .setJobId(jobId)
                .setPriorityDt(Instant.now().toString())
                .setRgtrId(null)
                .setTimestamp(Instant.now().toString())
                .build();

        byte[] bytes = avroSerializer.serialize(cmd);
        try {
            kafkaTemplate.send(Topics.EXECUTOR_CMD_JOB_DISPATCH, jobExcnId, bytes)
                    .get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while publishing dispatch command", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new RuntimeException("Failed to publish dispatch command", e);
        }
    }

    /**
     * Jenkins completed 콜백을 JSON으로 Kafka에 발행한다.
     * webhook-listener.groovy가 rpk produce로 보내는 형식을 시뮬레이션한다.
     */
    protected void publishCompletedCallback(
            String jobId
            , int buildNumber
            , String result
            , String logContent
    ) {
        try {
            var json = objectMapper.writeValueAsBytes(Map.of(
                    "jobId", jobId
                    , "buildNumber", buildNumber
                    , "result", result
                    , "logContent", logContent != null ? logContent : ""
            ));
            kafkaTemplate.send(Topics.EXECUTOR_EVT_JOB_COMPLETED, jobId, json)
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish completed callback", e);
        }
    }

    /**
     * Jenkins started 콜백을 JSON으로 Kafka에 발행한다.
     */
    protected void publishStartedCallback(String jobId, int buildNumber) {
        try {
            var json = objectMapper.writeValueAsBytes(Map.of(
                    "jobId", jobId
                    , "buildNumber", buildNumber
            ));
            kafkaTemplate.send(Topics.EXECUTOR_EVT_JOB_STARTED, jobId, json)
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish started callback", e);
        }
    }

    // ── API polling ──

    /**
     * 지정한 상태에 도달할 때까지 REST API를 폴링한다.
     *
     * @param jobExcnId      Job 실행 건 식별자
     * @param expectedStatus 기대하는 상태 문자열
     * @param timeoutSeconds 최대 대기 시간 (초)
     * @return API 응답 Map
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> waitForStatus(
            String jobExcnId
            , String expectedStatus
            , int timeoutSeconds
    ) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                var response = restTemplate.getForObject(
                        "/api/executor/jobs/" + jobExcnId, Map.class);
                if (response != null && expectedStatus.equals(response.get("status"))) {
                    return response;
                }
            } catch (Exception ignored) {
                // 아직 Job이 생성되지 않았을 수 있음
            }
            sleep(3000);
        }
        // 타임아웃 시 마지막 상태를 포함하여 실패
        var lastResponse = getExecutorJob(jobExcnId);
        throw new AssertionError(
                "Timeout waiting for status " + expectedStatus + " on job " + jobExcnId
                        + ". Last response: " + lastResponse
        );
    }

    /**
     * 지정한 상태 중 하나에 도달할 때까지 폴링한다.
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> waitForAnyStatus(
            String jobExcnId
            , java.util.List<String> expectedStatuses
            , int timeoutSeconds
    ) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                var response = restTemplate.getForObject(
                        "/api/executor/jobs/" + jobExcnId, Map.class);
                if (response != null && expectedStatuses.contains(response.get("status"))) {
                    return response;
                }
            } catch (Exception ignored) {}
            sleep(3000);
        }
        var lastResponse = getExecutorJob(jobExcnId);
        throw new AssertionError(
                "Timeout waiting for any of " + expectedStatuses + " on job " + jobExcnId
                        + ". Last response: " + lastResponse
        );
    }

    // ── API queries ──

    @SuppressWarnings("unchecked")
    protected Map<String, Object> getExecutorJob(String jobExcnId) {
        return restTemplate.getForObject("/api/executor/jobs/" + jobExcnId, Map.class);
    }

    // ── DB queries ──

    protected int countJobsInDbById(String jobExcnId) {
        var count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM executor.execution_job WHERE job_excn_id = ?"
                , Integer.class, jobExcnId);
        return count != null ? count : 0;
    }

    protected String getJobStatusFromDb(String jobExcnId) {
        return jdbcTemplate.queryForObject(
                "SELECT excn_stts FROM executor.execution_job WHERE job_excn_id = ?"
                , String.class, jobExcnId);
    }

    protected int getRetryCntFromDb(String jobExcnId) {
        var cnt = jdbcTemplate.queryForObject(
                "SELECT retry_cnt FROM executor.execution_job WHERE job_excn_id = ?"
                , Integer.class, jobExcnId);
        return cnt != null ? cnt : 0;
    }

    protected String createMissingJenkinsJobDefinition() {
        String jobId = "jt" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update("""
                        INSERT INTO operator.job (
                            job_id, project_id, preset_id, category, type, deleted, created_by, updated_by
                        ) VALUES (?, '1', '3', 'CI_CD', 'TEST', false, 'executor-test', 'executor-test')
                        """
                , jobId);
        return jobId;
    }

    // ── Log file checks ──

    protected boolean logFileExists(String jobName, String jobExcnId) {
        return findLogFilePath(jobExcnId).isPresent();
    }

    protected String readLogFile(String jobName, String jobExcnId) {
        try {
            return Files.readString(findLogFilePath(jobExcnId)
                    .orElseThrow(() -> new IllegalStateException("Log file not found: " + jobExcnId)));
        } catch (IOException e) {
            throw new RuntimeException("Failed to read log file", e);
        }
    }

    private Optional<Path> findLogFilePath(String jobExcnId) {
        var logDir = Path.of(LOG_BASE_PATH);
        if (!Files.exists(logDir)) {
            return Optional.empty();
        }

        try (var walk = Files.walk(logDir)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(jobExcnId + "_0"))
                    .findFirst();
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan log directory", e);
        }
    }

    // ── Helpers ──

    protected static String uniqueId(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }

    private void cleanLogDirectory() {
        try {
            var logDir = Path.of(LOG_BASE_PATH);
            if (Files.exists(logDir)) {
                try (var walk = Files.walk(logDir)) {
                    walk.sorted(Comparator.reverseOrder())
                            .forEach(p -> {
                                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                            });
                }
            }
        } catch (IOException ignored) {}
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record JenkinsRuntimeSeed(long id,
                                      String url,
                                      String username,
                                      String credential,
                                      String apiToken) {
    }
}
