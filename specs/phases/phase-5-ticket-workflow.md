# Phase 5: 티켓→워크플로우→파이프라인 연계

> 업무코드 기반 워크플로우 자동 결정부터 파이프라인 트리거까지, UC-2 전체 흐름을 구현하여 "티켓 생성만으로 배포 프로세스가 자동 시작되는" 구조를 완성한다.

## 목표

- 업무코드(BusinessCode) 개념을 도입하고, 업무코드와 워크플로우를 1:1로 매핑한다
- 워크플로우를 BUILD/DEPLOY/APPROVAL 컴포넌트의 순서 있는 조합으로 정의한다
- 워크플로우 실행 엔진이 컴포넌트를 순서대로 처리하며, 빌드/배포 컴포넌트는 파이프라인 실행 API를 이벤트 기반으로 호출한다
- 결재 컴포넌트는 스텁으로 구현하여 "승인/거절 API 호출 → 다음 단계 진행" 흐름을 검증한다

## 사전 조건

- Phase 3 완료 (충족): DAG 엔진, 파이프라인 실행 이력 동작 중
- Phase 4 권장 (필수 아님): 아티팩트 어댑터와 환경 엔티티가 있으면 워크플로우 컴포넌트에서 참조 가능하나, Phase 5는 Phase 4 없이도 독립 구현 가능하다

## 범위

### 도메인 — 티켓 관리 (REQ-01)

| REQ | 내용 | 우선순위 |
|-----|------|---------|
| REQ-01-001 | 티켓 엔티티에 businessCode 필드 추가 | P0 |
| REQ-01-004 | BusinessCode 엔티티 CRUD | P1 |
| REQ-01-002 | 업무코드 → 워크플로우 자동 매핑 (티켓 생성 시) | P0 |
| REQ-01-003 | 파이프라인 독립성 확보 (티켓 생성이 파이프라인 생성을 트리거하지 않음) | P0 |
| REQ-01-005 | 티켓 상태 확장 (WORKFLOW_IN_PROGRESS, APPROVAL_PENDING 추가) | P1 |
| REQ-01-006 | 티켓-파이프라인 실행 이력 연결 (triggerSource, triggerId) | P2 |

### 도메인 — 워크플로우 관리 (REQ-02)

| REQ | 내용 | 우선순위 |
|-----|------|---------|
| REQ-02-001 | 워크플로우 엔티티 정의 (이름, 컴포넌트 목록, 활성 상태) | P0 |
| REQ-02-002 | 컴포넌트 타입 정의 (BUILD / DEPLOY / BUILD_AND_DEPLOY / APPROVAL) | P0 |
| REQ-02-003 | 워크플로우 CRUD | P0 |
| REQ-02-004 | 업무코드-워크플로우 매핑 CRUD | P0 |
| REQ-02-005 | 워크플로우 실행 엔진 (컴포넌트 순차 실행, 이벤트 기반 완료 감지) | P1 |
| REQ-02-006 | 결재 스텁 (승인/거절 API, 자동 승인 모드) | P2 |
| REQ-02-007 | 워크플로우 실행 이력 | P2 |

## 구현 계획

### 1단계: 워크플로우 + 컴포넌트 엔티티 설계

`workflow` 테이블과 `workflow_component` 테이블을 설계한다. `workflow_component`는 `workflowId`, `componentType(BUILD|DEPLOY|BUILD_AND_DEPLOY|APPROVAL)`, `componentOrder`, `config JSON`을 갖는다. config에는 빌드/배포 컴포넌트의 경우 `pipelineId`가, 결재 컴포넌트의 경우 `approverInfo`가 들어간다.

하나의 워크플로우에 같은 타입의 컴포넌트가 여러 개 올 수 있다. 예를 들어 "빌드 → 결재 → 배포" 조합이 유효하다.

### 2단계: 워크플로우 CRUD API

`POST /api/workflows`, `GET /api/workflows`, `GET /api/workflows/{id}`, `PUT /api/workflows/{id}`, `DELETE /api/workflows/{id}` 를 구현한다. 삭제는 해당 워크플로우를 참조하는 업무코드가 없을 때만 허용한다.

컴포넌트 수정은 전체 목록을 교체하는 방식(PUT)으로 단순화한다. 부분 수정(PATCH)은 이번 Phase에서 구현하지 않는다.

### 3단계: 업무코드 엔티티 + 매핑

`business_code` 테이블을 생성한다. 컬럼은 `code(UNIQUE)`, `name`, `description`, `workflowId(FK)`, `defaultPresetId(FK, nullable)` 다.

`GET /api/business-codes`, `POST /api/business-codes`, `PUT /api/business-codes/{code}`, `DELETE /api/business-codes/{code}` API를 구현한다. 업무코드 삭제는 참조 중인 티켓이 없을 때만 허용한다.

### 4단계: 티켓 엔티티 확장 + 파이프라인 독립성 확보

`ticket` 테이블에 `businessCode` 컬럼을 추가한다. 초기에는 nullable로 추가하여 기존 데이터와 하위 호환을 유지한다.

티켓 생성 시 `businessCode`가 입력되면 매핑된 `workflowId`를 조회하여 티켓에 기록한다. `businessCode`가 없으면 워크플로우 없이 티켓이 생성된다.

기존 "티켓 생성 → 파이프라인 자동 생성" 로직을 제거한다. 파이프라인은 이제 워크플로우 실행 엔진이 명시적으로 트리거한다. 파이프라인 직접 실행 API(UC-1)는 영향받지 않는다.

티켓 상태에 `WORKFLOW_IN_PROGRESS`와 `APPROVAL_PENDING`을 추가한다.

### 5단계: 워크플로우 실행 엔진

`WorkflowExecutionEngine`을 구현한다. 티켓에 워크플로우가 연결된 경우, 티켓이 READY 상태가 되면 워크플로우 실행을 시작한다.

실행 흐름은 다음과 같다. 컴포넌트 목록을 `componentOrder` 순서로 정렬한다. 각 컴포넌트를 순서대로 처리하되, 빌드/배포 컴포넌트는 파이프라인 실행 API를 비동기 호출하고 Kafka 이벤트(`PIPELINE_EXECUTION_COMPLETED`)를 수신하여 다음 컴포넌트로 진행한다. 결재 컴포넌트는 티켓 상태를 `APPROVAL_PENDING`으로 전환하고 승인/거절 API 호출을 대기한다.

컴포넌트 실행 상태는 `workflow_component_execution` 테이블에 기록한다. 하나의 컴포넌트라도 FAILED가 되면 워크플로우 전체를 FAILED로 전환하고 티켓 상태도 FAILED로 변경한다.

### 6단계: 결재 스텁

`POST /api/workflows/executions/{executionId}/approve`와 `POST /api/workflows/executions/{executionId}/reject` API를 구현한다. 승인 시 워크플로우 엔진이 다음 컴포넌트로 진행하고, 거절 시 워크플로우를 REJECTED로 종료한다.

자동 승인 모드는 환경 변수 `WORKFLOW_AUTO_APPROVE=true`로 활성화한다. 활성화 시 결재 컴포넌트 도달 즉시 자동 승인 처리한다. PoC 데모 시 편의를 위한 옵션이다.

### 7단계: 티켓 UI 개선

티켓 생성/수정 폼에 업무코드 선택 드롭다운을 추가한다. 업무코드 선택 시 연결된 워크플로우 이름을 미리 보여준다.

티켓 상세 페이지에 워크플로우 진행 상태를 표시한다. 각 컴포넌트의 현재 상태(PENDING/IN_PROGRESS/COMPLETED/FAILED)를 순서대로 보여준다.

## 수용 기준

- [ ] 워크플로우를 생성/수정/삭제할 수 있고, 컴포넌트(BUILD/DEPLOY/APPROVAL)를 순서대로 조합할 수 있다
- [ ] 업무코드를 생성하고 워크플로우와 매핑할 수 있다
- [ ] 업무코드가 있는 티켓을 생성하면 매핑된 워크플로우가 자동으로 연결된다
- [ ] 티켓이 READY가 되면 연결된 워크플로우 실행이 자동 시작된다
- [ ] 워크플로우 실행 중 빌드/배포 컴포넌트가 파이프라인을 트리거하고, 완료 후 다음 컴포넌트로 진행한다
- [ ] 결재 컴포넌트 도달 시 티켓이 APPROVAL_PENDING 상태가 되고, 승인 API 호출 후 다음 단계로 진행한다
- [ ] 파이프라인은 티켓 없이 단독 실행할 수 있다 (UC-1 회귀 없음)
- [ ] 업무코드 없는 티켓은 기존 방식대로 동작한다 (하위 호환)

## 리스크

| 리스크 | 설명 | 완화 방안 |
|--------|------|----------|
| 이벤트 기반 컴포넌트 완료 감지 | 파이프라인 완료 이벤트를 Kafka에서 수신하는 구조가 추가 복잡도를 만든다 | 초기에는 폴링 방식으로 구현 후, 이벤트 기반으로 전환 |
| 워크플로우 실행 상태 일관성 | 엔진 재시작 시 실행 중인 워크플로우 상태 복구가 필요하다 | DB에 컴포넌트별 실행 상태를 저장하여 재시작 후 마지막 PENDING 컴포넌트부터 재개 |
| 티켓-파이프라인 종속 해제 | 기존 코드에서 티켓 생성과 파이프라인 생성이 강결합되어 있을 수 있다 | 해당 로직을 먼저 파악한 후 단계적으로 분리 |
| 매핑 없는 업무코드 처리 | 워크플로우 매핑이 없는 업무코드로 티켓 생성 시 기본 워크플로우 적용 로직 | 기본 워크플로우를 시스템 설정으로 관리 |

## 제외 항목

- REQ-01-006 티켓-파이프라인 실행 이력 상세 연결 (P2)
- REQ-02-007 워크플로우 실행 이력 상세 (P2)
- 결재 시스템 실제 구현 (외부 결재 연동, 결재선 관리 등)
- 워크플로우 병렬 컴포넌트 실행 (모든 컴포넌트는 순차 실행만)
- 업무코드 1:N 워크플로우 매핑 (추후 확장 가능성만 설계에 반영)
