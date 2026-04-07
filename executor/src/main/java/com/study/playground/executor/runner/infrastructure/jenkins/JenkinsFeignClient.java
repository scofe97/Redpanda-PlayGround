package com.study.playground.executor.runner.infrastructure.jenkins;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

/**
 * Jenkins REST API Feign 클라이언트.
 * URL은 Jenkins 인스턴스마다 다르므로 각 메서드의 첫 파라미터 URI로 동적 지정한다.
 * 인증은 API Token 기반 Basic Auth (CSRF crumb 불필요).
 */
@FeignClient(name = "jenkins", url = "http://placeholder")
public interface JenkinsFeignClient {

    /**
     * 빌드 대기열(Queue) 조회.
     * 대기 중인 빌드가 있는지 확인하여 즉시 실행 가능 여부를 판단한다.
     * items 배열이 비어있으면 대기열이 비어있는 것.
     */
    @GetMapping("/queue/api/json?tree=items[id]")
    String getQueueStatus(URI baseUri, @RequestHeader("Authorization") String auth);

    /**
     * Executor 상태 조회.
     * busyExecutors < totalExecutors이면 유휴 Executor가 존재하여 빌드 수용 가능.
     */
    @GetMapping("/computer/api/json?tree=busyExecutors,totalExecutors")
    String getComputerStatus(URI baseUri, @RequestHeader("Authorization") String auth);

    /**
     * 노드 클래스 목록 조회.
     * _class에 "kubernetes"가 포함된 노드가 있으면 K8s 동적 에이전트 환경으로 판단,
     * 대기열/Executor 체크를 건너뛴다 (Pod가 동적 생성되므로 항상 수용 가능).
     */
    @GetMapping("/computer/api/json?tree=computer[_class]")
    String getComputerClasses(URI baseUri, @RequestHeader("Authorization") String auth);

    /**
     * Job의 다음 빌드 번호 조회.
     */
    @GetMapping("/job/{jobPath}/api/json?tree=nextBuildNumber")
    String getJobInfo(URI baseUri, @PathVariable("jobPath") String jobPath
            , @RequestHeader("Authorization") String auth);

    /**
     * CSRF crumb 발급.
     * Password 인증 시 POST 요청에 crumb 헤더가 필요하다.
     */
    @GetMapping("/crumbIssuer/api/json")
    String getCrumb(URI baseUri, @RequestHeader("Authorization") String auth);

    /**
     * 파라미터 빌드 트리거.
     * EXECUTION_JOB_ID 파라미터를 전달하여 Jenkins Job을 실행한다.
     * 성공 시 HTTP 201을 반환하며 응답 본문은 없다.
     */
    @PostMapping(value = "/job/{jobPath}/buildWithParameters"
            , consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    void triggerBuild(URI baseUri, @PathVariable("jobPath") String jobPath
            , @RequestHeader("Authorization") String auth
            , @RequestHeader("Jenkins-Crumb") String crumb
            , @RequestBody String params);
}
