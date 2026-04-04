package com.study.playground.pipeline.config;

import com.study.playground.pipeline.job.domain.service.JenkinsPathBuilder;
import com.study.playground.pipeline.job.domain.service.JobService;
import com.study.playground.pipeline.pipeline.domain.service.PipelineService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PipelineDomainConfig {

    @Bean
    public JobService jobService() {
        return new JobService();
    }

    @Bean
    public JenkinsPathBuilder jenkinsPathBuilder() {
        return new JenkinsPathBuilder();
    }

    @Bean
    public PipelineService pipelineService() {
        return new PipelineService();
    }
}
