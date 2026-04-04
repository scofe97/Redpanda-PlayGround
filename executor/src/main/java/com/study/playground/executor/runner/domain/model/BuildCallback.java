package com.study.playground.executor.runner.domain.model;

/**
 * Jenkins JobListener가 rpk produce로 전달하는 콜백 데이터.
 * Jenkins는 jobExcnId를 모르므로 경로 + buildNo로 매칭한다.
 *
 * @param jenkinsPath Jenkins 파이프라인 경로 (예: "PROJECT-A/BUILD/JOB-001")
 * @param buildNo     Jenkins 빌드 번호
 * @param result      빌드 결과 (종료 시에만, 시작 시 null). SUCCESS/FAILURE/ABORTED 등
 * @param logContent  빌드 로그 내용 (종료 시에만, 시작 시 null)
 */
public record BuildCallback(
        String jenkinsPath
        , int buildNo
        , String result
        , String logContent
) {

    /**
     * Jenkins 경로에서 jobId를 추출한다.
     * 경로 구조: {project}/{purpose}/{jobId}
     * 마지막 세그먼트가 jobId이다.
     */
    public String extractJobId() {
        String[] segments = jenkinsPath.split("/");
        return segments[segments.length - 1];
    }

    /**
     * 로그 저장용 경로를 구성한다.
     * TPS 차용: {project}/{purpose}/{jobId}
     */
    public String toLogDirectoryPath() {
        return jenkinsPath.replace("/job/", "/").replace("/job", "");
    }

    /**
     * 시작 이벤트용 팩토리.
     */
    public static BuildCallback started(String jenkinsPath, int buildNo) {
        return new BuildCallback(jenkinsPath, buildNo, null, null);
    }

    /**
     * 종료 이벤트용 팩토리.
     */
    public static BuildCallback completed(String jenkinsPath, int buildNo, String result, String logContent) {
        return new BuildCallback(jenkinsPath, buildNo, result, logContent);
    }
}
