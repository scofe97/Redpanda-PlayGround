# 데이터베이스 아키텍처

## 1. DB 환경

| 항목 | 값 |
|------|-----|
| 엔진 | PostgreSQL 16-alpine |
| 설치 방식 | Bitnami Helm Chart |
| 네임스페이스 | `rp-oss` |
| K8s NodePort | 30275 |
| 마이그레이션 도구 | Flyway (Spring Boot 관리) |
| 마이그레이션 경로 | `app/src/main/resources/db/migration/` |

앱 기동 시 Flyway가 `db/migration/` 디렉토리를 스캔하여 아직 적용되지 않은 버전을 순서대로 실행한다. 초기 DB 확장 설정(`uuid-ossp`)은 `infra/docker/shared/init-db/01-init.sql`에서 별도 수행한다.

---

## 2. Flyway 마이그레이션 이력 (V1–V37)

| 버전 | 파일명 | 설명 | Phase |
|------|--------|------|-------|
| V1 | `V1__create_ticket.sql` | ticket, ticket_source 테이블 생성. 티켓과 배포 소스(GIT/NEXUS/HARBOR)의 기반 | 초기 |
| V2 | `V2__create_pipeline.sql` | pipeline_execution, pipeline_step 테이블 생성. 파이프라인 실행 이력 기반 | 초기 |
| V3 | `V3__create_outbox_event.sql` | outbox_event 테이블 생성. Transactional Outbox 패턴 적용 | 초기 |
| V4 | `V4__create_processed_event.sql` | processed_event 테이블 생성. Idempotent Consumer를 위한 멱등성 보장 | 초기 |
| V5 | `V5__create_support_tool.sql` | support_tool 테이블 생성. 외부 도구(Jenkins/GitLab/Nexus/Registry) 연결 정보 | 초기 |
| V6 | `V6__add_correlation_id_to_outbox.sql` | outbox_event에 correlation_id 컬럼 추가 | 초기 |
| V7 | `V7__insert_default_support_tools.sql` | support_tool 기본 시드 데이터 삽입 | 초기 |
| V8 | `V8__expand_pipeline_step_name_length.sql` | pipeline_step.step_name 컬럼 길이 확장 | 초기 |
| V9 | `V9__create_connector_config.sql` | connector_config 테이블 생성. 동적 Redpanda Connect 스트림 설정 영속화 | 초기 |
| V10 | `V10__alter_processed_event_to_event_id.sql` | processed_event PK를 event_id 기반으로 변경 | 초기 |
| V11 | `V11__add_trace_parent_to_outbox.sql` | outbox_event에 trace_parent 컬럼 추가. OpenTelemetry 분산 추적 연계 | 초기 |
| V12 | `V12__add_auth_type_to_support_tool.sql` | support_tool에 auth_type(BASIC/BEARER/PRIVATE_TOKEN/NONE) 추가 | Phase 0 |
| V13 | `V13__add_category_implementation_to_support_tool.sql` | support_tool에 category + implementation 컬럼 추가. CI_CD_TOOL/VCS/LIBRARY/CONTAINER_REGISTRY 역할 분리 | Phase 0 |
| V14 | `V14__create_middleware_preset.sql` | middleware_preset, preset_entry 테이블 생성. 역할별 도구 조합을 묶는 단위 | Phase 0 |
| V15 | `V15__drop_tool_type_column.sql` | support_tool.tool_type 컬럼 제거. category+implementation으로 대체 | Phase 0 |
| V16 | `V16__add_trace_parent_to_pipeline_execution.sql` | pipeline_execution에 trace_parent 추가. 분산 추적 컨텍스트 전파 | Phase 0 |
| V17 | `V17__create_pipeline_definition.sql` | pipeline_definition 테이블 생성. DAG Job 구성의 설계도 | Phase 1 |
| V18 | `V18__create_pipeline_job.sql` | pipeline_job 테이블 생성. DAG의 노드를 정의하는 독립 엔티티 | Phase 1 |
| V19 | `V19__add_job_id_to_pipeline_step.sql` | pipeline_step에 job_id 컬럼 추가. 실행 스텝과 Job 정의를 연결 | Phase 1 |
| V20 | `V20__rename_pipeline_step_to_job_execution.sql` | pipeline_step → pipeline_job_execution 리네이밍. Step 개념에서 Job 실행 추적으로 전환 | Phase 1 |
| V21 | `V21__add_missing_indexes.sql` | 조회 성능을 위한 누락 인덱스 추가 | Phase 1 |
| V22 | `V22__add_next_retry_at_to_outbox.sql` | outbox_event에 next_retry_at 컬럼 추가. Outbox 재시도 스케줄링 | Phase 1 |
| V23 | `V23__decouple_job_from_pipeline.sql` | Job을 파이프라인에서 분리. pipeline_job_mapping(다대다), pipeline_job_dependency(의존 관계) 테이블 생성 | Phase 1 |
| V24 | `V24__make_execution_order_nullable.sql` | pipeline_job_execution.execution_order nullable 허용. DAG에서는 의존성 그래프가 순서를 결정 | Phase 1 |
| V25 | `V25__add_jenkins_script_and_status.sql` | pipeline_job에 jenkins_script, jenkins_status 컬럼 추가. Job별 Jenkins Pipeline 스크립트 저장 | Phase 1 |
| V26 | `V26__rename_preset_team_b_to_default.sql` | 기본 프리셋 이름 정규화 (team_b → default) | Phase 1 |
| V27 | `V27__remove_gocd_support_tool.sql` | GoCD support_tool 시드 데이터 제거 | Phase 1 |
| V28 | `V28__rename_preset_to_default.sql` | 프리셋명 추가 정규화 | Phase 1 |
| V29 | `V29__make_jenkins_status_nullable.sql` | pipeline_job.jenkins_status nullable 허용 | Phase 1 |
| V30 | `V30__make_ticket_id_nullable.sql` | pipeline_execution.ticket_id nullable 허용. DAG 파이프라인은 티켓 없이 단독 실행 가능 | Phase 1 |
| V31 | `V31__scope_dependency_per_pipeline.sql` | pipeline_job_dependency에 definition_id FK 추가. 의존 관계를 파이프라인별로 범위 지정 | Phase 1 |
| V32 | `V32__add_retry_count_to_job_execution.sql` | pipeline_job_execution에 retry_count 추가. Exponential Backoff 재시도 횟수 추적 | Phase 3 |
| V33 | `V33__add_failure_policy_to_pipeline_definition.sql` | pipeline_definition에 failure_policy 추가. STOP_ALL/SKIP_DOWNSTREAM/FAIL_FAST | Phase 3 |
| V34 | `V34__add_pipeline_parameters.sql` | pipeline_execution에 parameter_schema_json 추가 (V35에서 job으로 이동됨) | Phase 3 |
| V35 | `V35__move_parameter_schema_to_job.sql` | parameter_schema_json을 pipeline_definition에서 pipeline_job으로 이동. Job별 파라미터 스키마 관리 | Phase 3 |
| V36 | `V36__add_context_json_to_execution.sql` | pipeline_execution에 context_json 추가. Job 간 공유 데이터 전달 (예: BUILD→DEPLOY 아티팩트 URL) | Phase 3 |
| V37 | `V37__seed_preset_entries.sql` | preset_entry 시드 데이터 삽입. Nexus LIBRARY 카테고리 도구 URL 연동 기반 | Phase 3 |

---

## 3. 주요 테이블 목록

| 테이블 | 도메인 | 설명 |
|--------|--------|------|
| `ticket` | ticket | 배포 티켓. 상태는 DRAFT/READY/DEPLOYING/DEPLOYED/FAILED |
| `ticket_source` | ticket | 티켓의 배포 소스. GIT(repo_url/branch), NEXUS(artifact_coordinate), HARBOR(image_name)를 다형적으로 관리 |
| `pipeline_definition` | pipeline-dag | DAG Job 구성의 설계도. 동일 정의로 여러 번 실행 가능. failure_policy, parameter_schema_json 보유 |
| `pipeline_job` | pipeline-dag | DAG의 노드. 파이프라인에서 독립적으로 CRUD 가능한 단위. jenkins_script, preset_id, parameter_schema_json 보유 |
| `pipeline_job_mapping` | pipeline-dag | pipeline_definition ↔ pipeline_job 다대다 관계 테이블 |
| `pipeline_job_dependency` | pipeline-dag | Job 간 의존 관계. definition_id로 파이프라인별 범위 지정 (V31) |
| `pipeline_execution` | pipeline | 파이프라인 실행 이력. ticket_id nullable (DAG 단독 실행 지원). context_json으로 Job 간 데이터 공유 |
| `pipeline_job_execution` | pipeline | Job 단위 실행 추적. retry_count, parameters_json 보유. 기존 pipeline_step에서 리네이밍 (V20) |
| `support_tool` | supporttool | 외부 도구(Jenkins/GitLab/Nexus/Harbor) URL·인증 정보. auth_type(BASIC/BEARER/PRIVATE_TOKEN/NONE)으로 인증 방식 명시 |
| `middleware_preset` | preset | 역할별 도구 조합 단위. 파이프라인이 프리셋을 참조하면 프리셋 교체만으로 모든 파이프라인의 도구가 갱신됨 |
| `preset_entry` | preset | 프리셋 내 개별 도구 항목. category(CI_CD_TOOL/VCS/LIBRARY/CONTAINER_REGISTRY)별로 support_tool을 할당 |
| `connector_config` | connector | 동적으로 생성된 Redpanda Connect 스트림 설정을 영속화. 앱 기동 시 Connect Streams API로 복원 |
| `outbox_event` | kafka | Transactional Outbox 패턴 핵심 테이블. payload는 BYTEA(Avro 직렬화). PENDING 상태 부분 인덱스로 폴러 성능 최적화 |
| `processed_event` | kafka | Idempotent Consumer를 위한 멱등성 보장. event_id 단일 PK로 중복 수신 차단 |
| `audit_log` | audit | 감사 이벤트 이력. 도메인 작업의 추적 가능성 확보 |

---

## 4. ERD 관계 요약

### ticket 도메인

`ticket`은 1:N으로 `ticket_source`를 가진다. 소스 유형(GIT/NEXUS/HARBOR)에 따라 사용되는 컬럼이 달라지는 다형적 설계이다. `ticket`은 0..N의 `pipeline_execution`을 가질 수 있으며, V30 이후 `ticket_id`가 nullable이므로 티켓 없이 직접 파이프라인을 실행하는 것도 가능하다.

### pipeline 도메인

`pipeline_definition`은 `pipeline_execution`과 1:N 관계를 가진다. 동일 정의를 여러 번 실행할 수 있어 설계와 실행이 분리된다. `pipeline_execution`은 1:N으로 `pipeline_job_execution`을 가지며, 각 `pipeline_job_execution`은 `pipeline_job`을 참조하여 어떤 Job 정의에서 비롯된 실행인지 추적한다.

### pipeline_definition ↔ pipeline_job (다대다)

`pipeline_definition`과 `pipeline_job`은 `pipeline_job_mapping`을 통해 다대다 관계를 맺는다. Job은 파이프라인에서 독립적으로 정의되므로, 동일한 빌드 Job을 여러 파이프라인에서 재사용할 수 있다.

### 의존 관계 범위 지정 (V31)

`pipeline_job_dependency`는 `job_id`(의존하는 Job)와 `depends_on_job_id`(선행 Job)뿐 아니라 `definition_id`(pipeline_definition FK)도 가진다. 같은 Job 쌍이라도 파이프라인 A와 파이프라인 B에서 서로 다른 의존 관계를 가질 수 있어야 하기 때문에, 의존 관계는 파이프라인별로 범위가 지정된다.

### preset 도메인

`pipeline_definition`은 `preset_id` FK로 `middleware_preset`을 참조한다. `pipeline_job`도 독립적으로 `preset_id`를 가진다(V23). `middleware_preset`은 1:N으로 `preset_entry`를 가지며, 각 항목은 category별로 `support_tool`을 하나씩 할당한다.

### connector 도메인

`connector_config`는 `tool_id` FK로 `support_tool`을 참조하며, `ON DELETE CASCADE`가 설정되어 도구 삭제 시 관련 스트림 설정도 자동 정리된다.

---

## 5. Phase 3 추가 컬럼

Phase 3(DAG 엔진 고도화)에서 기존 테이블에 다음 컬럼들이 추가되었다.

| 테이블 | 컬럼 | 마이그레이션 | 설명 |
|--------|------|-------------|------|
| `pipeline_job_execution` | `retry_count` | V32 | 현재까지 재시도한 횟수. 최대 3회. Exponential Backoff(1초→2초→4초) 간격 관리에 사용 |
| `pipeline_definition` | `failure_policy` | V33 | 파이프라인 실패 시 대응 전략. `STOP_ALL`(기본), `SKIP_DOWNSTREAM`, `FAIL_FAST` 중 택일 |
| `pipeline_definition` | `parameter_schema_json` | V34 | 파이프라인 레벨 파라미터 스키마 (V35에서 pipeline_job으로 이동됨, 현재 미사용) |
| `pipeline_job` | `parameter_schema_json` | V35 | Job별 파라미터 정의. `ParameterResolver`가 Jenkins 스크립트의 `${PARAM}` 플레이스홀더를 치환하는 기준 |
| `pipeline_job_execution` | `parameters_json` | V35 | 실행 시 사용자가 전달한 파라미터 값. `parameter_schema_json`의 스키마에 따라 검증됨 |
| `pipeline_execution` | `context_json` | V36 | Job 간 공유 데이터 저장소. BUILD Job 완료 시 `ARTIFACT_URL_{jobId}` 키로 아티팩트 URL을 기록하고, DEPLOY Job이 의존성을 통해 자동으로 값을 주입받음 |

### context_json 동작 방식

BUILD Job이 완료되면 `DagExecutionCoordinator`가 해당 Job의 preset에서 LIBRARY 카테고리 도구(Nexus) URL을 조회하고, GAV 좌표와 결합하여 아티팩트 URL을 구성한 뒤 `context_json`의 `ARTIFACT_URL_{jobId}` 키에 저장한다. DEPLOY Job이 `dependsOnJobIds`로 BUILD Job을 선행으로 선언하면, `ParameterResolver`가 `context_json`에서 해당 BUILD의 `ARTIFACT_URL`을 찾아 `${ARTIFACT_URL}` 플레이스홀더에 자동 치환한다. 사용자는 DAG 의존성 선언만 하면 되고, 아티팩트 URL을 수동으로 전달할 필요가 없다.
