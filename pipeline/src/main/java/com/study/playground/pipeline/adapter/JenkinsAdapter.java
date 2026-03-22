package com.study.playground.pipeline.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.study.playground.pipeline.port.JenkinsToolInfo;
import com.study.playground.pipeline.port.ToolRegistryPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.HashSet;
import java.util.Set;

@Slf4j
@Component
public class JenkinsAdapter {

    private final RestTemplate restTemplate;
    private final ToolRegistryPort toolRegistryPort;

    public JenkinsAdapter(RestTemplate restTemplate, ToolRegistryPort toolRegistryPort) {
        this.restTemplate = restTemplate;
        this.toolRegistryPort = toolRegistryPort;
    }

    private JenkinsToolInfo getTool() {
        return toolRegistryPort.getActiveJenkinsTool();
    }

    /** 지정한 Job과 빌드 번호의 빌드 정보를 조회한다. */
    public JenkinsBuildInfo getBuildInfo(String jobName, int buildNumber) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(getTool().url())
                    .path(toJobPath(jobName))
                    .pathSegment(String.valueOf(buildNumber))
                    .path("/api/json")
                    .toUriString();
            ResponseEntity<JenkinsBuildInfo> response = restTemplate.exchange(
                    url, HttpMethod.GET,
                    new HttpEntity<>(buildHeaders()),
                    new ParameterizedTypeReference<>() {});
            return response.getBody();
        } catch (Exception e) {
            log.warn("Jenkins getBuildInfo failed: job={}, build={}: {}", jobName, buildNumber, e.getMessage());
            return null;
        }
    }

    /** Job의 마지막 빌드 번호를 조회한다. 실패 시 -1 반환. */
    public int getLastBuildNumber(String jobName) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(getTool().url())
                    .path(toJobPath(jobName))
                    .pathSegment("lastBuild")
                    .path("/api/json")
                    .toUriString();
            ResponseEntity<JenkinsBuildInfo> response = restTemplate.exchange(
                    url, HttpMethod.GET,
                    new HttpEntity<>(buildHeaders()),
                    new ParameterizedTypeReference<>() {});
            JenkinsBuildInfo body = response.getBody();
            return body != null ? body.number() : -1;
        } catch (Exception e) {
            log.warn("Jenkins getLastBuildNumber failed for job={}: {}", jobName, e.getMessage());
            return -1;
        }
    }

    /** Jenkins 연결 가능 여부를 확인한다. */
    public boolean isAvailable() {
        try {
            JenkinsToolInfo tool = getTool();
            restTemplate.exchange(
                    tool.url() + "/api/json", HttpMethod.GET,
                    new HttpEntity<>(buildHeaders()),
                    String.class);
            return true;
        } catch (Exception e) {
            log.debug("Jenkins not available: {}", e.getMessage());
            return false;
        }
    }

    /** 지정한 Job과 빌드 번호의 콘솔 로그 전문을 조회한다. */
    public String getConsoleLog(String jobName, int buildNumber) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(getTool().url())
                    .path(toJobPath(jobName))
                    .pathSegment(String.valueOf(buildNumber), "consoleText")
                    .toUriString();
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET,
                    new HttpEntity<>(buildHeaders()),
                    String.class);
            return response.getBody();
        } catch (Exception e) {
            log.warn("Jenkins getConsoleLog failed: job={}, build={}: {}", jobName, buildNumber, e.getMessage());
            return null;
        }
    }

    // ── Jenkins Pipeline Job 관리 메서드 ──

    /**
     * Jenkins 파이프라인 Job을 멱등하게 생성 또는 업데이트한다.
     * create → 이미 존재(400) → update 순서로 시도한다.
     */
    public void upsertPipelineJob(String jobName, String scriptContent) {
        AdapterInputValidator.validatePathParam(jobName, "jobName");
        try {
            createPipelineJob(jobName, scriptContent);
        } catch (HttpClientErrorException.BadRequest e) {
            // 이미 존재 → update로 전환
            log.debug("Jenkins Job 이미 존재, update 전환: {}", jobName);
            updatePipelineJob(jobName, scriptContent);
        }
    }

    /** Jenkins 파이프라인 Job을 생성한다. */
    public void createPipelineJob(String jobName, String scriptContent) {
        AdapterInputValidator.validatePathParam(jobName, "jobName");
        JenkinsToolInfo tool = getTool();
        String url = UriComponentsBuilder.fromHttpUrl(tool.url())
                .path("/createItem")
                .queryParam("name", jobName)
                .toUriString();

        HttpHeaders headers = buildHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);

        restTemplate.exchange(url, HttpMethod.POST
                , new HttpEntity<>(buildConfigXml(scriptContent), headers)
                , String.class);
        log.info("Jenkins 파이프라인 생성: {}", jobName);
    }

    /** Jenkins 파이프라인 Job의 config.xml을 업데이트한다. */
    public void updatePipelineJob(String jobName, String scriptContent) {
        AdapterInputValidator.validatePathParam(jobName, "jobName");
        JenkinsToolInfo tool = getTool();
        String url = UriComponentsBuilder.fromHttpUrl(tool.url())
                .pathSegment("job", jobName, "config.xml")
                .toUriString();

        HttpHeaders headers = buildHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);

        restTemplate.exchange(url, HttpMethod.POST
                , new HttpEntity<>(buildConfigXml(scriptContent), headers)
                , String.class);
        log.info("Jenkins 파이프라인 업데이트: {}", jobName);
    }

    /** Jenkins 파이프라인 Job을 삭제한다. 존재하지 않으면 무시한다. */
    public void deletePipelineJob(String jobName) {
        AdapterInputValidator.validatePathParam(jobName, "jobName");
        try {
            JenkinsToolInfo tool = getTool();
            String url = UriComponentsBuilder.fromHttpUrl(tool.url())
                    .pathSegment("job", jobName, "doDelete")
                    .toUriString();
            restTemplate.exchange(url, HttpMethod.POST
                    , new HttpEntity<>(buildHeaders())
                    , String.class);
            log.info("Jenkins 파이프라인 삭제: {}", jobName);
        } catch (HttpClientErrorException.NotFound e) {
            log.debug("Jenkins Job이 이미 존재하지 않음: {}", jobName);
        }
    }

    /** Jenkins에 해당 Job이 존재하는지 확인한다. */
    public boolean jobExists(String jobName) {
        try {
            AdapterInputValidator.validatePathParam(jobName, "jobName");
            JenkinsToolInfo tool = getTool();
            String url = UriComponentsBuilder.fromHttpUrl(tool.url())
                    .pathSegment("job", jobName)
                    .path("/api/json")
                    .toUriString();
            restTemplate.exchange(url, HttpMethod.GET
                    , new HttpEntity<>(buildHeaders())
                    , String.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Jenkins에 등록된 전체 Job 이름 목록을 조회한다. */
    public Set<String> listJobNames() {
        Set<String> names = new HashSet<>();
        try {
            JenkinsToolInfo tool = getTool();
            String url = UriComponentsBuilder.fromHttpUrl(tool.url())
                    .path("/api/json")
                    .queryParam("tree", "jobs[name]")
                    .toUriString();
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET
                    , new HttpEntity<>(buildHeaders())
                    , String.class);
            if (response.getBody() != null) {
                JsonNode root = new ObjectMapper().readTree(response.getBody());
                JsonNode jobs = root.get("jobs");
                if (jobs != null && jobs.isArray()) {
                    for (JsonNode job : jobs) {
                        names.add(job.get("name").asText());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Jenkins Job 목록 조회 실패: {}", e.getMessage());
        }
        return names;
    }

    // ── Jenkins Folder 지원 메서드 ──

    /**
     * Jenkins 폴더가 없으면 생성한다. 이미 존재하면 무시한다 (멱등).
     * Folder Plugin (cloudbees-folder)이 설치되어 있어야 한다.
     */
    public void ensureFolderExists(String folderName) {
        AdapterInputValidator.validatePathParam(folderName, "folderName");
        try {
            JenkinsToolInfo tool = getTool();
            String checkUrl = UriComponentsBuilder.fromHttpUrl(tool.url())
                    .pathSegment("job", folderName)
                    .path("/api/json")
                    .toUriString();
            restTemplate.exchange(checkUrl, HttpMethod.GET
                    , new HttpEntity<>(buildHeaders())
                    , String.class);
            log.debug("Jenkins 폴더 이미 존재: {}", folderName);
        } catch (HttpClientErrorException.NotFound e) {
            createFolder(folderName);
        } catch (Exception e) {
            // 폴더 확인 실패 → 생성 시도 (이미 존재하면 400으로 무시)
            try {
                createFolder(folderName);
            } catch (HttpClientErrorException.BadRequest ignored) {
                log.debug("Jenkins 폴더 이미 존재 (생성 시 400): {}", folderName);
            }
        }
    }

    private void createFolder(String folderName) {
        JenkinsToolInfo tool = getTool();
        String url = UriComponentsBuilder.fromHttpUrl(tool.url())
                .path("/createItem")
                .queryParam("name", folderName)
                .toUriString();

        HttpHeaders headers = buildHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);

        String folderXml = """
                <?xml version='1.1' encoding='UTF-8'?>
                <com.cloudbees.hudson.plugins.folder.Folder plugin="cloudbees-folder">
                  <description>Auto-created folder for %s jobs</description>
                </com.cloudbees.hudson.plugins.folder.Folder>
                """.formatted(folderName);

        restTemplate.exchange(url, HttpMethod.POST
                , new HttpEntity<>(folderXml, headers)
                , String.class);
        log.info("Jenkins 폴더 생성: {}", folderName);
    }

    /**
     * 폴더 안에 Jenkins 파이프라인 Job을 멱등하게 생성 또는 업데이트한다.
     * 폴더가 없으면 먼저 생성한다.
     */
    public void upsertPipelineJob(String folderName, String jobName, String scriptContent) {
        upsertPipelineJob(folderName, jobName, scriptContent, Set.of());
    }

    public void upsertPipelineJob(String folderName, String jobName, String scriptContent, Set<String> configParams) {
        AdapterInputValidator.validatePathParam(folderName, "folderName");
        AdapterInputValidator.validatePathParam(jobName, "jobName");
        ensureFolderExists(folderName);
        try {
            createPipelineJobInFolder(folderName, jobName, scriptContent, configParams);
        } catch (HttpClientErrorException.BadRequest e) {
            log.debug("Jenkins Job 이미 존재, update 전환: {}/{}", folderName, jobName);
            updatePipelineJobInFolder(folderName, jobName, scriptContent, configParams);
        }
    }

    private void createPipelineJobInFolder(String folderName, String jobName, String scriptContent) {
        createPipelineJobInFolder(folderName, jobName, scriptContent, Set.of());
    }

    private void createPipelineJobInFolder(String folderName, String jobName, String scriptContent, Set<String> configParams) {
        JenkinsToolInfo tool = getTool();
        String url = UriComponentsBuilder.fromHttpUrl(tool.url())
                .pathSegment("job", folderName, "createItem")
                .queryParam("name", jobName)
                .toUriString();

        HttpHeaders headers = buildHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);

        restTemplate.exchange(url, HttpMethod.POST
                , new HttpEntity<>(buildConfigXml(scriptContent, configParams), headers)
                , String.class);
        log.info("Jenkins 파이프라인 생성: {}/{} (params={})", folderName, jobName, configParams);
    }

    private void updatePipelineJobInFolder(String folderName, String jobName, String scriptContent) {
        updatePipelineJobInFolder(folderName, jobName, scriptContent, Set.of());
    }

    private void updatePipelineJobInFolder(String folderName, String jobName, String scriptContent, Set<String> configParams) {
        JenkinsToolInfo tool = getTool();
        String url = UriComponentsBuilder.fromHttpUrl(tool.url())
                .pathSegment("job", folderName, "job", jobName, "config.xml")
                .toUriString();

        HttpHeaders headers = buildHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);

        restTemplate.exchange(url, HttpMethod.POST
                , new HttpEntity<>(buildConfigXml(scriptContent, configParams), headers)
                , String.class);
        log.info("Jenkins 파이프라인 업데이트: {}/{} (params={})", folderName, jobName, configParams);
    }

    /** 폴더 안의 Jenkins 파이프라인 Job을 삭제한다. 존재하지 않으면 무시한다. */
    public void deletePipelineJob(String folderName, String jobName) {
        AdapterInputValidator.validatePathParam(folderName, "folderName");
        AdapterInputValidator.validatePathParam(jobName, "jobName");
        try {
            JenkinsToolInfo tool = getTool();
            String url = UriComponentsBuilder.fromHttpUrl(tool.url())
                    .pathSegment("job", folderName, "job", jobName, "doDelete")
                    .toUriString();
            restTemplate.exchange(url, HttpMethod.POST
                    , new HttpEntity<>(buildHeaders())
                    , String.class);
            log.info("Jenkins 파이프라인 삭제: {}/{}", folderName, jobName);
        } catch (HttpClientErrorException.NotFound e) {
            log.debug("Jenkins Job이 이미 존재하지 않음: {}/{}", folderName, jobName);
        }
    }

    /** 폴더 안에 해당 Job이 존재하는지 확인한다. */
    public boolean jobExists(String folderName, String jobName) {
        try {
            AdapterInputValidator.validatePathParam(folderName, "folderName");
            AdapterInputValidator.validatePathParam(jobName, "jobName");
            JenkinsToolInfo tool = getTool();
            String url = UriComponentsBuilder.fromHttpUrl(tool.url())
                    .pathSegment("job", folderName, "job", jobName)
                    .path("/api/json")
                    .toUriString();
            restTemplate.exchange(url, HttpMethod.GET
                    , new HttpEntity<>(buildHeaders())
                    , String.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 폴더 안의 전체 Job 이름 목록을 조회한다. */
    public Set<String> listJobNamesInFolder(String folderName) {
        Set<String> names = new HashSet<>();
        try {
            AdapterInputValidator.validatePathParam(folderName, "folderName");
            JenkinsToolInfo tool = getTool();
            String url = UriComponentsBuilder.fromHttpUrl(tool.url())
                    .pathSegment("job", folderName)
                    .path("/api/json")
                    .queryParam("tree", "jobs[name]")
                    .toUriString();
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET
                    , new HttpEntity<>(buildHeaders())
                    , String.class);
            if (response.getBody() != null) {
                JsonNode root = new ObjectMapper().readTree(response.getBody());
                JsonNode jobs = root.get("jobs");
                if (jobs != null && jobs.isArray()) {
                    for (JsonNode job : jobs) {
                        names.add(job.get("name").asText());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Jenkins 폴더 내 Job 목록 조회 실패: folder={}, error={}", folderName, e.getMessage());
        }
        return names;
    }

    // ── 내부 헬퍼 ──

    /**
     * Declarative/Scripted Pipeline 스크립트를 Jenkins flow-definition config.xml로 래핑한다.
     * sandbox=true로 보안을 적용하고, CDATA로 스크립트를 이스케이프한다.
     */
    private String buildConfigXml(String scriptContent) {
        return buildConfigXml(scriptContent, Set.of());
    }

    /**
     * 추가 파라미터 이름을 포함하여 config.xml을 생성한다.
     * configJson의 키를 Jenkins parameterDefinitions에 등록하면
     * buildWithParameters로 전달된 값이 env로 접근 가능해진다.
     */
    String buildConfigXml(String scriptContent, Set<String> additionalParams) {
        var sb = new StringBuilder();
        sb.append("""
                <?xml version='1.1' encoding='UTF-8'?>
                <flow-definition plugin="workflow-job">
                  <properties>
                    <hudson.model.ParametersDefinitionProperty>
                      <parameterDefinitions>
                        <hudson.model.StringParameterDefinition>
                          <name>EXECUTION_ID</name>
                          <defaultValue></defaultValue>
                        </hudson.model.StringParameterDefinition>
                        <hudson.model.StringParameterDefinition>
                          <name>STEP_ORDER</name>
                          <defaultValue>1</defaultValue>
                        </hudson.model.StringParameterDefinition>
                """);
        for (String param : additionalParams) {
            if (!"EXECUTION_ID".equals(param) && !"STEP_ORDER".equals(param)) {
                sb.append("""
                        <hudson.model.StringParameterDefinition>
                          <name>%s</name>
                          <defaultValue></defaultValue>
                        </hudson.model.StringParameterDefinition>
                """.formatted(param));
            }
        }
        sb.append("""
                      </parameterDefinitions>
                    </hudson.model.ParametersDefinitionProperty>
                  </properties>
                  <definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition" plugin="workflow-cps">
                    <script><![CDATA[%s]]></script>
                    <sandbox>true</sandbox>
                  </definition>
                </flow-definition>
                """.formatted(scriptContent));
        return sb.toString();
    }

    /**
     * "deploy/playground-job-50" → "/job/deploy/job/playground-job-50" 변환.
     * 슬래시로 구분된 폴더/Job 이름을 Jenkins REST API 경로로 변환한다.
     */
    private String toJobPath(String jobName) {
        String[] parts = jobName.split("/");
        var sb = new StringBuilder();
        for (String part : parts) {
            sb.append("/job/").append(part);
        }
        return sb.toString();
    }

    private HttpHeaders buildHeaders() {
        JenkinsToolInfo tool = getTool();
        HttpHeaders headers = new HttpHeaders();
        if (tool.credential() != null && !tool.credential().isBlank()) {
            headers.setBasicAuth(tool.username(), tool.credential());
        }
        return headers;
    }
}
