# 프론트엔드 아키텍처

## 1. 기술 스택

| 영역 | 기술 | 버전 |
|------|------|------|
| UI 프레임워크 | React | 19 |
| 번들러 | Vite | 6 |
| 언어 | TypeScript | 5.6 |
| CSS | Tailwind CSS | 4.2.1 |
| 서버 상태 관리 | TanStack Query | ^5.60.0 |
| 라우팅 | react-router-dom | ^7.1.0 |
| DAG 시각화 | @xyflow/react (React Flow) | ^12.10.1 |
| DAG 레이아웃 | @dagrejs/dagre | ^2.0.4 |
| 패키지 매니저 | Yarn | 4.13.0 |

---

## 2. 페이지 구성 (12 pages)

| 경로 | 페이지 컴포넌트 | 설명 |
|------|----------------|------|
| `/tickets` | TicketListPage | 티켓 목록. 상태별 필터링 |
| `/tickets/new` | TicketCreatePage | 티켓 생성. 소스 유형(GIT/NEXUS/HARBOR) 선택 |
| `/tickets/:id` | TicketDetailPage | 티켓 상세 + 파이프라인 실행 이력 |
| `/jobs` | JobListPage | Job 목록. jenkins_status 포함 |
| `/jobs/new` | JobCreatePage | Job 생성. Jenkins 스크립트 편집 포함 |
| `/jobs/:id` | JobDetailPage | Job 상세 정보 조회 |
| `/jobs/:id/edit` | JobEditPage | Job 수정 |
| `/pipelines` | PipelineListPage | 파이프라인 정의 목록 |
| `/pipelines/new` | PipelineCreatePage | 파이프라인 생성. ReactFlow 기반 DAG 편집 |
| `/pipelines/:id` | PipelineDetailPage | DAG 시각화 + 실행 시작 + 실행 이력 |
| `/presets` | PresetListPage | 프리셋 CRUD. 역할별 도구 조합 관리 |
| `/tools` | ToolListPage | 도구(support_tool) 등록·수정·삭제 |

---

## 3. 핵심 컴포넌트

| 컴포넌트 | 역할 |
|----------|------|
| `LiveDagGraph` | 실시간 DAG 시각화. ReactFlow 노드/엣지 + dagre 자동 레이아웃. SSE 이벤트 수신 시 Job 상태를 노드 색상으로 반영 |
| `ExecutionCard` | 실행 상태 카드. PipelineDetailPage와 전체 이력 페이지에서 공유하는 재사용 컴포넌트 |
| `PipelineExecutionPanel` | 실행 시작 UI. 파라미터 입력 + 실행 트리거. `parameter_schema_json`을 읽어 입력 폼을 동적 생성 |
| `PipelineTimeline` | Job 실행 순서를 타임라인으로 표시. 각 Job의 시작/완료 시각과 상태를 시각화 |
| `ConfigJsonEditor` | JSON 설정 에디터. pipeline_job의 configJson 편집에 사용 |
| `StatusBadge` | PENDING/RUNNING/SUCCESS/FAILED/CANCELLED/SKIPPED 상태를 색상 배지로 표시하는 공유 컴포넌트 |
| `PresetFormDrawer` | 프리셋 생성/수정 Drawer. category별 support_tool 선택 UI |
| `ToolFormDrawer` | 도구 등록/수정 Drawer. auth_type 선택 + 인증 정보 입력 |
| `ParameterInputModal` | 파이프라인 실행 전 파라미터 값을 입력받는 모달. `parameter_schema_json` 스키마 기반 동적 렌더링 |

---

## 4. SSE + TanStack Query 연동 패턴

프론트엔드의 실시간 업데이트는 Server-Sent Events(SSE)와 TanStack Query 캐시 무효화의 조합으로 구현된다.

`useSSE` 훅이 `/api/sse/executions/{executionId}` 엔드포인트에 연결하고, 서버에서 이벤트가 도착하면 해당 실행과 관련된 TanStack Query 캐리를 `queryClient.invalidateQueries()`로 무효화한다. 무효화된 쿼리는 TanStack Query의 refetch 정책에 따라 자동으로 최신 데이터를 가져온다. 이 방식은 SSE 페이로드를 직접 파싱하여 상태를 갱신하는 것보다 단순하고, 서버를 단일 진실 공급원으로 유지할 수 있다.

```
SSE 이벤트 수신
  → useSSE 훅
  → queryClient.invalidateQueries(['execution', executionId])
  → TanStack Query 자동 refetch
  → 컴포넌트 리렌더링
```

`LiveDagGraph`는 SSE 이벤트의 job_execution 상태를 직접 읽어 ReactFlow 노드의 색상을 즉각 갱신한다. 전체 re-fetch 없이 노드 단위로 업데이트하기 때문에 DAG 그래프의 애니메이션이 끊김 없이 동작한다.

---

## 5. DAG 시각화 2모드

### Static 모드 (파이프라인 편집)

`PipelineCreatePage`와 `PipelineDetailPage`의 편집 뷰에서 사용한다. `pipeline_job_mapping`과 `pipeline_job_dependency` 데이터를 기반으로 Job 노드와 의존성 엣지를 렌더링하며, 사용자가 노드를 드래그하거나 엣지를 연결하여 DAG 구조를 수정할 수 있다. dagre 레이아웃 알고리즘이 초기 노드 배치를 자동으로 계산한다.

### Live 모드 (실행 중 실시간 상태 반영)

`PipelineDetailPage`의 실행 뷰에서 사용한다. Static 모드와 동일한 그래프 구조를 유지하면서, SSE로 수신되는 `pipeline_job_execution` 상태 변경을 실시간으로 노드 색상에 반영한다.

| Job 실행 상태 | 노드 색상 |
|--------------|----------|
| PENDING | 회색 |
| RUNNING | 파란색 |
| SUCCESS | 초록색 |
| FAILED | 빨간색 |
| CANCELLED | 어두운 회색 |
| SKIPPED | 노란색 |

---

## 6. API 클라이언트

`frontend/src/api/` 디렉토리에 도메인별로 분리된 API 모듈이 위치한다.

| 파일 | 담당 API |
|------|----------|
| `client.ts` | HTTP 기반 공통 클라이언트. base URL, 에러 처리 공통화 |
| `jobApi.ts` | pipeline_job CRUD + Jenkins 상태 조회 |
| `pipelineApi.ts` | pipeline_execution 실행 시작·조회·재시작 |
| `pipelineDefinitionApi.ts` | pipeline_definition CRUD + Job 매핑·의존성 관리 |
| `presetApi.ts` | middleware_preset + preset_entry CRUD |
| `sourceApi.ts` | ticket_source 관리 |
| `ticketApi.ts` | ticket CRUD + 상태 전이 |
| `toolApi.ts` | support_tool CRUD |

각 API 모듈은 TanStack Query의 `useQuery`/`useMutation` 훅과 조합하여 사용한다. 쿼리 키는 도메인별로 일관된 배열 형식(`['jobs']`, `['pipeline', id]`)으로 관리하여 캐시 무효화 범위를 예측 가능하게 유지한다.
