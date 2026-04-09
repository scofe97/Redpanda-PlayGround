# Executor Use Cases

`executor` 모듈의 현재 구현 기준 유스케이스 문서 모음이다.

이 디렉토리는 `spec.md`의 설계 의도보다 실제 live 코드가 어떻게 동작하는지 설명하는 데 초점을 둔다.

## 문서 목록

- `01-receive-job.md`: operator가 보낸 dispatch command를 수신해 `execution_job`에 적재하는 흐름
- `02-evaluate-dispatch.md`: 대기 Job을 Jenkins 인스턴스별로 평가하고 실행 대상을 QUEUED로 전환하는 흐름
- `03-execute-job.md`: QUEUED Job을 Jenkins에 실제로 트리거하고 SUBMITTED로 전환하는 흐름
- `04-handle-build-started.md`: Jenkins 시작 이벤트를 받아 RUNNING으로 전환하고 operator에 알리는 흐름
- `05-handle-build-completed.md`: Jenkins 종료 이벤트를 받아 터미널 상태 반영, 로그 저장, operator notify를 수행하는 흐름
- `06-stale-job-recovery.md`: 웹훅 유실이나 메시지 유실에 대비해 SUBMITTED, RUNNING, QUEUED stale Job을 복구하는 흐름

각 문서에는 Mermaid 흐름도와 대응 HTML 시각화 링크가 포함돼 있다.

## 상태 흐름 요약

`PENDING -> QUEUED -> SUBMITTED -> RUNNING -> TERMINAL`

- `PENDING`: 수신 완료, 디스패치 대기
- `QUEUED`: 실행 대상으로 선정됨. 내부 execute command 발행 완료
- `SUBMITTED`: Jenkins 트리거 성공. 아직 시작 이벤트를 받지 못한 상태
- `RUNNING`: Jenkins 시작 이벤트 확인
- `TERMINAL`: `SUCCESS`, `FAILURE`, `UNSTABLE`, `ABORTED`, `NOT_BUILT`, `NOT_EXECUTED`

복구 경로에서는 `QUEUED -> PENDING`, `SUBMITTED -> PENDING`, `RUNNING -> PENDING`도 가능하다.

## 핵심 진입점

- Kafka inbound:
  - `JobDispatchConsumer`
  - `JobExecuteConsumer`
  - `JobStartedConsumer`
  - `JobCompletedConsumer`
- Scheduler:
  - `DispatchScheduler`
  - `StaleJobRecoveryScheduler`
- Read API:
  - `DispatchController`

## 읽는 순서

1. `01-receive-job.md`
2. `02-evaluate-dispatch.md`
3. `03-execute-job.md`
4. `04-handle-build-started.md`
5. `05-handle-build-completed.md`
6. `06-stale-job-recovery.md`
