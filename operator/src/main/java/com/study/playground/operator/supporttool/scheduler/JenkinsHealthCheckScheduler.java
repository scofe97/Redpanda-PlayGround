package com.study.playground.operator.supporttool.scheduler;

import com.study.playground.operator.supporttool.service.JenkinsHealthCheckService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JenkinsHealthCheckScheduler {

    private final JenkinsHealthCheckService jenkinsHealthCheckService;

    @Scheduled(fixedDelayString = "${jenkins.health-check-interval-ms:60000}",
            initialDelayString = "${jenkins.health-check-initial-delay-ms:5000}")
    public void run() {
        jenkinsHealthCheckService.checkAllJenkinsInstances();
    }
}
