package com.study.playground.executor.execution.infrastructure.jenkins;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

/**
 * Jenkins API를 URI 첫 파라미터로 동적 호출하는 Feign client.
 * executor 런타임은 API token 기반 Basic Auth만 사용한다.
 */
@FeignClient(name = "jenkins", url = "http://placeholder")
public interface JenkinsFeignClient {

    /** queue 비어 있음 여부 확인용. */
    @GetMapping("/queue/api/json?tree=items[id]")
    String getQueueStatus(URI baseUri, @RequestHeader("Authorization") String auth);

    /** busyExecutors/totalExecutors 비교용. */
    @GetMapping("/computer/api/json?tree=busyExecutors,totalExecutors")
    String getComputerStatus(URI baseUri, @RequestHeader("Authorization") String auth);

    /** 동적 Pod Jenkins(K8S) 여부 판별에 필요한 메타데이터를 읽는다. */
    @GetMapping("/computer/api/json?tree=totalExecutors,computer[_class,assignedLabels[name]]")
    String getComputerClasses(URI baseUri, @RequestHeader("Authorization") String auth);

    /** build trigger 직전에 nextBuildNumber를 읽는다. */
    @GetMapping("/job/{jobPath}/api/json?tree=nextBuildNumber")
    String getJobInfo(URI baseUri,
                      @PathVariable("jobPath") String jobPath,
                      @RequestHeader("Authorization") String auth);

    /** JOB_ID 파라미터를 Jenkins job에 전달한다. */
    @RequestMapping(method = RequestMethod.POST, consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    void triggerBuild(URI uri,
                      @RequestHeader("Authorization") String auth,
                      @RequestBody String params);

    /** stale recovery용 build 상태 조회. */
    @GetMapping("/job/{jobPath}/{buildNo}/api/json?tree=building,result")
    String getBuildInfo(URI baseUri,
                        @PathVariable("jobPath") String jobPath,
                        @PathVariable("buildNo") int buildNo,
                        @RequestHeader("Authorization") String auth);
}
