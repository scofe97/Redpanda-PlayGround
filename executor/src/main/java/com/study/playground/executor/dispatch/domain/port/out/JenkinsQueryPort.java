package com.study.playground.executor.dispatch.domain.port.out;

/**
 * Jenkins 조회 out-port (슬롯 확인 + 빌드번호 + 인스턴스 매핑 통합).
 * runner.infrastructure의 JenkinsClient가 구현한다.
 */
public interface JenkinsQueryPort {

    /**
     * 해당 Jenkins 인스턴스에서 즉시 빌드 실행이 가능한지 판단한다.
     * Jenkins 모드 자동 감지 (K8S Dynamic vs VM/정적).
     */
    boolean isImmediatelyExecutable(long jenkinsInstanceId);

    /**
     * 해당 Jenkins 인스턴스의 Job에 대한 다음 빌드번호를 조회한다.
     * Jenkins API: GET /{jobPath}/api/json?tree=nextBuildNumber
     */
    int queryNextBuildNumber(long jenkinsInstanceId, String jenkinsJobPath);

    /**
     * jobId로부터 실행할 Jenkins 인스턴스 ID를 조회한다.
     * Job → Preset → PurposeEntry(CI_CD_TOOL) → SupportTool.id
     */
    long resolveJenkinsInstance(String jobId);
}
