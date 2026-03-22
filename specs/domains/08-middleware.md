# 미들웨어 도메인

## 개요

미들웨어 도메인은 파이프라인이 사용하는 외부 도구(Jenkins, GitLab, Nexus, Harbor 등)의 등록과 조합을 관리한다. SupportTool로 개별 도구를 등록하고, MiddlewarePreset으로 역할별 도구 조합을 묶어, 파이프라인이 프리셋을 참조하는 구조이다. 프리셋을 교체하면 해당 프리셋을 참조하는 모든 파이프라인이 자동으로 새 도구를 사용한다.

---

## 현재 상태 (2026-03-22 기준)

**구현완료** — Phase 0에서 핵심 구현, Phase 3에서 프리셋 관리 UI 추가.

| 항목 | 상태 | 구현 시점 |
|------|------|----------|
| SupportTool CRUD + 헬스체크 | 완료 | Phase 0 |
| 6카테고리 분류 (CI_CD_TOOL, VCS, LIBRARY, CONTAINER_REGISTRY, STORAGE, CLUSTER_APP) | 완료 | Phase 0 |
| ToolCategory + ToolImplementation 분리 (V13) | 완료 | Phase 0 |
| auth_type (BASIC/PRIVATE_TOKEN/NONE) 통합 (V12) | 완료 | Phase 0 |
| MiddlewarePreset CRUD | 완료 | Phase 0 |
| 동적 Connect 커넥터 관리 | 완료 | Phase 0 |
| GoCD 제거 | 완료 | Phase 0 |
| ToolRegistry 런타임 해석 | 완료 | Phase 0 |
| 프리셋 관리 페이지 (/presets) | 완료 | Phase 3 (2026-03-22) |

---

## 요구사항

### REQ-08-001: SupportTool CRUD [P0] — 완료

외부 도구의 URL, 인증 정보, 카테고리를 DB에 저장하고 관리하는 기능이다. 도구 정보를 `application.yml`이 아닌 DB에 저장하는 이유는 앱 재시작 없이 도구를 교체할 수 있어야 하기 때문이다. TPS 실무에서는 팀마다 다른 Jenkins 인스턴스를 사용하므로, 런타임 교체가 필수 요건이다.

**수용 기준:**
- [x] 도구 생성: 이름, URL, 카테고리, 구현체 타입, 인증 정보 저장
- [x] 도구 목록/상세 조회
- [x] 도구 수정: URL, 인증 정보 변경
- [x] 도구 삭제: 프리셋에서 참조 중이 아닐 때만 허용
- [x] auth_type(BASIC/PRIVATE_TOKEN/NONE) 기반 인증 방식 통합 (V12)

**구현 참조:** `supporttool` 패키지 — `SupportTool`, `SupportToolService`, `SupportToolController`

**Phase:** 0

---

### REQ-08-002: 도구 헬스체크 [P0] — 완료

등록된 도구의 연결 상태를 확인하는 기능이다. 프리셋 구성 시 실제로 접근 가능한 도구인지 검증하기 위해 필요하다.

**수용 기준:**
- [x] 도구 URL로 HTTP 요청을 보내 연결 상태 확인
- [x] 헬스체크 결과(성공/실패/지연시간) 반환
- [x] 인증이 필요한 도구는 auth_type에 따라 인증 헤더 포함

**구현 참조:** `supporttool` 패키지 — `SupportToolHealthChecker`

**Phase:** 0

---

### REQ-08-003: 역할 기반 분류 (ToolCategory + ToolImplementation) [P0] — 완료

기존 ToolType(도구명 = 역할 혼합)을 ToolCategory(역할)와 ToolImplementation(구현체)으로 분리한다. 역할이 같으면 구현체를 교체할 수 있어, 예를 들어 CI_CD_TOOL 역할에 Jenkins 대신 다른 구현체를 사용할 수 있다. V13 마이그레이션으로 적용되었다.

**ToolCategory (역할) 정의:**

| 카테고리 | 설명 | 예시 구현체 |
|---------|------|-----------|
| CI_CD_TOOL | 빌드/배포 실행 엔진 | Jenkins |
| CONTAINER_REGISTRY | 컨테이너 이미지 저장소 | Harbor |
| LIBRARY | 라이브러리/패키지 저장소 | Nexus, Artifactory |
| STORAGE | 범용 파일 저장소 | Minio, S3 |
| CLUSTER_APP | K8s 애플리케이션 관리 | ArgoCD |
| VCS | 소스 코드 관리 | GitLab |

**수용 기준:**
- [x] SupportTool 엔티티에 `category`(역할) + `implementation`(구현체 타입) 필드 존재
- [x] ToolRegistry 조회가 카테고리 기반으로 동작
- [x] 6개 카테고리 모두 등록/조회 가능

**구현 참조:** `supporttool` 패키지 — `ToolCategory`, `ToolImplementation` enum

**Phase:** 0

---

### REQ-08-004: 미들웨어 프리셋 CRUD [P0] — 완료

역할별 도구 조합을 묶는 프리셋 엔티티를 정의하고 관리 API를 제공한다. 프리셋은 독립 엔티티로 파이프라인이 직접 참조한다. 파이프라인은 업무코드를 모르고 프리셋만 참조하므로, 프리셋을 교체하면 해당 프리셋을 참조하는 모든 파이프라인이 다음 실행부터 자동으로 새 도구를 사용한다.

**수용 기준:**
- [x] 프리셋 생성: 이름 + 카테고리-도구 매핑 리스트
- [x] 프리셋 목록/상세 조회 (매핑된 도구 정보 포함)
- [x] 프리셋 수정: 카테고리별 도구 변경
- [x] 프리셋 삭제: 참조 중인 파이프라인이 없을 때만 허용
- [x] 모든 카테고리를 채울 필요 없음 (파이프라인이 사용하는 카테고리만 등록)
- [x] 프리셋 도구 교체 시 해당 프리셋을 참조하는 파이프라인 목록 조회 가능

**구현 참조:** `preset` 패키지 — `MiddlewarePreset`, `PresetItem`, `PresetService`, `PresetController`

**Phase:** 0

---

### REQ-08-005: 동적 Connect 커넥터 관리 [P0] — 완료

SupportTool 등록/삭제 시 Redpanda Connect 스트림을 자동으로 생성/삭제한다. 커넥터 설정을 DB에 영속화하고 런타임에 등록/삭제하는 방식으로, 앱 재시작 시에도 커넥터 상태를 복원한다.

**수용 기준:**
- [x] CI_CD_TOOL 등록 시 command 토픽 → HTTP 브릿지 Connect 스트림 자동 생성
- [x] VCS 등록 시 webhook 수신 Connect 스트림 자동 생성
- [x] 도구 삭제 시 관련 Connect 스트림 삭제
- [x] 커넥터 설정을 DB에 영속화하여 재시작 후 복원
- [x] Connect 단일포트(4195) 사용, 스트림명 프리픽스 `/jenkins-webhook/webhook/jenkins`

**구현 참조:** `connect` 패키지 — `DynamicConnectorManager`, `ConnectorRepository`

**Phase:** 0

---

### REQ-08-006: 프리셋 관리 UI [P1] — 완료

프리셋을 등록/수정/삭제하는 프론트엔드 페이지(/presets)를 제공한다. 파이프라인 생성 UI에서 프리셋을 선택할 수 있도록 한다.

**수용 기준:**
- [x] `/presets` 경로에 프리셋 목록 페이지 제공
- [x] PresetFormDrawer로 프리셋 생성/수정 UI 제공
- [x] 카테고리별 도구 선택 드롭다운 (등록된 SupportTool 목록에서 선택)
- [x] 프리셋 삭제 시 참조 파이프라인 경고 표시

**구현 참조:** `frontend/src/pages/presets/` — `PresetsPage`, `PresetFormDrawer`

**Phase:** 3 (2026-03-22)

---

### REQ-08-007: ToolRegistry 런타임 해석 [P0] — 완료

파이프라인 실행 시 활성 도구를 런타임에 해석하는 레지스트리이다. 프리셋 ID와 카테고리를 조합하여 실제 사용할 SupportTool을 반환하며, Job의 configJson에서 도구 URL을 주입하는 데 사용된다.

**수용 기준:**
- [x] 프리셋 ID + 카테고리로 SupportTool 조회
- [x] 조회된 SupportTool의 URL/인증 정보를 Job에 주입
- [x] 프리셋에 해당 카테고리가 없으면 명시적 에러 반환

**구현 참조:** `supporttool` 패키지 — `ToolRegistry`

**Phase:** 0

---

## 설계 결정

### 1. 도구 정보를 DB에 저장 (application.yml 아님)

앱 재시작 없이 도구를 교체할 수 있어야 하기 때문이다. TPS 실무에서는 팀마다 다른 Jenkins 인스턴스를 사용하고, 운영 중에도 도구 URL이나 인증 정보가 바뀔 수 있다. 설정 파일 방식은 이런 동적 변경을 수용할 수 없다.

### 2. 프리셋을 독립 엔티티로 설계

파이프라인의 독립성을 위해서이다. 파이프라인은 업무코드를 모르고 프리셋만 참조한다. 업무코드 → 기본 프리셋 매핑은 편의 기능일 뿐, 파이프라인과 프리셋의 관계를 업무코드가 중재하지 않는다. 파이프라인 단독 실행(UC-1)에서도 프리셋을 직접 지정할 수 있어야 하기 때문이다.

### 3. GoCD 제거

요구사항 원안(REQ-08-002 원본)에서는 GoCD를 UC-5 검증용 대체 구현체로 포함했으나, 실제 미구현 구현체는 불필요한 간접 계층만 추가한다. Jenkins만 구현한 상태에서 인터페이스 추상화는 유지하되, GoCD 관련 코드는 제거했다. 추상화 구조가 살아 있으므로 나중에 다른 CI 도구 추가는 동일한 방식으로 가능하다.

---

## 도메인 간 관계

- **03-pipeline**: 파이프라인이 `presetId`로 MiddlewarePreset을 참조한다. 파이프라인 생성 시 프리셋을 지정하며, 실행 시 ToolRegistry가 프리셋에서 도구를 해석한다.
- **04-build-job**: BuildJob의 `configJson`에서 ToolRegistry가 해석한 Jenkins URL과 Nexus URL을 사용한다.
- **06-deploy-job**: DeployJob의 `configJson`에서 Harbor URL과 ArgoCD 정보를 사용한다.
- **Connect**: 동적 커넥터로 Kafka 토픽과 외부 시스템(Jenkins HTTP API, GitLab Webhook 등)을 연결한다.

---

## 미해결 사항

- **필수 카테고리 제약 없음**: 프리셋 생성 시 어떤 카테고리도 강제하지 않는다. 파이프라인 실행 시점에 필요한 카테고리가 없으면 에러를 반환하는 방식이나, 이를 사전에 검증하는 제약이 없어 실행 직전에야 오류를 발견하게 된다.
- **도구 인증 갱신 자동화 미구현**: PRIVATE_TOKEN이나 BASIC 인증의 만료를 감지하거나 자동 갱신하는 기능이 없다. 인증 만료 시 도구 헬스체크가 실패하면 수동으로 인증 정보를 업데이트해야 한다.
