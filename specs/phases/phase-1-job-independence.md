# Phase 1: Job 독립화 + DAG 파이프라인

> 티켓 종속 고정 3-스텝 파이프라인에서 벗어나 Job을 독립 엔티티로 분리하고, DAG 기반 병렬 실행 엔진을 도입한 핵심 전환점이다.

## 목표

기존 파이프라인은 티켓이 있어야만 실행 가능하고, 실행 순서가 Clone→Build→Deploy로 고정되어 있었다. Phase 1에서는 Job을 독립 엔티티로 승격시켜 파이프라인 없이도 단독 실행이 가능하게 하고, DAG(유향 비순환 그래프)로 의존성을 선언하면 `DagExecutionCoordinator`가 병렬 실행을 자동으로 조율하도록 구조를 전환한다.

## 범위

- `PipelineDefinition`: 티켓 종속 해제, Job DAG 컨테이너
- `PipelineJob`: 독립 CRUD 엔티티
- `pipeline_job_mapping`: Job ↔ Pipeline 다대다 관계 테이블
- `pipeline_job_dependency`: Job 간 의존 관계(DAG 엣지) 테이블
- `DagValidator`: 순환 참조 감지 (Kahn's Algorithm)
- `DagExecutionCoordinator`: 의존성 순서 기반 병렬 실행 (containerCap 3)
- Jenkins 동적 Job 생성: `JenkinsAdapter` + `JenkinsReconciler` + Outbox
- SAGA 보상: 역방향 위상 순서 보상 트랜잭션
- E2E 검증

## 기간

E2E 검증 완료 2026-03-20

## 구현 항목

- [x] `PipelineDefinition` — 티켓 종속 해제, DAG Job 컨테이너로 재정의
- [x] `PipelineJob` 독립 CRUD — 파이프라인과 무관하게 생성/수정/삭제 가능
- [x] `pipeline_job_mapping` 다대다 — 동일 Job을 여러 파이프라인에서 재사용
- [x] `pipeline_job_dependency` DAG 엣지 — `(pipeline_id, from_job_id, to_job_id)` 복합 키
- [x] `DagValidator` — Kahn's Algorithm 기반 순환 참조 탐지
- [x] `DagExecutionCoordinator` — 의존성 충족 순으로 병렬 실행, `containerCap` 3
- [x] Jenkins 동적 Job 생성 — `JenkinsAdapter`가 REST API로 Job 생성/실행
- [x] `JenkinsReconciler` — DB Job과 Jenkins Job 상태 주기적 동기화 (Outbox 패턴)
- [x] SAGA 보상 — 역방향 위상 순서로 완료된 Job 보상
- [x] `jenkins_script` 컬럼 (V25) — Job별 Jenkins Pipeline 스크립트 저장
- [x] `execution_order` nullable (V24) — DAG에서는 의존성 그래프가 순서 결정
- [x] `ticket_id` nullable (V30) — Job 단독 실행 허용
- [x] 의존성 파이프라인별 범위 지정 (V31) — 같은 Job이 파이프라인마다 다른 의존 관계 보유

## 해결한 기술 과제

**pipeline_step → pipeline_job_execution 리네이밍 (V20)**
기존 `pipeline_step` 테이블이 고정 3-스텝 개념을 함의하고 있었다. DAG 기반으로 전환하면서 `pipeline_job_execution`으로 리네이밍했다. MyBatis XML mapper와 Java 코드 전반에서 참조를 일괄 수정하는 작업이 필요했고, 레거시 티켓 기반 파이프라인과 신규 DAG 파이프라인이 같은 테이블을 공유하도록 하여 마이그레이션 비용을 줄였다.

**ticket_id nullable 전환 (V30)**
초기 설계에서 `pipeline_execution.ticket_id`는 NOT NULL이었다. DAG 파이프라인은 티켓 없이 직접 실행되므로 nullable로 변경했다. 이 변경이 연쇄적으로 SSE 이벤트 전송, `TicketStatusEventConsumer`, Avro 스키마의 `ticketId` 필드 타입(long → nullable union)에 영향을 주었고, Phase 2 버그 수정의 원인이 되었다.

**의존성 파이프라인별 범위 지정 (V31)**
초기에는 `pipeline_job_dependency`가 `(from_job_id, to_job_id)` 쌍만 저장했다. 그런데 같은 BuildJob이 파이프라인 A에서는 DeployJob에 의존하고, 파이프라인 B에서는 TestJob에 의존해야 하는 상황이 생겼다. `pipeline_id`를 복합 키에 포함시켜 의존성을 파이프라인 범위로 격리했다.

**Kahn's Algorithm 순환 탐지**
`DagValidator`는 진입 차수(in-degree) 기반의 Kahn's Algorithm으로 DAG를 검증한다. Job 저장 시점과 파이프라인 실행 시점 두 곳에서 검증하여, 저장 후 의존성 추가로 순환이 생기는 경우도 실행 전에 차단한다.

## E2E 검증 결과

| # | 시나리오 | 결과 |
|---|----------|------|
| 1 | Job 생성 → Jenkins Job 자동 등록 | PASS |
| 2 | Job 단독 실행 (파이프라인 없이) | PASS |
| 3 | Pipeline 생성 + Job 매핑 | PASS |
| 4 | Pipeline DAG 실행 (병렬 Job 처리) | PASS |
| 5 | SAGA 보상 (중간 Job 실패 시 역순 보상) | PASS |
| 6 | 장애 복구 (앱 재시작 후 실행 재개) | 미검증 — Phase 3에서 구현 |
| 7 | 프론트엔드 DAG 시각화 | 미검증 — Phase 3에서 완성 |

## 발견된 버그 5건

E2E 검증 과정에서 발견되어 Phase 1 내에서 수정한 버그들이다.

1. **ticket_id NOT NULL 제약**: V30 마이그레이션 전 DAG 실행 시 DB 제약 위반 에러 발생
2. **Avro ticketId long 타입**: ticketId가 nullable이 되었는데 Avro 스키마가 `long` 단일 타입으로 남아 있어 직렬화 실패
3. **setTicketId(0L) 기본값**: Avro 스키마 수정 전 임시 처방으로 0L을 넣던 코드가 남아 있어 혼란 유발
4. **buildConfigXml parameters 누락**: `JenkinsAdapter.buildConfigXml()`이 `EXECUTION_ID`/`STEP_ORDER`만 parameterDefinitions에 정의하고, configJson의 커스텀 파라미터는 포함하지 않아서 Jenkins에 사용자 정의 파라미터가 전달되지 않음 — Phase 3 파라미터 주입 기능에서 근본 수정
5. **JenkinsWebhookPayload snake_case**: Spring Boot의 기본 Jackson 설정이 camelCase를 기대하는데 Jenkins가 snake_case로 콜백을 보내어 역직렬화 실패

## Flyway 마이그레이션

| 버전 | 내용 |
|------|------|
| V20 | `pipeline_step` → `pipeline_job_execution` 리네이밍 |
| V21 | `pipeline_job` 독립 테이블 생성 |
| V22 | `pipeline_definition` 테이블 생성 (티켓 종속 없음) |
| V23 | `pipeline_job_mapping` 다대다 관계 테이블 생성 |
| V24 | `pipeline_job_execution.execution_order` nullable 변경 |
| V25 | `pipeline_job.jenkins_script` 컬럼 추가 |
| V26 | `pipeline_job_dependency` DAG 엣지 테이블 생성 |
| V27 | Jenkins Outbox 이벤트 타입 추가 |
| V28 | `pipeline_execution` DAG 실행 지원 컬럼 추가 |
| V29 | 시드 데이터 (기본 Job 템플릿) |
| V30 | `pipeline_execution.ticket_id` nullable 변경 |
| V31 | `pipeline_job_dependency`에 `pipeline_id` 복합 키 추가 |

## 회고

**잘한 것**

Job을 파이프라인에서 완전히 분리한 설계 결정이 이 프로젝트 전체에서 가장 중요한 전환점이었다. "같은 빌드 Job을 여러 파이프라인에서 재사용한다"는 요구사항을 `pipeline_job_mapping` 다대다 관계로 자연스럽게 표현할 수 있었다. TPS 실무에서 빌드/배포/테스트 Job이 독립적으로 관리되는 구조와 정확히 일치한다.

SAGA 보상을 역방향 위상 순서로 구현한 것도 올바른 접근이었다. DAG 실행에서 완료 역순으로 보상하면 의존 관계가 자연스럽게 유지된다. 예를 들어 Deploy→Build→Clone 순서로 완료된 경우 Deploy 보상 → Build 보상 → Clone 보상 순서가 된다.

**개선할 것**

`buildConfigXml`의 파라미터 처리가 불완전한 채로 E2E를 통과했다. EXECUTION_ID/STEP_ORDER는 시스템 파라미터이고, configJson의 사용자 파라미터는 별도 처리가 필요하다는 것을 초기 설계에서 명확히 분리했다면 Phase 3에서의 재작업을 줄일 수 있었다.

의존성을 파이프라인별로 범위 지정(V31)하는 마이그레이션이 뒤늦게 추가되었다. Job 독립화 설계 시점에 이미 "같은 Job이 파이프라인마다 다른 의존 관계를 가질 수 있다"는 요구사항을 예측하고 `pipeline_id`를 처음부터 포함했더라면 스키마 변경 비용이 없었을 것이다.
