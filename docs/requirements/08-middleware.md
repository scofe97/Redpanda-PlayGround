# 미들웨어 (지원 도구 관리)

## TPS 원본 — 마인드맵에서 무엇을 하는가

TPS에서 지원 도구 관리는 CI/CD 파이프라인에 필요한 외부 도구들을 등록하고 관리하는 기능이다. 마인드맵 2에서 각 도구는 역할(Role)에 따라 분류된다:

| 도구 | 역할 |
|------|------|
| Jenkins | CI/CD Tool |
| ArgoCD | Cluster Application |
| Harbor | Container Registry (Upload) |
| Nexus | Library (Upload) |
| Min.io | Storage (Upload) |
| GitLab | VCS |

업무코드별로 어떤 도구 조합을 사용할지 미리 정의하고, 파이프라인 실행 시 해당 조합에서 도구를 해석한다.

---

## PoC 목표 — Playground에서 무엇을 검증하는가

1. **역할 기반 분류**: 현재 SupportTool의 ToolType(JENKINS/GITLAB/NEXUS/HARBOR)을 역할(Category) + 구현체(Implementation)로 분리한다. 역할이 같으면 구현체를 교체할 수 있다
2. **미들웨어 프리셋**: 역할별 도구 조합을 프리셋으로 묶는 개념을 구현한다. 파이프라인이 프리셋을 참조하면, 프리셋 수정 시 파이프라인을 개별 변경할 필요가 없다
3. **UC-5 검증**: 프리셋에서 CI 도구를 Jenkins → GoCD로 교체하면, 해당 프리셋을 참조하는 모든 파이프라인이 자동으로 GoCD를 사용하는 흐름을 구현한다

---

## 현재 구현 상태

| 항목 | 상태 | 비고 |
|------|------|------|
| SupportTool CRUD | 구현됨 | 이름, URL, 타입, 인증정보 |
| ToolType 열거형 | 구현됨 | JENKINS/GITLAB/NEXUS/HARBOR |
| 동적 Connect 커넥터 | 구현됨 | SupportTool 등록 시 Connect 스트림 자동 생성 |
| ToolRegistry | 구현됨 | 타입별 도구 조회 |
| 역할 기반 분류 | 미구현 | ToolType이 도구명과 역할을 혼합 |
| 미들웨어 프리셋 | 미구현 | |

---

## 요구사항

### REQ-08-001: ToolCategory + ToolImplementation 분리 [P0]

현재 ToolType(도구명 = 역할)을 ToolCategory(역할)와 ToolImplementation(구현체)로 분리한다.

**ToolCategory (역할):**

| 카테고리 | 설명 | 예시 구현체 |
|---------|------|-----------|
| CI_CD_TOOL | 빌드/배포 실행 엔진 | Jenkins, GoCD, GitHub Actions |
| CONTAINER_REGISTRY | 컨테이너 이미지 저장소 | Harbor, Docker Hub, ECR |
| LIBRARY | 라이브러리/패키지 저장소 | Nexus, Artifactory |
| STORAGE | 범용 파일 저장소 | Minio, S3 |
| CLUSTER_APPLICATION | K8S 애플리케이션 관리 | ArgoCD, Flux |
| VCS | 소스 코드 관리 | GitLab, GitHub, Bitbucket |

- SupportTool 엔티티에 `category`(역할) + `implementation`(구현체 타입) 필드 추가
- 기존 ToolType → ToolCategory + ToolImplementation 마이그레이션
- ToolRegistry 조회를 카테고리 기반으로 변경

### REQ-08-002: 미들웨어 프리셋 엔티티 [P0]

역할별 도구 조합을 묶는 프리셋 엔티티를 정의한다.

- 프리셋 ID, 이름, 설명
- 프리셋 항목: 카테고리 → SupportTool ID 매핑 (1:1, 카테고리당 하나의 도구)
- 예: "preset-team-a" = { CI_CD_TOOL: jenkins-a, CONTAINER_REGISTRY: harbor-01, VCS: gitlab-01, CLUSTER_APPLICATION: argocd-01 }
- 모든 카테고리를 채울 필요 없음 (파이프라인이 사용하는 카테고리만)

### REQ-08-003: 프리셋 CRUD [P0]

프리셋을 관리하는 API를 제공한다.

- 프리셋 생성: 이름 + 카테고리-도구 매핑 리스트
- 프리셋 조회: 상세(매핑된 도구 포함) + 목록
- 프리셋 수정: 카테고리별 도구 변경 (UC-5의 핵심)
- 프리셋 삭제: 참조 중인 파이프라인이 없을 때만
- 프리셋 도구 교체 시 해당 프리셋을 참조하는 파이프라인 목록 조회

### REQ-08-004: 파이프라인-프리셋 연계 [P0]

파이프라인이 프리셋을 참조하는 구조를 구현한다.

- 파이프라인 생성/수정 시 프리셋 ID 설정
- 파이프라인 실행 시: 프리셋 → 카테고리별 도구 해석 → CiAdapter/CdAdapter 선택
- 프리셋이 변경되면 다음 파이프라인 실행부터 새 도구 사용
- 프리셋 없이 파이프라인 생성 가능 (실행 시 프리셋 지정 필수)

### REQ-08-005: 어댑터 팩토리 [P1]

프리셋에서 해석한 도구 정보를 기반으로 적절한 어댑터를 생성하는 팩토리를 구현한다.

- `AdapterFactory.createCiAdapter(toolInfo) → CiAdapter`
- `AdapterFactory.createCdAdapter(toolInfo) → CdAdapter`
- `AdapterFactory.createRegistryAdapter(toolInfo) → ArtifactRegistryAdapter`
- ToolImplementation(JENKINS/GOCD/ARGOCD 등)에 따라 구현체 선택
- SupportTool의 연결 정보(URL, 인증)를 어댑터에 주입

### REQ-08-006: 업무코드-기본 프리셋 매핑 [P1]

업무코드에 기본 프리셋을 매핑하는 편의 기능을 제공한다.

- 업무코드 "TEAM-A" → 기본 프리셋 "preset-team-a"
- 워크플로우 경유 파이프라인 실행 시, 업무코드의 기본 프리셋이 주입됨
- 파이프라인 자체에 프리셋이 설정되어 있으면, 파이프라인 프리셋 우선
- 이 매핑은 티켓(01) 도메인의 업무코드 엔티티에서 관리

### REQ-08-007: Connect 커넥터 자동 관리 확장 [P2]

SupportTool 등록/수정 시 Connect 스트림을 자동 생성/수정하는 기존 기능을, 카테고리 기반으로 확장한다.

- CI_CD_TOOL 등록 시: command 토픽 → HTTP 브릿지 Connect 스트림 생성
- VCS 등록 시: webhook 수신 Connect 스트림 생성
- 기존 동적 Connect 관리 로직을 카테고리별로 분기

---

## 설계 고민 포인트

### 프리셋은 독립 엔티티인가, 업무코드의 속성인가

프리셋을 독립 엔티티로 만드는 이유는 파이프라인의 독립성 때문이다. 파이프라인은 업무코드를 모르고, 프리셋만 참조한다. 업무코드 → 기본 프리셋 매핑은 편의 기능일 뿐, 파이프라인과 프리셋의 관계를 업무코드가 중재하지 않는다. 파이프라인 단독 실행(UC-1)에서도 프리셋을 직접 지정할 수 있어야 하기 때문이다.

### ToolType 마이그레이션 전략

기존 ToolType(JENKINS/GITLAB/NEXUS/HARBOR)을 ToolCategory + ToolImplementation으로 분리하면, DB 마이그레이션과 기존 코드 수정이 필요하다. 마이그레이션 전략:

1. SupportTool 테이블에 `category`, `implementation` 컬럼 추가
2. 기존 데이터를 매핑: JENKINS → (CI_CD_TOOL, JENKINS), HARBOR → (CONTAINER_REGISTRY, HARBOR) 등
3. 기존 `tool_type` 컬럼은 잔류시키되 deprecated 처리
4. ToolRegistry의 조회 메서드를 category 기반으로 변경
5. 안정화 후 `tool_type` 컬럼 제거

### 프리셋에 필수 카테고리가 있어야 하는가

모든 프리셋이 6개 카테고리를 다 채울 필요는 없다. 빌드만 하는 파이프라인은 CI_CD_TOOL + CONTAINER_REGISTRY만 있으면 되고, 배포만 하는 파이프라인은 CLUSTER_APPLICATION만 있으면 된다. 프리셋은 "사용 가능한 도구 목록"이고, 파이프라인 실행 시 필요한 카테고리가 프리셋에 없으면 에러를 반환하는 방식이 유연하다.
