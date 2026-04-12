package com.study.playground.executor.execution.domain.port.out;

import com.study.playground.executor.execution.domain.model.BuildStatusResult;

/**
 * Jenkins 런타임 상태를 조회하는 out-port.
 * 구현체는 operator.support_tool의 health/api token 정보를 기반으로 Jenkins를 읽는다.
 */
public interface JenkinsQueryPort {

    /** queue/executor 상태를 함께 봐서 즉시 실행 가능한 슬롯 수를 반환한다. */
    int isImmediatelyExecutable(long jenkinsInstanceId);
    
    /** 기존 포트 호환용 메서드로, 현재 구현에서는 health gate와 동일 의미를 가진다. */
    boolean isReachable(long jenkinsInstanceId);

    /** operator가 마지막으로 기록한 health 상태와 freshness를 함께 확인한다. */
    boolean isHealthy(long jenkinsInstanceId);

    /** build trigger 전에 Jenkins job의 nextBuildNumber를 읽는다. */
    int queryNextBuildNumber(long jenkinsInstanceId, String jenkinsJobPath);

    /** stale recovery 용으로 특정 build의 실행/완료 상태를 조회한다. */
    BuildStatusResult queryBuildStatus(long jenkinsInstanceId, String jenkinsJobPath, int buildNo);
}
