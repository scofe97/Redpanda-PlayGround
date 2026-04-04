# 배포 Job 도메인

## 개요

배포 Job은 아티팩트를 대상 환경에 배포하는 CD 단위이다. Jenkins Pipeline으로 실행되며, 배포 대상은 VM(SSH 스크립트)과 K8S(ArgoCD Sync 또는 kubectl apply)로 나뉜다. CdAdapter 인터페이스로 CD 도구를 교체할 수 있는 구조를 목표로 한다.

배포 Job은 `deployMode`에 따라 두 가지 유형으로 나뉜다.

- **IMPORT**: 외부 아티팩트(Nexus URL/GAV 좌표)를 직접 지정하여 배포한다. 빌드 Job 없이 단독 실행이 가능하다.
- **BUILD_REQUIRED**: 특정 빌드 Job의 결과물을 배포한다. `requiredBuildJobId`로 빌드 Job을 지정하며, 단독 실행은 불가하다. 파이프라인에서 사용할 때 해당 빌드 Job이 동일 파이프라인에 포함되어 있는지 검증한다.

BUILD_REQUIRED 유형은 파이프라인 실행 시 `ARTIFACT_URL`을 빌드 Job의 실행 컨텍스트에서 자동 수신한다. IMPORT 유형은 `artifactRef`에서 `ARTIFACT_URL`을 직접 해석한다.

---

## 현재 상태 (2026-03-23 기준)

| 항목 | 상태 | 비고 |
|------|------|------|
| Deploy 스텝 실행 | 완료 | PipelineEngine 내 고정 스텝 |
| Jenkins 배포 트리거 | 완료 | Kafka→Connect→Jenkins 경유 |
| ARTIFACT_URL 자동 주입 | 완료 | dependsOnJobIds → context_json 자동 전달 (V36) |
| ARTIFACT_URL 플레이스홀더 치환 | 완료 | ParameterResolver, ${ARTIFACT_URL} 치환 |
| deployMode (IMPORT/BUILD_REQUIRED) | 미구현 | 배포 유형 구분 필드 |
| requiredBuildJobId | 미구현 | BUILD_REQUIRED 시 빌드 Job 참조 |
| 파이프라인 빌드 포함 검증 | 미구현 | BUILD_REQUIRED 매핑 시 빌드 Job 포함 검증 |
| VM 배포 분기 | 미구현 | 현재 Jenkins에 위임하는 구조만 존재 |
| K8S 배포 분기 | 미구현 | ArgoCD Sync / kubectl apply 미구현 |
| CdAdapter 인터페이스 | 미구현 | Jenkins 하드코딩 상태 |
| 배포 롤백 | 미구현 | |
| 배포 상태 모니터링 | 미구현 | |
| 배포 이력 | 미구현 | |

---

## 요구사항

### REQ-06-008: 배포 모드 (IMPORT / BUILD_REQUIRED) [P0] — 미구현

배포 Job의 아티팩트 소스를 `deployMode` 필드로 명시적으로 구분한다. 이 구분에 따라 단독 실행 가능 여부와 파이프라인 구성 검증 규칙이 달라진다.

**새 필드 (pipeline_job 테이블, DEPLOY 타입 전용):**

| 필드 | 타입 | 설명 |
|------|------|------|
| `deploy_mode` | `VARCHAR(20)` — `IMPORT` \| `BUILD_REQUIRED` | 배포 유형. DEPLOY 타입 Job 필수 |
| `required_build_job_id` | `BIGINT NULL`, FK → `pipeline_job(id)` | BUILD_REQUIRED일 때 필수, IMPORT일 때 NULL |

**유형별 동작:**

| 항목 | IMPORT | BUILD_REQUIRED |
|------|--------|----------------|
| artifactRef | 필수 (Nexus URL/GAV) | NULL (빌드 결과 자동 수신) |
| requiredBuildJobId | NULL | 필수 (빌드 Job ID) |
| 단독 실행 | 가능 | **불가** (400 에러) |
| 파이프라인 매핑 검증 | 없음 | 해당 빌드 Job이 동일 파이프라인에 포함되어야 함 |
| ARTIFACT_URL 소스 | config.artifactRef에서 직접 해석 | 빌드 Job 실행 컨텍스트에서 자동 주입 |

**수용 기준**:
- [ ] `deployMode` 필드 추가 (`IMPORT` | `BUILD_REQUIRED`). DEPLOY 타입 Job 생성 시 필수
- [ ] `IMPORT`: `artifactRef` 필수, `requiredBuildJobId` NULL
- [ ] `BUILD_REQUIRED`: `requiredBuildJobId` 필수, 해당 ID가 실제 BUILD 타입 Job인지 검증
- [ ] `BUILD_REQUIRED` 단독 실행 시 400 에러 ("빌드 연동 배포는 파이프라인에서만 실행 가능")
- [ ] `IMPORT` 단독 실행 시 `artifactRef`에서 ARTIFACT_URL 해석하여 실행 허용

**구현 참조**: (미구현)

**Phase**: 4

---

### REQ-06-009: 파이프라인 빌드 포함 검증 [P0] — 미구현

`deployMode = BUILD_REQUIRED`인 배포 Job을 파이프라인에 추가할 때, `requiredBuildJobId`로 지정된 빌드 Job이 동일 파이프라인에 포함되어 있는지 검증한다. 검증을 통과하면 DAG 의존성을 자동 생성한다.

**수용 기준**:
- [ ] BUILD_REQUIRED 배포 Job을 파이프라인에 매핑할 때, `requiredBuildJobId`가 동일 파이프라인의 `pipeline_job_mapping`에 존재하는지 검증
- [ ] 존재하지 않으면 400 에러 ("필요 빌드 Job '{name}'이 파이프라인에 포함되어 있지 않습니다")
- [ ] 존재하면 자동으로 `pipeline_job_dependency` 생성 (빌드 → 배포 방향)
- [ ] 파이프라인에서 빌드 Job 제거 시, 해당 빌드를 `requiredBuildJobId`로 참조하는 배포 Job이 있으면 제거 거부

**구현 참조**: (미구현)

**Phase**: 4

---

### REQ-06-001: VM/K8S 배포 분기 [P0] — 미구현

배포 Job 설정에 배포 대상 유형을 지정하고, 유형에 따라 다른 배포 전략을 실행한다.

**수용 기준**:
- [ ] `deployTarget: VM | K8S` 필드 정의
- [ ] VM: SSH 접속 정보 + 스크립트 경로로 배포
- [ ] K8S: 클러스터 정보 + 매니페스트 또는 ArgoCD Application 이름으로 배포
- [ ] `environmentId`로 배포 환경(09) 엔티티 참조

**구현 참조**: (미구현)

**Phase**: 4

---

### REQ-06-002: CdAdapter 인터페이스 [P0] — 미구현

배포 실행을 CD 미들웨어에 위임하는 어댑터 인터페이스를 정의한다. Jenkins 하드코딩을 제거하고, ArgoCD·SSH 배포를 동일 인터페이스로 교체할 수 있는 구조를 만든다.

**수용 기준**:
- [ ] `CdAdapter` 인터페이스 정의: `deploy()`, `getDeployStatus()`, `rollback()`, `cancelDeploy()`
- [ ] K8S 배포: `ArgoCdAdapter` (ArgoCD Sync API 호출)
- [ ] VM 배포: `SshDeployAdapter` (SSH 원격 스크립트 실행) 또는 Jenkins Job 위임
- [ ] 프리셋 CD 도구 설정에 따라 적절한 어댑터 선택

**구현 참조**: (미구현)

**Phase**: 4

---

### REQ-06-003: 배포 설정 구조 [P1] — 미구현

배포 Job의 config JSON 스키마를 정의한다. VM과 K8S는 config 구조가 다르며, `deployTarget` 필드로 구분한다.

**수용 기준**:
- [ ] VM 배포 config: `deployTarget`, `environmentId`, `deploy.script`, `deploy.artifactPath`, `deploy.healthCheckUrl`, `deploy.healthCheckTimeout`
- [ ] K8S 배포 config: `deployTarget`, `environmentId`, `deploy.method(ARGOCD_SYNC|KUBECTL_APPLY)`, `deploy.applicationName`, `deploy.namespace`, `deploy.imageOverride`
- [ ] `${ARTIFACT_URL}`, `${BUILD_OUTPUT_IMAGE}` 플레이스홀더 자동 치환 (ParameterResolver 재사용)
- [ ] `deployTarget` 필드 누락 시 400 에러 반환

**구현 참조**: (미구현)

**Phase**: 4

---

### REQ-06-004: DEPLOY Job 아티팩트 수신 [P0] — 완료

BUILD Job이 완료되면 생성된 아티팩트 URL을 DEPLOY Job이 자동으로 수신한다. `deployMode = BUILD_REQUIRED`에서는 `requiredBuildJobId`로 지정된 빌드 Job의 실행 컨텍스트에서 자동 주입한다. `deployMode = IMPORT`에서는 `artifactRef`에서 ARTIFACT_URL을 직접 해석한다.

**수용 기준**:
- [x] `dependsOnJobIds`에 BUILD Job ID를 지정하면 `ARTIFACT_URL` 자동 주입 (V36)
- [x] BUILD 완료 시 `ARTIFACT_URL_{jobId}` 키로 실행 컨텍스트에 저장
- [x] ParameterResolver가 DEPLOY Job 실행 시 `${ARTIFACT_URL}` 플레이스홀더를 컨텍스트 값으로 치환
- [ ] `IMPORT` 모드: `artifactRef`에서 ARTIFACT_URL 직접 해석 (REQ-06-008 연계)

**구현 참조**: `pipeline.service.ExecutionContextService`, `pipeline.service.ParameterResolver`

**Phase**: 3 (기본), 4 (IMPORT 모드 확장)

---

### REQ-06-005: 배포 롤백 [P1] — 미구현

배포 실패 또는 수동 롤백 시 이전 버전으로 되돌리는 기능을 제공한다.

**수용 기준**:
- [ ] `CdAdapter.rollback(deploymentId)`로 이전 배포 상태 복원
- [ ] ArgoCD: 이전 Sync 리비전으로 Sync 요청
- [ ] VM: 이전 배포 스냅샷의 스크립트 재실행
- [ ] 파이프라인 SAGA 보상 작업에 롤백 연계

**구현 참조**: (미구현)

**Phase**: 4

---

### REQ-06-006: 배포 상태 모니터링 [P1] — 미구현

배포 진행 중 상태를 추적하고 SSE로 프론트엔드에 전달한다.

**수용 기준**:
- [ ] `CdAdapter.getDeployStatus()` 주기적 폴링 또는 이벤트 기반 상태 수신
- [ ] 배포 상태 전이: `PENDING → DEPLOYING → HEALTH_CHECK → DEPLOYED/FAILED`
- [ ] 헬스 체크 URL 기반 HTTP 200 확인 포함
- [ ] SSE로 배포 진행률 전달 (SseEmitterRegistry 재사용)

**구현 참조**: (미구현)

**Phase**: 4

---

### REQ-06-007: 배포 이력 [P2] — 미구현

배포 실행 이력을 관리하고 롤백 이력을 포함하여 조회한다.

**수용 기준**:
- [ ] 배포 대상 환경, 배포 시간, 소요 시간, 결과 기록
- [ ] 배포된 산출물 정보 (이미지 태그, JAR 버전) 포함
- [ ] 롤백 이력 포함하여 조회

**구현 참조**: (미구현)

**Phase**: 4

---

## 설계 결정

### VM 배포: SSH 직접 실행 vs Jenkins 위임

VM 배포를 Spring Boot가 직접 SSH로 실행하는 방식과 Jenkins Job에 위임하는 방식 사이에 트레이드오프가 있다. SSH 직접 실행은 구조가 단순하지만 SSH 키 관리, 접속 풀링, 타임아웃 처리를 직접 구현해야 한다. Jenkins 위임은 Jenkins의 SSH Agent 인프라를 재사용할 수 있지만 Jenkins에 대한 의존성이 높아진다.

PoC에서는 Jenkins 위임으로 시작하되, CdAdapter 인터페이스 뒤에 구현을 숨긴다. 이후 SSH 직접 실행으로 교체할 때 서비스 로직은 변경하지 않아도 된다.

### K8S 배포: ArgoCD vs kubectl 직접 적용

ArgoCD가 설치된 환경에서는 GitOps 방식의 ArgoCD Sync가 권장된다. ArgoCD가 없는 환경에서는 `kubectl apply`를 직접 실행해야 한다. 두 경로 모두 `CdAdapter`로 추상화하고, config의 `deploy.method` 필드로 분기한다. ArgoCD Sync는 REST API 호출이므로 CdAdapter에 자연스럽게 들어가며, `kubectl apply`는 Kubernetes Java Client 또는 ProcessBuilder로 구현한다.

---

## 설계 결정

### 배포 모드: IMPORT vs BUILD_REQUIRED

배포 Job에는 본질적으로 다른 두 가지 유형이 있다. 반입 배포(IMPORT)는 Nexus 등 외부 저장소의 아티팩트를 직접 지정하여 독립적으로 실행한다. 빌드 연동 배포(BUILD_REQUIRED)는 특정 빌드 Job의 결과물에 의존하므로 단독 실행이 불가하고, 파이프라인에서 해당 빌드가 포함되어야 한다.

이 구분을 `deployMode` 필드로 Job 엔티티 레벨에서 명시한 이유는 세 가지다.

첫째, 단독 실행 가능 여부가 본질적으로 다르다. IMPORT는 아티팩트 참조가 자체 완결적이므로 단독 실행이 가능하지만, BUILD_REQUIRED는 빌드 결과가 없으면 배포 자체가 불가능하다. 이 차이를 암묵적(artifactRef 유무)으로 판별하면 실수로 빈 artifactRef를 넣거나, 빌드 없이 배포를 시도하는 오류를 잡기 어렵다.

둘째, 파이프라인 구성 검증의 깊이가 다르다. BUILD_REQUIRED는 파이프라인에 매핑할 때 `requiredBuildJobId`가 동일 파이프라인에 포함되어 있는지 검증해야 한다. IMPORT는 이런 검증이 불필요하다. 명시적 모드가 있으면 검증 로직을 깔끔하게 분기할 수 있다.

셋째, ADR-005의 "DEPLOY Job의 artifactRef로 반입을 표현한다"는 결정은 유지하되, `deployMode`로 의도를 명확히 선언하여 검증을 강화한다.

### VM 배포: SSH 직접 실행 vs Jenkins 위임

VM 배포를 Spring Boot가 직접 SSH로 실행하는 방식과 Jenkins Job에 위임하는 방식 사이에 트레이드오프가 있다. SSH 직접 실행은 구조가 단순하지만 SSH 키 관리, 접속 풀링, 타임아웃 처리를 직접 구현해야 한다. Jenkins 위임은 Jenkins의 SSH Agent 인프라를 재사용할 수 있지만 Jenkins에 대한 의존성이 높아진다.

PoC에서는 Jenkins 위임으로 시작하되, CdAdapter 인터페이스 뒤에 구현을 숨긴다. 이후 SSH 직접 실행으로 교체할 때 서비스 로직은 변경하지 않아도 된다.

### K8S 배포: ArgoCD vs kubectl 직접 적용

ArgoCD가 설치된 환경에서는 GitOps 방식의 ArgoCD Sync가 권장된다. ArgoCD가 없는 환경에서는 `kubectl apply`를 직접 실행해야 한다. 두 경로 모두 `CdAdapter`로 추상화하고, config의 `deploy.method` 필드로 분기한다. ArgoCD Sync는 REST API 호출이므로 CdAdapter에 자연스럽게 들어가며, `kubectl apply`는 Kubernetes Java Client 또는 ProcessBuilder로 구현한다.

---

## 도메인 간 관계

| 참조 도메인 | 관계 | 설명 |
|------------|------|------|
| 03-pipeline | 실행 컨텍스트 소비 | BUILD_REQUIRED: `requiredBuildJobId` 기반 DAG 자동 생성 + ARTIFACT_URL 자동 수신 |
| 04-build-job | 선행 의존 | BUILD_REQUIRED 시 `requiredBuildJobId`로 직접 참조. BUILD 완료 후 아티팩트 URL이 컨텍스트에 등록됨 |
| 07-artifact | 아티팩트 참조 | IMPORT 시 `artifactRef`로 외부 저장소의 아티팩트를 직접 지정 |
| 08-middleware | 프리셋 참조 | CD 도구(ArgoCD 등) 연결 정보를 프리셋에서 조회 |
| 09-deploy-environment | 배포 대상 참조 | `environmentId`로 VM/K8S 접속 정보를 환경 도메인에서 조회 |
| 10-user-notification | 배포 완료 알림 | 배포 성공/실패 시 SSE 및 웹훅 알림 발송 |

---

## 미해결 사항

- **deployMode 구현 (REQ-06-008)**: `deploy_mode`, `required_build_job_id` 컬럼 추가와 검증 로직 구현이 필요하다. DB 마이그레이션 V37.
- **파이프라인 빌드 포함 검증 (REQ-06-009)**: BUILD_REQUIRED 배포 Job 매핑 시 빌드 포함 검증 + DAG 자동 생성 로직 구현이 필요하다.
- **CdAdapter 추상화 (REQ-06-002)**: Jenkins 하드코딩을 제거하고 ArgoCD/SSH 어댑터로 교체 가능한 인터페이스 도입이 필요하다.
- **VM/K8S 분기 (REQ-06-001)**: 현재 배포 대상 유형이 없어 모든 배포가 Jenkins에 위임된다. 환경 도메인(09) 구현 후 연계 가능하다.
- **배포 롤백 (REQ-06-005)**: SAGA 보상과 연계하여 실패 시 자동 롤백 경로가 필요하다. 파이프라인 SAGA 구현(03)에 의존한다.
