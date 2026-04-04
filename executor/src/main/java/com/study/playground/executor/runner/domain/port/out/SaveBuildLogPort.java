package com.study.playground.executor.runner.domain.port.out;

/**
 * 빌드 로그를 파일로 적재하는 out-port.
 * 실패해도 프로세스를 중단하지 않으며, 성공 여부만 반환한다.
 *
 * @see com.study.playground.executor.runner.infrastructure.filesystem.BuildLogFileWriter
 */
public interface SaveBuildLogPort {

    /**
     * 로그 내용을 파일로 저장한다.
     * 저장 경로: {logPath}/{directoryPath}/{jobExcnId}_0
     *
     * @param directoryPath 로그 디렉토리 경로 (Jenkins 경로에서 파생)
     * @param jobExcnId     파일명에 사용할 Job 실행 식별자
     * @param logContent    저장할 로그 내용
     * @return 저장 성공 여부 (실패해도 예외를 던지지 않음)
     */
    boolean save(String directoryPath, String jobExcnId, String logContent);
}
