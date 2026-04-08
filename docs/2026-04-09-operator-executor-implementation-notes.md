# 2026-04-09 Operator/Executor 구현 정리
---
> `operator` 모듈 통합을 완료하고, `executor`의 디스패치 및 재시도 흐름을 실인프라 기준으로 안정화했다.

## 배경
기존에는 `app`, `pipeline`, `operator-stub`이 분리된 상태였고, executor는 인스턴스별 슬롯 계산과 재시도 경로에서 여러 병목을 가지고 있었다. 오늘 작업은 이 구조를 `operator` 중심으로 통합하고, executor가 GCP 환경에서 더 예측 가능하게 동작하도록 정리하는 데 초점을 맞췄다.

## 주요 변경
- `app`, `pipeline`, `operator-stub`을 `operator`로 통합하고, 실행 엔트리포인트와 설정, 마이그레이션, 테스트를 `operator` 기준으로 재배치했다.
- executor의 디스패치 로직을 인스턴스 기준 가용 슬롯 계산 방식으로 정리하고, `support_tool.max_executors`와 DB 상 활성 Job 수를 함께 보도록 바꿨다.
- `QUEUED` 정체를 막기 위한 stale recovery를 추가하고, 스케줄러 초기 지연과 thread pool 분리를 넣어 outbox/dispatch/recovery가 서로 막지 않도록 했다.
- Kafka listener group id를 설정 기반으로 바꾸고, retry topic 재발행이 Avro command payload를 처리할 수 있도록 producer 경로를 보강했다.
- `ReceiveJobService`의 중복 insert 레이스를 방어해, 같은 Job이 동시에 들어와도 PK 충돌로 전체 흐름이 깨지지 않도록 했다.

## 테스트 및 검증
- `./gradlew :executor:compileJava :executor:compileTestJava`
- `./gradlew :executor:test`
- `./gradlew :operator:compileJava`
- `./gradlew :operator:test`
- `./gradlew :operator:compileJava :executor:compileJava`

위 검증은 모두 통과했다. 특히 `operator`의 ArchUnit 규칙은 통합 후 패키지 구조(`operatorjob`, `pipeline` persistence mapper 배치, legacy ticket 부재)에 맞게 정리한 뒤 다시 통과시켰다.

## 남은 리스크
- `operator` 통합으로 기존 문서들에 `app`/`pipeline`/`operator-stub` 전제나 경로가 남아 있다.
- `README.md`, `PROJECT_SPEC.md`, `docs/README.md`, `executor/docs/*`는 아직 예전 모듈명을 일부 포함한다.
- GCP 인프라 검증은 코드 컴파일과 테스트 기준으로는 통과했지만, 실제 서버 재기동 후에는 JVM/Kafka/Redpanda 상태를 한 번 더 확인하는 편이 안전하다.

## 이번 턴에서 추가 반영한 정리
- 기존 `app/`, `pipeline/`, `operator-stub/` 디렉토리를 실제로 삭제했다.
- 중앙 진입 문서에 현재 구조와 historical 문서 여부를 구분하는 안내를 추가했다.
- `executor` 스펙 문서와 E2E 결과 문서에 live 코드와의 차이를 경고로 명시했다.
