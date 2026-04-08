package com.study.playground.executor.execution.domain.port.out;

import com.study.playground.executor.execution.domain.model.BuildStatusResult;

/**
 * Jenkins 조회 out-port (슬롯 확인).
 * runner.infrastructure의 JenkinsClient가 구현한다.
 */
public interface JenkinsQueryPort {

    /**
     * 해당 Jenkins 인스턴스에서 즉시 빌드 실행이 가능한지 판단한다.
     * Jenkins 모드 자동 감지 (K8S Dynamic vs VM/정적).
     */
    boolean isImmediatelyExecutable(long jenkinsInstanceId);

    /** Jenkins 인스턴스 연결 가능 여부 (헬스체크). */
    boolean isReachable(long jenkinsInstanceId);

    /** support_tool.max_executors 조회. */
    int getMaxExecutors(long jenkinsInstanceId);

    /**
     * 해당 Jenkins Job의 다음 빌드번호를 조회한다.
     */
    int queryNextBuildNumber(long jenkinsInstanceId, String jenkinsJobPath);

    /**
     * 특정 빌드의 현재 상태를 조회한다.
     * Jenkins API /job/{path}/{buildNo}/api/json?tree=building,result 를 호출한다.
     *
     * @return NOT_FOUND(404), BUILDING(building=true), COMPLETED(building=false, result)
     */
    BuildStatusResult queryBuildStatus(long jenkinsInstanceId, String jenkinsJobPath, int buildNo);
}
