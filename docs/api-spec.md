# API 스펙 — Redpanda Playground

## 1. 개요

Redpanda Playground는 CI/CD 파이프라인을 관리하는 학습용 프로젝트로, REST API와 Kafka 비동기 메시징, SSE 실시간 스트림을 조합하여 동작한다.

| 유형 | 수량 |
|------|------|
| REST 엔드포인트 | 44개 (아래 상세) |
| Kafka 토픽 | 8개 (DLQ 포함) |
| SSE 스트림 | 1개 (티켓 파이프라인 이벤트) |
| Actuator | 4개 (health, info, prometheus, springwolf) |

### 공통 규칙

- **Base path**: `/api`
- **Content-Type**: `application/json` (SSE 제외)
- **비동기 패턴**: 파이프라인 실행 트리거는 `202 Accepted`를 반환하고, 실제 처리는 Kafka를 통해 비동기로 진행된다. 클라이언트는 응답의 `trackingUrl` 필드로 SSE를 구독하여 진행 상황을 수신한다.
- **인증**: 없음 (학습 프로젝트)
- **직렬화**: Kafka 메시지는 Avro 바이너리, REST 응답은 JSON

---

## 2. Source (`/api/sources`)

외부 소스 시스템(GitLab, Nexus, Docker Registry)의 메타데이터를 조회하는 읽기 전용 API다. 티켓 생성 시 소스 선택 UI에서 사용한다.

**컨트롤러**: `SourceController`
**파일**: `app/src/main/java/com/study/playground/adapter/SourceController.java`

| 메서드 | 경로 | 설명 | 요청 | 응답 | 비고 |
|--------|------|------|------|------|------|
| GET | `/api/sources/git/repos` | GitLab 저장소 목록 조회 | - | `GitLabProject[]` | |
| GET | `/api/sources/git/repos/{id}/branches` | 저장소 브랜치 목록 조회 | `id`: Long (path) | `GitLabBranch[]` | |
| GET | `/api/sources/nexus/artifacts` | Maven 아티팩트 검색 | `groupId`, `artifactId`: String (query) | `NexusAsset[]` | |
| GET | `/api/sources/registry/images` | Docker 이미지 목록 조회 | - | `string[]` | |
| GET | `/api/sources/registry/images/{repo}/tags` | 이미지 태그 목록 조회 | `repo`: String (path) | `string[]` | `{repo:.+}` 패턴 |

### DTO

```typescript
interface GitLabProject {
  id: number;
  name: string;
  nameWithNamespace: string;
  webUrl: string;
  defaultBranch: string;
}

interface GitLabBranch {
  name: string;
  merged: boolean;
  isProtected: boolean;
}

interface NexusAsset {
  downloadUrl: string;
  path: string;
  repository: string;
  maven2: { groupId: string; artifactId: string; version: string; };
}
```

---

## 3. Ticket (`/api/tickets`)

배포 단위인 티켓의 CRUD를 담당한다. 티켓에는 소스(Git, Nexus, Registry) 정보가 연결되며, 파이프라인 실행의 기준이 된다.

**컨트롤러**: `TicketController`
**파일**: `app/src/main/java/com/study/playground/ticket/api/TicketController.java`

| 메서드 | 경로 | 설명 | 요청 | 응답 | 비고 |
|--------|------|------|------|------|------|
| GET | `/api/tickets` | 전체 티켓 목록 조회 | - | `TicketListResponse[]` | 소스 정보 미포함 |
| GET | `/api/tickets/{id}` | 티켓 상세 조회 | `id`: Long | `TicketResponse` | 소스 정보 포함 |
| POST | `/api/tickets` | 티켓 생성 | `TicketCreateRequest` | `TicketResponse` | **201 Created** |
| PUT | `/api/tickets/{id}` | 티켓 수정 | `TicketCreateRequest` | `TicketResponse` | |
| DELETE | `/api/tickets/{id}` | 티켓 삭제 | `id`: Long | - | **204 No Content** |

### DTO

```typescript
// 요청
interface TicketCreateRequest {
  name: string;          // 필수, 최대 200자
  description?: string;
  sources?: TicketSourceRequest[];
}

interface TicketSourceRequest {
  sourceType: string;    // 필수 ("GIT" | "NEXUS" | "REGISTRY")
  repoUrl?: string;
  branch?: string;
  artifactCoordinate?: string;
  imageName?: string;
}

// 응답 (목록)
interface TicketListResponse {
  id: number;
  name: string;
  status: string;        // "DRAFT" | "RUNNING" | "SUCCESS" | "FAILED"
  createdAt: string;     // ISO 8601
  updatedAt: string;
}

// 응답 (상세)
interface TicketResponse {
  id: number;
  name: string;
  description: string | null;
  status: string;
  sources: TicketSourceResponse[];
  createdAt: string;
  updatedAt: string;
}

interface TicketSourceResponse {
  id: number;
  sourceType: string;
  repoUrl: string | null;
  branch: string | null;
  artifactCoordinate: string | null;
  imageName: string | null;
}
```

---

## 4. Tool (`/api/tools`)

미들웨어 도구(Jenkins, Nexus, Harbor, GitLab 등)의 연결 정보를 관리한다. Preset에 연결되어 파이프라인 실행 시 실제 미들웨어 접속에 사용된다.

**컨트롤러**: `SupportToolController`
**파일**: `app/src/main/java/com/study/playground/supporttool/api/SupportToolController.java`

| 메서드 | 경로 | 설명 | 요청 | 응답 | 비고 |
|--------|------|------|------|------|------|
| GET | `/api/tools` | 전체 도구 목록 조회 | - | `SupportToolResponse[]` | |
| GET | `/api/tools/{id}` | 도구 상세 조회 | `id`: Long | `SupportToolResponse` | |
| POST | `/api/tools` | 도구 등록 | `SupportToolRequest` | `SupportToolResponse` | **201 Created** |
| PUT | `/api/tools/{id}` | 도구 수정 | `SupportToolRequest` | `SupportToolResponse` | |
| DELETE | `/api/tools/{id}` | 도구 삭제 | `id`: Long | - | **204 No Content** |
| POST | `/api/tools/{id}/test` | 연결 테스트 | `id`: Long | `{ reachable: boolean }` | |

### DTO

```typescript
// 요청
interface SupportToolRequest {
  category: string;       // 필수 ("SCM" | "CI" | "ARTIFACT" | "REGISTRY")
  implementation: string; // 필수 ("JENKINS" | "GITLAB" | "NEXUS" | "HARBOR")
  name: string;           // 필수
  url: string;            // 필수
  authType?: string;      // "BASIC" | "TOKEN" | "NONE"
  username?: string;
  credential?: string;
  active?: boolean;       // 기본값 true
}

// 응답
interface SupportToolResponse {
  id: number;
  category: string;
  implementation: string;
  name: string;
  url: string;
  authType: string | null;
  username: string | null;
  hasCredential: boolean;  // credential 존재 여부만 노출
  active: boolean;
  createdAt: string;
  updatedAt: string;
}
```

---

## 5. Preset (`/api/presets`)

미들웨어 도구 조합을 프리셋으로 묶어 관리한다. 파이프라인 Job이 프리셋을 참조하여 실행에 필요한 미들웨어 세트를 결정한다.

**컨트롤러**: `PresetController`
**파일**: `app/src/main/java/com/study/playground/preset/api/PresetController.java`

| 메서드 | 경로 | 설명 | 요청 | 응답 | 비고 |
|--------|------|------|------|------|------|
| GET | `/api/presets` | 전체 프리셋 목록 조회 | - | `PresetResponse[]` | |
| GET | `/api/presets/{id}` | 프리셋 상세 조회 | `id`: Long | `PresetResponse` | |
| POST | `/api/presets` | 프리셋 생성 | `PresetRequest` | `PresetResponse` | **201 Created** |
| PUT | `/api/presets/{id}` | 프리셋 수정 | `PresetRequest` | `PresetResponse` | |
| DELETE | `/api/presets/{id}` | 프리셋 삭제 | `id`: Long | - | **204 No Content** |

### DTO

```typescript
// 요청
interface PresetRequest {
  name: string;            // 필수
  description?: string;
  entries: EntryRequest[];  // 필수, 1개 이상
}

interface EntryRequest {
  category: string;  // 필수 ("SCM" | "CI" | "ARTIFACT" | "REGISTRY")
  toolId: number;    // 필수
}

// 응답
interface PresetResponse {
  id: number;
  name: string;
  description: string | null;
  entries: EntryResponse[];
  createdAt: string;
  updatedAt: string;
}

interface EntryResponse {
  id: number;
  category: string;
  toolId: number;
  toolName: string;
  toolUrl: string;
  toolImplementation: string | null;
}
```

---

## 6. Job (`/api/jobs`)

파이프라인을 구성하는 개별 작업 단위다. Jenkins Job과 1:1로 매핑되며, 독립적으로 CRUD하고 단독 실행할 수 있다.

**컨트롤러**: `JobController`
**파일**: `pipeline/src/main/java/com/study/playground/pipeline/api/JobController.java`

| 메서드 | 경로 | 설명 | 요청 | 응답 | 비고 |
|--------|------|------|------|------|------|
| GET | `/api/jobs` | 전체 Job 목록 조회 | - | `JobResponse[]` | |
| GET | `/api/jobs/{id}` | Job 상세 조회 | `id`: Long | `JobResponse` | |
| POST | `/api/jobs` | Job 생성 | `JobRequest` | `JobResponse` | **201 Created** |
| PUT | `/api/jobs/{id}` | Job 수정 | `JobRequest` | `JobResponse` | |
| DELETE | `/api/jobs/{id}` | Job 삭제 | `id`: Long | - | **204 No Content** |
| POST | `/api/jobs/{id}/execute` | Job 단독 실행 | `PipelineExecuteRequest?` | `PipelineExecutionResponse` | **202 Accepted** `async` |
| POST | `/api/jobs/{id}/retry-provision` | Jenkins 프로비저닝 재시도 | `id`: Long | - | **202 Accepted** |

### DTO

```typescript
// 요청
interface JobRequest {
  jobName: string;             // 필수, 최대 200자
  jobType: string;             // 필수 ("BUILD" | "DEPLOY" | "ARTIFACT_DOWNLOAD" | "CUSTOM")
  presetId: number;            // 필수
  configJson?: string;         // Job 타입별 설정 JSON
  jenkinsScript?: string;      // 사용자 정의 Jenkinsfile
  parameterSchemaJson?: string; // 파라미터 스키마 JSON
}

// 응답
interface JobResponse {
  id: number;
  jobName: string;
  jobType: string;
  presetId: number;
  presetName: string | null;
  configJson: string | null;
  jenkinsScript: string | null;
  jenkinsStatus: string | null; // "PENDING" | "PROVISIONED" | "FAILED"
  parameterSchemas: ParameterSchema[] | null;
  createdAt: string;
}

interface ParameterSchema {
  name: string;
  type: string;       // "STRING" | "BOOLEAN" | "CHOICE"
  defaultValue: string | null;
  description: string | null;
  required: boolean;
  choices: string[] | null;
}
```

---

## 7. Pipeline - Ticket scope (`/api/tickets/{ticketId}/pipeline`)

티켓 기반 파이프라인 실행을 트리거하고 이력을 조회한다. 실행 시작은 Outbox 패턴으로 비동기 발행되며, 실시간 진행 상황은 SSE 엔드포인트로 구독한다.

**컨트롤러**: `PipelineController`, `PipelineSseController`
**파일**: `app/src/main/java/com/study/playground/pipeline/api/PipelineController.java`, `pipeline/src/main/java/com/study/playground/pipeline/api/PipelineSseController.java`

| 메서드 | 경로 | 설명 | 요청 | 응답 | 비고 |
|--------|------|------|------|------|------|
| POST | `/api/tickets/{ticketId}/pipeline/start` | 파이프라인 시작 | `ticketId`: Long | `PipelineExecutionResponse` | **202 Accepted** `async` |
| GET | `/api/tickets/{ticketId}/pipeline` | 최근 실행 결과 조회 | `ticketId`: Long | `PipelineExecutionResponse` | |
| GET | `/api/tickets/{ticketId}/pipeline/history` | 전체 실행 이력 조회 | `ticketId`: Long | `PipelineExecutionResponse[]` | |
| GET | `/api/tickets/{ticketId}/pipeline/events` | SSE 이벤트 스트림 구독 | `ticketId`: Long | `text/event-stream` | SSE, Section 11 참조 |

### DTO

```typescript
interface PipelineExecutionResponse {
  executionId: string;      // UUID
  ticketId: number | null;  // DAG 모드에서는 null
  status: string;           // "PENDING" | "RUNNING" | "SUCCESS" | "FAILED"
  jobExecutions: PipelineJobExecutionResponse[] | null;
  startedAt: string | null;
  completedAt: string | null;
  errorMessage: string | null;
  parameters: Record<string, string> | null;
  trackingUrl: string;      // SSE 구독 URL
}

interface PipelineJobExecutionResponse {
  id: number;
  jobOrder: number;
  jobType: string;    // "BUILD" | "DEPLOY" | "ARTIFACT_DOWNLOAD" | "CUSTOM"
  jobName: string;
  status: string;     // "PENDING" | "RUNNING" | "SUCCESS" | "FAILED" | "WAITING_WEBHOOK"
  log: string | null;
  retryCount: number;
  startedAt: string | null;
  completedAt: string | null;
}
```

---

## 8. Pipeline Definition - DAG (`/api/pipelines`)

DAG(Directed Acyclic Graph) 기반 파이프라인 정의를 관리한다. 티켓과 무관하게 Job 간 의존관계를 설정하고 실행할 수 있다.

**컨트롤러**: `PipelineDefinitionController`
**파일**: `pipeline/src/main/java/com/study/playground/pipeline/dag/api/PipelineDefinitionController.java`

| 메서드 | 경로 | 설명 | 요청 | 응답 | 비고 |
|--------|------|------|------|------|------|
| GET | `/api/pipelines` | 전체 파이프라인 정의 목록 | - | `PipelineDefinitionResponse[]` | |
| GET | `/api/pipelines/{id}` | 파이프라인 정의 상세 | `id`: Long | `PipelineDefinitionResponse` | |
| POST | `/api/pipelines` | 파이프라인 정의 생성 | `PipelineDefinitionRequest` | `PipelineDefinitionResponse` | **201 Created** |
| PUT | `/api/pipelines/{id}/mappings` | Job 매핑 업데이트 (DAG 구성) | `PipelineJobMappingRequest[]` | `PipelineDefinitionResponse` | |
| DELETE | `/api/pipelines/{id}` | 파이프라인 정의 삭제 | `id`: Long | - | **204 No Content** |
| POST | `/api/pipelines/{id}/execute` | 파이프라인 실행 | `PipelineExecuteRequest?` | `PipelineExecutionResponse` | **202 Accepted** `async` |
| POST | `/api/pipelines/{id}/executions/{executionId}/restart` | 실패한 실행 재시작 | `PipelineExecuteRequest?` | `PipelineExecutionResponse` | **202 Accepted** `async` |
| GET | `/api/pipelines/{id}/executions` | 파이프라인 실행 이력 | `id`: Long | `PipelineExecutionResponse[]` | |
| GET | `/api/pipelines/executions/{executionId}` | 단일 실행 상세 | `executionId`: UUID | `PipelineExecutionResponse` | |
| GET | `/api/pipelines/executions/{executionId}/dag-graph` | DAG 그래프 전체 | `executionId`: UUID | `DagGraphResponse` | Grafana Node Graph용 |
| GET | `/api/pipelines/executions/{executionId}/dag-graph/nodes` | DAG 노드 목록 | `executionId`: UUID | `DagGraphResponse.Node[]` | |
| GET | `/api/pipelines/executions/{executionId}/dag-graph/edges` | DAG 엣지 목록 | `executionId`: UUID | `DagGraphResponse.Edge[]` | |

### DTO

```typescript
// 요청
interface PipelineDefinitionRequest {
  name: string;          // 필수, 최대 200자
  description?: string;
}

interface PipelineJobMappingRequest {
  jobId: number;             // 필수
  executionOrder: number;    // 필수
  dependsOnJobIds?: number[]; // DAG 의존관계
}

interface PipelineExecuteRequest {
  params?: Record<string, string>; // 런타임 파라미터
}

// 응답
interface PipelineDefinitionResponse {
  id: number;
  name: string;
  description: string | null;
  status: string;
  failurePolicy: string | null;  // "FAIL_FAST" | "CONTINUE_ON_FAILURE"
  createdAt: string;
  jobs: PipelineJobResponse[];
}

interface PipelineJobResponse {
  id: number;
  jobName: string;
  jobType: string;
  executionOrder: number;
  presetId: number;
  presetName: string | null;
  configJson: string | null;
  parameterSchemas: ParameterSchema[] | null;
  dependsOnJobIds: number[];
}

// DAG 그래프 (Grafana Node Graph 형식)
interface DagGraphResponse {
  nodes: DagNode[];
  edges: DagEdge[];
}

interface DagNode {
  id: string;
  title: string;     // Job 이름
  subTitle: string;   // Job 타입
  mainStat: string;   // 상태
  color: string;      // 상태별 색상
}

interface DagEdge {
  id: string;
  source: string;     // 선행 Job ID
  target: string;     // 후행 Job ID
}
```

---

## 9. Actuator (`/actuator`)

Spring Boot Actuator가 제공하는 운영 엔드포인트다.

| 메서드 | 경로 | 설명 | 비고 |
|--------|------|------|------|
| GET | `/actuator/health` | 헬스체크 | K8s liveness/readiness probe |
| GET | `/actuator/info` | 앱 정보 | |
| GET | `/actuator/prometheus` | Prometheus 메트릭 | Grafana 대시보드 연동 |
| GET | `/springwolf/asyncapi` | AsyncAPI 명세 | Kafka 토픽/스키마 자동 생성 |

---

## 10. Kafka 토픽

모든 토픽은 Avro 바이너리로 직렬화되며, `TopicConfig`에서 선언적으로 관리된다.

| 토픽명 | 파티션 | 보관 | 용도 | 프로듀서 | 컨슈머 |
|--------|--------|------|------|----------|--------|
| `playground.pipeline.commands.execution` | 3 | 7일 | 파이프라인 실행 시작 커맨드 | OutboxPoller | PipelineEventConsumer (`pipeline-engine`) |
| `playground.pipeline.commands.jenkins` | 3 | 7일 | Jenkins Job 실행 커맨드 | PipelineEngine | (Jenkins 어댑터) |
| `playground.pipeline.events.step-changed` | 3 | 7일 | Job 스텝 상태 변경 이벤트 | PipelineEngine | PipelineSseConsumer (`pipeline-sse`) |
| `playground.pipeline.events.completed` | 3 | 7일 | 파이프라인 실행 완료 이벤트 | PipelineEngine | PipelineSseConsumer (`pipeline-sse`), TicketStatusEventConsumer (`ticket-status-updater`) |
| `playground.pipeline.events.dag-job` | 3 | 7일 | DAG Job 단위 실행 이벤트 | DAG 엔진 | (DAG 스케줄러) |
| `playground.ticket.events` | 3 | 7일 | 티켓 상태 변경 이벤트 | TicketService | (이벤트 구독자) |
| `playground.webhook.inbound` | 2 | 3일 | Jenkins 웹훅 수신 이벤트 | Redpanda Connect | WebhookEventConsumer (`webhook-processor`) |
| `playground.audit.events` | 1 | 30일 | 감사 로그 이벤트 | AuditEventPublisher | AuditEventConsumer (`audit-logger`) |
| `playground.dlq` | 1 | 30일 | Dead Letter Queue | KafkaErrorConfig | DlqConsumer (`dlq-handler`) |

### 컨슈머 그룹 요약

| 그룹 ID | 구독 토픽 | 역할 |
|---------|----------|------|
| `pipeline-engine` | `commands.execution` | 파이프라인 엔진 실행 |
| `pipeline-sse` | `events.step-changed`, `events.completed` | SSE 클라이언트 푸시 |
| `ticket-status-updater` | `events.completed` | 티켓 상태 자동 갱신 |
| `webhook-processor` | `webhook.inbound` | Jenkins 웹훅 처리 |
| `audit-logger` | `audit.events` | 감사 로그 기록 |
| `dlq-handler` | `playground.dlq` | DLQ 메시지 로깅 |

---

## 11. SSE 이벤트

### 엔드포인트

| 경로 | 대상 |
|------|------|
| `GET /api/tickets/{ticketId}/pipeline/events` | 티켓 기반 파이프라인 |
| `GET /api/pipelines/executions/{executionId}/events` | DAG 파이프라인 (trackingUrl로 제공) |

### 이벤트 타입

클라이언트는 `EventSource`로 구독하며, 서버는 다음 이벤트 타입을 전송한다.

| 이벤트 타입 | Kafka 소스 토픽 | 발생 시점 | 데이터 |
|------------|----------------|----------|--------|
| `status` | `events.step-changed` | Job 스텝 상태가 변경될 때 | `PipelineStepChangedEvent` (Avro JSON) |
| `completed` | `events.completed` | 파이프라인 실행이 완료될 때 | `PipelineExecutionCompletedEvent` (Avro JSON) |

`completed` 이벤트 수신 후 서버가 SSE 연결을 종료(`complete`)하므로, 클라이언트는 스트림 끝을 자연스럽게 인지할 수 있다.

### 구독 예시

```javascript
const es = new EventSource('/api/tickets/1/pipeline/events');

es.addEventListener('status', (e) => {
  const data = JSON.parse(e.data);
  // { executionId, ticketId, jobOrder, jobType, status, ... }
});

es.addEventListener('completed', (e) => {
  const data = JSON.parse(e.data);
  // { executionId, ticketId, finalStatus, ... }
  es.close();
});
```
