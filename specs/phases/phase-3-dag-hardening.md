# Phase 3: DAG 엔진 고도화

> DAG 엔진을 프로덕션 수준 안정성으로 끌어올리는 10개 Feature와 GitLab/Nexus 인프라 확장을 완료한 단계이다.

## 목표

Phase 1에서 구현한 DAG 실행 엔진은 "정상 경로"만 처리했다. Phase 3에서는 크래시 복구, Job 재시도, 실패 정책, 부분 재시작 같은 "비정상 경로" 처리를 추가하여 엔진의 신뢰성을 높인다. 동시에 파라미터 주입과 실행 컨텍스트로 Job 간 데이터 전달을 가능하게 하고, 실행 이력 UI와 Connect 단일포트 전환으로 운영 편의성을 개선한다.

## 범위

- DAG 엔진: 설정 외부화, 크래시 복구, Job 재시도, 실패 정책, 부분 재시작
- 이벤트: DAG 완료 이벤트 Avro 발행
- 파라미터: Job별 파라미터 스키마 + 실행 컨텍스트(Job 간 데이터 전달)
- 프론트엔드: 실행 이력 페이지
- 인프라: Connect 단일포트 전환, 프리셋 관리 UI, GitLab CE K8s 설치, Nexus 리셋, Jenkins 파이프라인 개선

## 기간

2026-03-21 ~ 2026-03-22 완료

## 10개 Feature

### Feature 1: 설정 외부화

`DagExecutionCoordinator`에 하드코딩되어 있던 `maxConcurrentJobs(3)`, `webhookTimeoutMs`, `outboxPollIntervalMs` 등 매직 넘버를 `PipelineProperties`(@ConfigurationProperties)로 추출했다. `application.yml`의 `pipeline:` 블록에서 값을 관리하고, 환경마다 `application-{profile}.yml`로 오버라이드할 수 있다.

**Flyway**: 없음 (코드 변경만)

### Feature 2: 크래시 복구

애플리케이션이 비정상 종료되면 `pipeline_execution` 테이블에 `RUNNING` 상태인 레코드가 남는다. 재시작 시 `@PostConstruct`에서 RUNNING 상태 Execution을 조회하고 `DagExecutionCoordinator.resumeExecution()`으로 재개한다. 이미 `SUCCESS`인 Job은 건너뛰고, `PENDING`/`RUNNING` 상태였던 Job부터 다시 실행한다. 멱등성 보장을 위해 재개 전에 RUNNING Job을 PENDING으로 리셋하는 과정을 거친다.

**Flyway**: 없음 (코드 변경만)

### Feature 3: Job 재시도

Jenkins 일시 장애처럼 일과성 오류에 대응하기 위해 Exponential Backoff 재시도를 추가했다. `retry_count` 컬럼(V32)이 현재 재시도 횟수를 추적하며, 재시도 간격은 `2^retryCount`초(1초 → 2초 → 4초)이다. 최대 재시도 횟수는 `PipelineProperties.maxRetryCount`(기본 3)로 설정하며, 초과 시 최종 FAILED로 처리된다.

**Flyway**: V32 — `pipeline_job_execution.retry_count INT DEFAULT 0` 추가

### Feature 4: 실패 정책

파이프라인별로 Job 실패 시 나머지 처리 방식을 정의한다. `failure_policy` 컬럼(V33)에 세 가지 정책을 지원한다.

| 정책 | 동작 |
|------|------|
| `STOP_ALL` (기본값) | 실패 Job 외 나머지를 모두 CANCELLED로 전이 |
| `SKIP_DOWNSTREAM` | 실패 Job의 하위 의존성만 SKIPPED 처리, 독립 Job은 계속 실행 |
| `FAIL_FAST` | 즉시 전체 실행을 FAILED로 종료 |

`DagExecutionCoordinator.handleJobFailure()`가 정책에 따라 분기 처리한다.

**Flyway**: V33 — `pipeline_definition.failure_policy VARCHAR(32) DEFAULT 'STOP_ALL'` 추가

### Feature 5: 부분 재시작

파이프라인 실행이 FAILED 상태가 된 뒤, 실패 원인을 수정하고 처음부터 재실행하는 것은 비효율적이다. `POST /api/pipelines/{id}/executions/{execId}/restart` API는 이미 SUCCESS인 Job은 그대로 두고, FAILED/CANCELLED Job과 그 하위 의존성만 PENDING으로 리셋한 뒤 실행을 재개한다.

**Flyway**: 없음 (코드 변경만)

### Feature 6: DAG 이벤트

DAG 파이프라인 실행이 완료되면 외부 시스템이 구독할 수 있도록 `PIPELINE_EVT_COMPLETED` 토픽에 Avro 이벤트를 발행한다. `DagEventProducer`가 `EventPublisher`(Outbox 패턴)를 통해 발행하므로 DB 상태 전이와 이벤트 발행의 원자성이 보장된다. 이 이벤트는 Phase 4(워크플로우 도메인)에서 워크플로우가 파이프라인 완료를 감지하는 통로로 활용될 예정이다.

**Flyway**: 없음 (Avro 스키마 파일 추가, Outbox 이벤트 타입 상수 추가)

### Feature 7: 파라미터 주입

Job마다 실행 시 필요한 파라미터를 정의하고 주입한다. `parameter_schema_json`(V35, pipeline_job 테이블)으로 파라미터 목록과 기본값을 선언하고, 실행 요청 시 `parameters_json`으로 값을 전달한다. `ParameterResolver`가 Jenkins 스크립트 내 `${PARAM}` 플레이스홀더를 치환한다.

`EXECUTION_ID`, `STEP_ORDER` 같은 시스템 파라미터는 파라미터 스키마에 정의하지 않고 `buildWithParameters` 쿼리로 직접 전달한다. Jenkins 측에서는 파라미터를 선언하지 않아도 `env.EXECUTION_ID`로 접근할 수 있으므로 사용자에게 노출되지 않는다.

V34는 `pipeline_execution` 테이블에 파라미터 컬럼을 추가하는 초안이었으나, 검토 후 `pipeline_job` 레벨에서 관리하는 것이 더 적합하다고 판단하여 V35로 위치를 변경했다.

**Flyway**: V34(초안, 폐기) → V35 — `pipeline_job.parameter_schema_json TEXT` 추가

### Feature 8: 실행 컨텍스트 (Job 간 데이터 전달)

BUILD Job이 생성한 아티팩트 URL을 DEPLOY Job이 자동으로 받아야 한다. `context_json` 컬럼(V36)이 파이프라인 실행 단위의 공유 저장소 역할을 한다.

BUILD Job 완료 시 GAV 좌표(groupId, artifactId, version)와 프리셋의 Nexus URL(LIBRARY 카테고리 도구)을 조합하여 아티팩트 URL을 계산하고, `ARTIFACT_URL_{jobId}` 키로 `context_json`에 저장한다. DEPLOY Job이 `dependsOnJobIds`에 BUILD Job을 포함하면, `ParameterResolver`가 해당 BUILD의 `ARTIFACT_URL`을 자동으로 `${ARTIFACT_URL}` 플레이스홀더에 주입한다.

Nexus URL은 `configJson`이 아닌 Job의 프리셋에서 `LIBRARY` 카테고리 도구 URL을 조회한다. 이 참조 경로가 Phase 0에서 설계한 프리셋 추상화를 실제로 활용하는 첫 번째 사례이다(V37 시드 데이터에 LIBRARY 도구 등록).

DEPLOY Job이 BUILD에 의존하지 않고 단독 실행되는 경우에는 `parameter_schema_json`에 `ARTIFACT_URL`을 명시하여 사용자가 직접 입력한다. 다중 BUILD 의존성에서는 첫 번째 BUILD의 아티팩트 URL이 사용된다.

**Flyway**: V36 — `pipeline_execution.context_json TEXT`, V37 — LIBRARY 도구 시드 데이터

### Feature 9: 실행 이력 UI

전체 파이프라인 실행 이력을 한 페이지에서 조회하는 `/executions` 페이지를 추가했다. 상태별(SUCCESS/FAILED/RUNNING 등) 필터링과 파이프라인별 필터링을 지원한다.

`PipelineExecutionPanel`에서 사용하던 `ExecutionCard` 컴포넌트를 공유 컴포넌트로 추출하여 이력 페이지와 파이프라인 상세 페이지에서 동일한 카드 UI를 재사용한다.

**Flyway**: 없음 (프론트엔드 변경만)

### Feature 10: Connect 단일포트 전환

Redpanda Connect의 Jenkins webhook 수신 포트(4197)와 GitLab webhook 수신 포트(4196)를 공유 HTTP 포트(4195) 하나로 통합했다.

Streams 모드(`streams --chilled`)에서 `http_server` input의 `address` 필드는 무시되고, `-o` 플래그로 지정한 공유 HTTP 서버 포트에 모든 경로가 등록된다. 또한 `address`를 생략하면 스트림 이름이 경로 prefix로 붙는다. `jenkins-webhook` 스트림의 `/webhook/jenkins` 경로는 공유 서버에서 `/jenkins-webhook/webhook/jenkins`로 등록된다.

따라서 실제 webhook URL은 다음과 같다.
- Jenkins: `connect:4195/jenkins-webhook/webhook/jenkins`
- GitLab: `connect:4195/gitlab-webhook/webhook/gitlab`

K8s 이관 시 할당했던 NodePort 31196, 31197을 제거하고 31195 단일 NodePort만 남겼다.

**Flyway**: 없음 (Connect YAML 및 K8s 매니페스트 변경)

## Phase 3 후반 추가 작업

### 프리셋 관리 페이지 (/presets)

`SupportTool`과 `MiddlewarePreset`을 UI에서 CRUD할 수 있는 `/presets` 페이지를 추가했다. Phase 0에서 API만 구현했던 프리셋 관리를 프론트엔드에서 완성한 것이다. 도구 카테고리별 분류, 인증 타입 선택, 프리셋 구성 편집을 지원한다.

### GitLab CE K8s 설치

`rp-gitlab` 네임스페이스에 GitLab CE를 설치했다. NodePort 31480으로 접근하며, 초기 root 비밀번호 설정 후 dev-server control-plane에서 스케줄링 가능하도록 taint를 제거했다. GitLab은 리소스 요구량이 높으므로 3-node 중 control-plane에도 스케줄링을 허용해야 했다.

### Nexus 비밀번호 리셋 및 저장소 생성

Nexus 관리자 비밀번호를 `admin/playground1234!`로 리셋하고, Maven 릴리스 아티팩트 저장을 위한 `maven-releases` 저장소를 생성했다. BUILD Job이 빌드 산출물을 Nexus에 업로드하고 DEPLOY Job이 다운로드하는 E2E 흐름의 전제 조건이다.

### Jenkins BUILD/DEPLOY 파이프라인 Nexus curl 로직

BUILD Job의 Jenkins Pipeline 스크립트에 `mvn deploy` 후 Nexus REST API로 아티팩트 메타데이터를 조회하고 `ARTIFACT_URL`을 계산하는 로직을 추가했다(Rev.18). DEPLOY Job은 `curl`로 Nexus에서 아티팩트를 다운로드하는 스크립트를 사용한다.

E2E 검증 결과 BUILD→Nexus 업로드는 성공했으나, DEPLOY의 아티팩트 다운로드는 `JenkinsAdapter.buildConfigXml()`이 `EXECUTION_ID`/`STEP_ORDER`만 parameterDefinitions에 정의하여 `configJson`의 `ARTIFACT_URL` 키가 Jenkins에 전달되지 않는 문제가 남아 있다. 이는 Phase 1 버그 4의 근본 수정이 아직 미완임을 의미하며, 다음 단계에서 `buildConfigXml`의 동적 파라미터 정의 로직을 수정해야 한다.

### dev-server control-plane taint 제거

GitLab CE가 리소스 부족으로 스케줄링되지 않는 문제를 해결하기 위해 dev-server(control-plane)의 `node-role.kubernetes.io/control-plane:NoSchedule` taint를 제거했다. 3-node 클러스터에서 워커 노드 2대만으로는 GitLab의 메모리 요구량을 충족하지 못했다.

## Flyway 마이그레이션 요약

| 버전 | 내용 |
|------|------|
| V32 | `pipeline_job_execution.retry_count INT DEFAULT 0` 추가 |
| V33 | `pipeline_definition.failure_policy VARCHAR(32) DEFAULT 'STOP_ALL'` 추가 |
| V34 | 초안 (폐기) — `pipeline_execution` 파라미터 컬럼 (V35로 이동) |
| V35 | `pipeline_job.parameter_schema_json TEXT` 추가 |
| V36 | `pipeline_execution.context_json TEXT` 추가 |
| V37 | LIBRARY 카테고리 Nexus 도구 시드 데이터 삽입 |

## 회고

**잘한 것**

크래시 복구(Feature 2)와 Job 재시도(Feature 3)를 독립 Feature로 분리하여 순차 구현한 것이 효과적이었다. 두 기능이 개념적으로 연관되어 있지만 구현 레이어가 다르다(재시작 vs 실행 중 재시도). 분리해서 구현하면 각각의 엣지 케이스를 집중해서 처리할 수 있었다.

실행 컨텍스트(Feature 8)가 Phase 0의 프리셋 추상화를 실제로 활용하는 첫 번째 사례가 된 것이 설계의 일관성을 보여준다. Nexus URL을 `configJson`이 아닌 프리셋의 LIBRARY 카테고리에서 조회하는 방식은 도구 URL이 바뀌어도 Job 스크립트를 수정할 필요가 없다는 것을 의미한다.

Connect 단일포트 전환(Feature 10)은 Redpanda Connect Streams 모드의 동작 방식을 정확히 파악하고 있어야 가능했다. `address` 필드가 무시되고 스트림 이름이 prefix로 붙는 동작은 공식 문서에 명확하게 기술되어 있지 않아서 실험으로 확인해야 했다.

**개선할 것**

V34 마이그레이션을 만들었다가 V35로 대체한 것은 스키마 설계 검토가 부족했음을 보여준다. Flyway는 한 번 적용된 마이그레이션을 되돌릴 수 없으므로, 개발 환경이라도 V34를 먼저 롤백(flyway repair)한 뒤 V35를 적용하는 절차가 번거로웠다. 스키마 변경 전에 "이 컬럼이 어떤 테이블에 속해야 하는가"를 먼저 결정하는 습관이 필요하다.

`buildConfigXml`의 동적 파라미터 미지원 문제(Phase 1 버그 4)가 Phase 3 E2E에서도 완전히 해결되지 않았다. BUILD→DEPLOY 아티팩트 전달 흐름의 마지막 구간인 "Jenkins에 ARTIFACT_URL 파라미터 전달"이 막혀 있다. Feature 7(파라미터 주입)을 구현하는 과정에서 함께 수정했어야 했는데, 시스템 파라미터(EXECUTION_ID)와 사용자 파라미터(configJson)를 분리하는 리팩토링을 다음 단계의 첫 번째 작업으로 처리해야 한다.

GitLab, Nexus 인프라 작업이 Phase 3 후반에 몰려서 10개 Feature의 일관성이 흐트러졌다. 인프라 확장은 별도 Phase(또는 별도 트랙)로 분리하고, DAG 엔진 고도화 10개 Feature를 먼저 완결하는 순서가 더 깔끔했을 것이다.
