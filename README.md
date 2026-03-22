# Redpanda Playground

TPS 트럼본(배포 플랫폼)의 간소화 버전. Redpanda 도입 구조와 정책을 코드로 설명하는 데모 프로젝트.

## 기술 스택

| 영역 | 기술 |
|------|------|
| Framework | Spring Boot 3.4.0 |
| Language | Java 21 |
| Persistence | MyBatis 3.0.3 |
| DB | PostgreSQL (Bitnami Helm) |
| Migration | Flyway (V1~V38) |
| Serialization | Apache Avro 1.12.1 |
| Messaging | Spring Kafka + Redpanda |
| Outbox | MyBatis 기반 직접 구현 |
| AsyncAPI | Springwolf 1.21.0 |
| Test | JUnit 5 + ArchUnit 1.3.0 |
| Frontend | Vite 6 + React 19 + TypeScript + TanStack Query 5 |
| Observability | OpenTelemetry + Grafana + Loki + Tempo |
| Infra | K8s (kubeadm 3-node) + Helm |

## 실행 방법

### GCP 환경 (기본)

```bash
# 백엔드 (GCP 프로필 — K8s 인프라 사용)
make backend

# 프론트엔드
make frontend
```

### 로컬 환경

```bash
# 인프라 (Docker Compose)
make infra-all

# 백엔드
make backend-local

# 프론트엔드
make frontend
```

### 개발 안내

```bash
make dev    # 접속 URL 목록 출력
make help   # 전체 명령어 목록
```

## 인프라 (K8s — kubeadm 3-node 클러스터)

GCP에 kubeadm으로 구축한 3대 K8s 클러스터에서 모든 인프라 서비스를 운영한다.

### K8s 서비스 (네임스페이스별)

| 서비스 | 네임스페이스 | NodePort | 비고 |
|--------|-------------|----------|------|
| Redpanda (Kafka) | rp-oss | 31092 | Helm |
| Schema Registry | rp-oss | 31081 | |
| Console | rp-oss | 31880 | |
| Connect | rp-oss | 31195 | 단일포트, 스트림명 프리픽스 |
| PostgreSQL | rp-oss | 30275 | Bitnami Helm |
| Nexus | rp-oss | 31280 | Plain manifest |
| Jenkins | rp-jenkins | 31080 | Helm |
| Grafana | rp-mgm | 30000 | |
| Alloy (OTLP) | rp-mgm | 30317/30318 | |
| GitLab CE | rp-gitlab | 31480 | Helm |
| Playground API | rp-oss | 31070 | |
| Playground Frontend | rp-oss | 31080 | |
| ArgoCD | argocd | 31134 | |

### 앱 연결 (`application-gcp.yml`)

| 서비스 | 포트 | 비고 |
|--------|------|------|
| Kafka | 31092 | K8s NodePort |
| Schema Registry | 31081 | K8s NodePort |
| PostgreSQL | 30275 | K8s NodePort |
| Connect | 31195 | K8s NodePort |
| OTel (Alloy) | 4318 | K8s (Server 3) |

> `/etc/hosts`에 `34.47.83.38 redpanda-0` 추가 필요 (Kafka advertised listener가 `redpanda-0` 호스트명 반환)

### 로컬 포트 (Docker Compose)

| 서비스 | 포트 |
|--------|------|
| Spring Boot | 8070 |
| Redpanda (Kafka) | 29092 (로컬 Docker) |
| Schema Registry | 28081 (로컬 Docker) |
| Console | 28080 |
| PostgreSQL | 25432 |
| Frontend (Vite) | 5170 |
| Connect | 4195 |
| Grafana | 23000 |
| Prometheus | 29090 |

### Helm 차트 구조

```
infra/k8s/
├── oss-install.sh              # OSS 일괄 설치
├── postgresql/                  # bitnami/postgresql
├── redpanda/                    # redpanda/redpanda
├── connect/                     # plain manifests
├── console/                     # redpanda/console
├── playground/                  # 커스텀 차트 (Spring Boot 백엔드)
├── playground-frontend/         # 커스텀 차트 (React + nginx)
├── jenkins/                     # jenkins/jenkins
└── monitoring/                  # LGMT 스택
    ├── install.sh
    ├── loki/
    ├── tempo/
    ├── prometheus/
    ├── alloy/
    └── grafana/

infra/argocd/
├── install.sh                   # ArgoCD Application 일괄 등록
├── project.yaml                 # AppProject
├── postgresql.yaml ... 12개     # Application manifests
```

### Docker 이미지 빌드

```bash
# 백엔드
docker build -t playground:latest .

# 프론트엔드
docker build -t playground-frontend:latest frontend/
```

### 빠른 설치

```bash
# OSS 스택 (PostgreSQL, Redpanda, Connect, Console, App, Frontend)
cd infra/k8s && ./oss-install.sh

# 모니터링 스택 (Loki, Tempo, Prometheus, Alloy, Grafana)
cd infra/k8s/monitoring && ./install.sh

# ArgoCD Application 등록 (GitOps)
cd infra/argocd && ./install.sh
```

## API

### Ticket
| Method | Path | 설명 |
|--------|------|------|
| GET | /api/tickets | 목록 |
| POST | /api/tickets | 생성 |
| GET | /api/tickets/:id | 상세 |
| PUT | /api/tickets/:id | 수정 |
| DELETE | /api/tickets/:id | 삭제 |

### Pipeline (티켓 기반)
| Method | Path | 설명 |
|--------|------|------|
| POST | /api/tickets/:id/pipeline/start | 실행 시작 (202) |
| GET | /api/tickets/:id/pipeline | 최신 상태 |
| GET | /api/tickets/:id/pipeline/history | 이력 |
| GET | /api/tickets/:id/pipeline/events | SSE 스트림 |

### DAG Pipeline
| Method | Path | 설명 |
|--------|------|------|
| GET | /api/pipelines | 파이프라인 목록 |
| POST | /api/pipelines | 파이프라인 생성 (DAG 정의) |
| GET | /api/pipelines/:id | 파이프라인 상세 |
| PUT | /api/pipelines/:id | 파이프라인 수정 |
| DELETE | /api/pipelines/:id | 파이프라인 삭제 |
| POST | /api/pipelines/:id/execute | 파이프라인 실행 |
| POST | /api/pipelines/:id/executions/:execId/restart | 부분 재시작 |

### Job
| Method | Path | 설명 |
|--------|------|------|
| GET | /api/jobs | Job 목록 |
| POST | /api/jobs | Job 생성 |
| GET | /api/jobs/:id | Job 상세 |
| PUT | /api/jobs/:id | Job 수정 |
| DELETE | /api/jobs/:id | Job 삭제 |
| POST | /api/jobs/:id/execute | Job 단독 실행 (202) |
| GET | /api/jobs/:id/executions | Job 실행 이력 |

### Preset
| Method | Path | 설명 |
|--------|------|------|
| GET | /api/presets | 프리셋 목록 |
| POST | /api/presets | 프리셋 생성 |
| GET | /api/presets/:id | 프리셋 상세 |
| PUT | /api/presets/:id | 프리셋 수정 |
| DELETE | /api/presets/:id | 프리셋 삭제 |

### Support Tool (개발지원도구)
| Method | Path | 설명 |
|--------|------|------|
| GET | /api/tools | 전체 목록 |
| GET | /api/tools/:id | 단건 조회 |
| POST | /api/tools | 등록 |
| PUT | /api/tools/:id | 수정 |
| DELETE | /api/tools/:id | 삭제 |
| POST | /api/tools/:id/test | 연결 테스트 |

#### 도구 카테고리
| 카테고리 | implementation | 용도 |
|---------|---------------|------|
| CI_CD_TOOL | JENKINS | 빌드/배포 파이프라인 |
| VCS | GITLAB | 소스 코드 저장소 |
| LIBRARY | NEXUS | 바이너리 아티팩트 |
| CONTAINER_REGISTRY | DOCKER_REGISTRY | 컨테이너 이미지 |

### Source Browsing (소스 브라우징)

등록된 활성 도구를 통해 외부 서비스의 리소스를 검색/조회하는 API. 티켓 생성 시 소스 선택 UX에 사용.

| Method | Path | 설명 |
|--------|------|------|
| GET | /api/sources/git/repos | GitLab 프로젝트 목록 |
| GET | /api/sources/git/repos/:id/branches | 프로젝트 브랜치 목록 |
| GET | /api/sources/nexus/artifacts?groupId=&artifactId= | Nexus 아티팩트 검색 |
| GET | /api/sources/registry/images | Registry 이미지 목록 |
| GET | /api/sources/registry/images/:repo/tags | 이미지 태그 목록 |

### AsyncAPI
| Path | 설명 |
|------|------|
| /springwolf/asyncapi-ui.html | 이벤트 명세 UI |
| /springwolf/docs | JSON 명세 |

## AsyncAPI 시각화

Springwolf가 `@KafkaListener`와 `KafkaTemplate` 사용을 자동 스캔하여 AsyncAPI 명세를 생성합니다.

### 브라우저에서 확인
```
http://localhost:8070/springwolf/asyncapi-ui.html
```
- **Channels**: 각 Kafka 토픽과 발행/구독 정보
- **Messages**: Avro 스키마 기반 메시지 구조
- **Schemas**: 필드별 타입, 필수 여부

### JSON 명세 다운로드
```bash
curl http://localhost:8070/springwolf/docs -o asyncapi.json
```

### AsyncAPI Studio로 편집/시각화
1. https://studio.asyncapi.com 접속
2. 좌측 에디터에 JSON 붙여넣기 (또는 File → Import)
3. 우측에서 인터랙티브 문서 확인

### AsyncAPI CLI로 HTML 생성
```bash
npm install -g @asyncapi/cli
asyncapi generate fromTemplate asyncapi.json @asyncapi/html-template -o docs/asyncapi-html
open docs/asyncapi-html/index.html
```

## 프론트엔드 (React 19 + Vite 6)

| 경로 | 페이지 | 설명 |
|------|--------|------|
| /tickets | 티켓 관리 | CRUD + 소스 관리 |
| /jobs | Job 관리 | CRUD + Jenkins 프로비저닝 |
| /pipelines | 파이프라인 관리 | DAG 정의 + 실행 |
| /pipelines/:id | 파이프라인 상세 | DAG 그래프 + 실행 로그 |
| /presets | 프리셋 관리 | 미들웨어 연결점 CRUD |
| /tools | 도구 관리 | Jenkins/Nexus/GitLab 등록 |

## 아키텍처

### 전체 흐름

```
[Frontend (React)]
  ├── ToolListPage ──── toolApi ──── /api/tools (CRUD)
  ├── SourceSelector ── sourceApi ── /api/sources/* (브라우징)
  ├── TicketCreatePage─ ticketApi ── /api/tickets (CRUD)
  ├── TicketDetailPage─ pipelineApi─ /api/tickets/:id/pipeline (실행+SSE)
  ├── JobListPage ───── jobApi ───── /api/jobs (CRUD)
  ├── PipelineListPage─ pipelineDefinitionApi─ /api/pipelines (DAG 정의)
  └── PipelineDetailPage─ DAG 실행/재시작/모니터링

[Backend (Spring Boot)]
  ├── SupportToolController ── SupportToolService ── DB (support_tool)
  ├── SourceController ──────── Adapter Layer ──────── 외부 서비스
  │     ├── GitLabAdapter ──── RestTemplate ──── GitLab API v4
  │     ├── NexusAdapter ───── RestTemplate ──── Nexus REST API
  │     └── RegistryAdapter ── RestTemplate ──── Docker Registry v2
  ├── TicketController ──────── TicketService ──── DB (ticket, ticket_source)
  ├── PipelineController ────── PipelineService ── Kafka(Outbox) → PipelineEngine
  │     ├── JenkinsCloneAndBuildStep ── Jenkins API → Webhook 대기
  │     ├── NexusDownloadStep ───────── Nexus API (검색+다운로드)
  │     ├── RegistryImagePullStep ───── Registry API (존재 확인)
  │     └── JenkinsDeployStep ─────── Jenkins API → Webhook 대기
  ├── JobController ─────────── JobService ─── DB (pipeline_job)
  └── PipelineDefinitionController ── PipelineDefinitionService
        └── DagExecutionCoordinator ── DAG 의존성 순서 실행
```

### DAG 파이프라인 실행 흐름

```
POST /api/pipelines/{id}/execute
  → DAG 검증 (순환 참조 탐지)
  → PipelineExecution + JobExecutions 생성
  → DagExecutionCoordinator.startExecution()
    → 루트 Job 병렬 실행 (의존성 없는 Job)
    → Job 완료 → 후속 Job 의존성 충족 확인 → 실행
    → 실패 시 FailurePolicy 적용
      → STOP_ALL: 나머지 취소
      → SKIP_DOWNSTREAM: 하위 스킵
      → FAIL_FAST: 즉시 실패
    → 전체 완료 → Avro 이벤트 발행
```

### 소스 브라우징 흐름

ToolRegistry가 활성 도구의 URL/인증정보를 관리하며, 각 Adapter가 해당 정보로 외부 서비스에 REST 호출.

```
SourceController → ToolRegistry.getActiveTool(ToolType)
                 → Adapter.method() → RestTemplate → 외부 API
```

### 파이프라인 실행 흐름 (티켓 기반)

파이프라인은 Outbox 패턴 + Kafka 기반 비동기 실행. Jenkins Job은 Webhook 콜백으로 결과 수신.

**Jenkins 시스템 파라미터**: `EXECUTION_ID`, `STEP_ORDER`는 `buildWithParameters` 쿼리로 자동 전달.
Jenkins에 파라미터 선언 없이 환경변수(`env.X`)로 접근하므로 사용자에게 노출되지 않는다.
전역 RunListener가 빌드 완료 시 Connect로 자동 콜백하므로, 개별 스크립트에 webhook 블록이 불필요하다.

**빌드→배포 데이터 전달**: BUILD Job 완료 시 `context_json`에 `ARTIFACT_URL_{jobId}` 저장.
DEPLOY Job의 configJson에서 `${ARTIFACT_URL_{jobId}}` 플레이스홀더로 참조.
DEPLOY 단독 실행 시에는 사용자가 `ARTIFACT_URL` 파라미터를 직접 입력.

```
POST /pipeline/start
  → PipelineService: Execution + JobExecutions 생성 → Outbox INSERT
  → OutboxPoller → Kafka (playground.pipeline.commands)
  → PipelineEventConsumer → PipelineEngine.execute()
    → Job 실행 (동기 or Webhook 대기)
    → SSE로 프론트엔드에 실시간 전달
```

### 프론트엔드 구조

```
src/
├── api/
│   ├── client.ts               # HTTP 클라이언트 (query params 지원)
│   ├── toolApi.ts              # 도구 CRUD
│   ├── ticketApi.ts            # 티켓 CRUD
│   ├── pipelineApi.ts          # 파이프라인 실행 (티켓 기반)
│   ├── pipelineDefinitionApi.ts # DAG 파이프라인 정의
│   ├── jobApi.ts               # Job CRUD
│   ├── sourceApi.ts            # 소스 브라우징
│   └── presetApi.ts            # 미들웨어 프리셋
├── hooks/
│   ├── useTools.ts             # 도구 React Query 훅
│   ├── useTickets.ts           # 티켓 React Query 훅
│   ├── usePipeline.ts          # 파이프라인 React Query 훅
│   ├── usePipelineDefinition.ts # DAG 파이프라인 훅
│   ├── useJobs.ts              # Job React Query 훅
│   ├── useSources.ts          # 소스 브라우징 React Query 훅
│   └── useSSE.ts               # SSE 실시간 업데이트
├── components/
│   ├── DagGraph.tsx            # DAG 시각화 그래프
│   ├── ErrorBoundary.tsx       # 에러 바운더리
│   ├── JobSelector.tsx         # Job 선택 (파이프라인 구성)
│   ├── ParameterInputModal.tsx # 파라미터 입력 모달
│   ├── PipelineExecutionPanel.tsx # 실행 결과 패널
│   ├── PipelineJobForm.tsx     # Job 생성/수정 폼
│   ├── PipelineTimeline.tsx    # 실행 타임라인
│   ├── Sidebar.tsx             # 네비게이션
│   ├── SourceSelector.tsx      # 소스 검색/선택
│   ├── StatusBadge.tsx         # 상태 배지
│   └── ToolFormDrawer.tsx      # 도구 생성/수정 드로어
└── pages/
    ├── ToolListPage.tsx        # 도구 관리
    ├── TicketListPage.tsx      # 티켓 목록
    ├── TicketCreatePage.tsx    # 티켓 생성 (소스 선택)
    ├── TicketDetailPage.tsx    # 티켓 상세 + 파이프라인 실행
    ├── JobListPage.tsx         # Job 목록
    ├── JobDetailPage.tsx       # Job 상세
    ├── PipelineListPage.tsx    # DAG 파이프라인 목록
    └── PipelineDetailPage.tsx  # DAG 파이프라인 상세 + 실행
```

## K8s 네트워크

GCP kubeadm 클러스터에서 모든 인프라 서비스가 K8s Pod로 실행된다.
앱은 NodePort를 통해 외부에서 K8s 서비스에 접근한다.

```
[로컬 개발 머신]
  └── Spring Boot (localhost:8070)
        ├── Kafka ──── 34.47.83.38:31092 (K8s rp-oss, NodePort)
        ├── Schema Registry ── 34.47.83.38:31081 (K8s rp-oss, NodePort)
        ├── PostgreSQL ── 34.47.83.38:30275 (K8s rp-oss, NodePort)
        ├── Connect ── 34.47.83.38:31195 (K8s rp-oss, NodePort)
        ├── Nexus ── 34.47.83.38:31280 (K8s rp-oss, NodePort)
        └── OTel ── 34.22.78.240:4318 (K8s rp-mgm, Server 3)
```
