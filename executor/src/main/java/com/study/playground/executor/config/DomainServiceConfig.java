package com.study.playground.executor.config;

import com.study.playground.executor.dispatch.domain.service.DispatchService;
import com.study.playground.executor.runner.domain.service.BuildLifecycleService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 순수 도메인 서비스를 Spring Bean으로 등록한다.
 * 도메인 서비스 자체는 Spring 어노테이션을 갖지 않으므로 여기서 등록한다.
 */
@Configuration
public class DomainServiceConfig {

    @Bean
    public DispatchService dispatchService() {
        return new DispatchService();
    }

    @Bean
    public BuildLifecycleService buildLifecycleService() {
        return new BuildLifecycleService();
    }
}
