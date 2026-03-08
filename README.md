# Redpanda Playground

TPS 트럼본(배포 플랫폼)의 간소화 버전. Redpanda 도입 구조와 정책을 코드로 설명하는 데모 프로젝트.

## 기술 스택

| 영역 | 기술 |
|------|------|
| Framework | Spring Boot 3.3.2 |
| Persistence | MyBatis 3.0.3 |
| DB | PostgreSQL 16 |
| Migration | Flyway |
| Serialization | Apache Avro 1.11.3 |
| Messaging | Spring Kafka + Redpanda |
| Outbox | MyBatis 기반 직접 구현 |
| AsyncAPI | Springwolf 2.0.0 |
| Test | JUnit 5 + ArchUnit 1.3.0 |
| Frontend | Vite 6 + React 19 + TypeScript + TanStack Query 5 |

## 실행 방법

### 인프라 (Core)
```bash
cd docker && docker compose up -d
```

### 인프라 (Full - 데모용)
```bash
cd docker && docker compose up -d && docker compose -f docker-compose.infra.yml up -d
```

### 백엔드
```bash
./gradlew bootRun
```

### 프론트엔드
```bash
cd frontend && npm install && npm run dev
```

## 포트 할당

| 서비스 | 포트 |
|--------|------|
| Spring Boot | 8080 |
| Redpanda (Kafka) | 29092 |
| Schema Registry | 28081 |
| Redpanda Console | 28080 |
| PostgreSQL | 25432 |
| Frontend (Vite) | 5173 |
| Jenkins | 29080 |
| GitLab | 29180 |
| Nexus | 28881 |
| Redpanda Connect | 4195 |
| Registry | 25050 |
| Registry UI | 25051 |

## API

### Ticket
| Method | Path | 설명 |
|--------|------|------|
| GET | /api/tickets | 목록 |
| POST | /api/tickets | 생성 |
| GET | /api/tickets/:id | 상세 |
| PUT | /api/tickets/:id | 수정 |
| DELETE | /api/tickets/:id | 삭제 |

### Pipeline
| Method | Path | 설명 |
|--------|------|------|
| POST | /api/tickets/:id/pipeline/start | 실행 시작 (202) |
| GET | /api/tickets/:id/pipeline | 최신 상태 |
| GET | /api/tickets/:id/pipeline/history | 이력 |
| GET | /api/tickets/:id/pipeline/events | SSE 스트림 |

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
| 카테고리 | toolType | 용도 |
|---------|----------|------|
| CI/CD | JENKINS | 빌드/배포 파이프라인 |
| Artifact | GITLAB, NEXUS | 소스 코드 저장소, 바이너리 아티팩트 |
| Registry | REGISTRY | 컨테이너 이미지 (Harbor/Docker Registry) |

카테고리는 프론트엔드에서 `toolType`으로부터 파생하며, DB 스키마 변경 없음.

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
http://localhost:8080/springwolf/asyncapi-ui.html
```
- **Channels**: 각 Kafka 토픽과 발행/구독 정보
- **Messages**: Avro 스키마 기반 메시지 구조
- **Schemas**: 필드별 타입, 필수 여부

### JSON 명세 다운로드
```bash
curl http://localhost:8080/springwolf/docs -o asyncapi.json
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

## 아키텍처

### 전체 흐름

```
[Frontend (React)]
  ├── ToolListPage ──── toolApi ──── /api/tools (CRUD)
  ├── SourceSelector ── sourceApi ── /api/sources/* (브라우징)
  ├── TicketCreatePage─ ticketApi ── /api/tickets (CRUD)
  └── TicketDetailPage─ pipelineApi─ /api/tickets/:id/pipeline (실행+SSE)

[Backend (Spring Boot)]
  ├── SupportToolController ── SupportToolService ── DB (support_tool)
  ├── SourceController ──────── Adapter Layer ──────── 외부 서비스
  │     ├── GitLabAdapter ──── RestTemplate ──── GitLab API v4
  │     ├── NexusAdapter ───── RestTemplate ──── Nexus REST API
  │     └── RegistryAdapter ── RestTemplate ──── Docker Registry v2
  ├── TicketController ──────── TicketService ──── DB (ticket, ticket_source)
  └── PipelineController ────── PipelineService ── Kafka(Outbox) → PipelineEngine
        ├── JenkinsCloneAndBuildStep ── Jenkins API → Webhook 대기
        ├── NexusDownloadStep ───────── Nexus API (검색+다운로드)
        ├── RegistryImagePullStep ───── Registry API (존재 확인)
        └── RealDeployStep ──────────── Jenkins API → Webhook 대기
```

### 소스 브라우징 흐름

ToolRegistry가 활성 도구의 URL/인증정보를 관리하며, 각 Adapter가 해당 정보로 외부 서비스에 REST 호출.

```
SourceController → ToolRegistry.getActiveTool(ToolType)
                 → Adapter.method() → RestTemplate → 외부 API
```

### 파이프라인 실행 흐름

파이프라인은 Outbox 패턴 + Kafka 기반 비동기 실행. Jenkins Step은 Webhook 콜백으로 결과 수신.

```
POST /pipeline/start
  → PipelineService: Execution + Steps 생성 → Outbox INSERT
  → OutboxPoller → Kafka (playground.pipeline.commands)
  → PipelineEventConsumer → PipelineEngine.execute()
    → Step 실행 (동기 or Webhook 대기)
    → SSE로 프론트엔드에 실시간 전달
```

### 프론트엔드 구조

```
src/
├── api/
│   ├── client.ts        # HTTP 클라이언트 (query params 지원)
│   ├── toolApi.ts       # 도구 CRUD
│   ├── ticketApi.ts     # 티켓 CRUD
│   ├── pipelineApi.ts   # 파이프라인 실행
│   └── sourceApi.ts     # 소스 브라우징 (GitLab/Nexus/Registry)
├── hooks/
│   ├── useTools.ts      # 도구 React Query 훅
│   ├── useTickets.ts    # 티켓 React Query 훅
│   ├── usePipeline.ts   # 파이프라인 React Query 훅
│   ├── useSources.ts    # 소스 브라우징 React Query 훅
│   └── useSSE.ts        # SSE 실시간 업데이트
├── components/
│   ├── SourceSelector.tsx    # 소스 검색/선택 (드롭다운 기반)
│   ├── ToolFormDrawer.tsx    # 도구 생성/수정 (카테고리 선택)
│   ├── PipelineTimeline.tsx  # 파이프라인 실행 타임라인
│   ├── StatusBadge.tsx       # 상태 배지
│   └── Sidebar.tsx           # 네비게이션
└── pages/
    ├── ToolListPage.tsx       # 도구 관리 (카테고리 컬럼)
    ├── TicketListPage.tsx     # 티켓 목록
    ├── TicketCreatePage.tsx   # 티켓 생성 (소스 선택)
    └── TicketDetailPage.tsx   # 티켓 상세 + 파이프라인 실행
```

## Docker 네트워크

모든 컨테이너는 `playground-net` 공유 네트워크에서 통신합니다. `docker-compose.yml`(앱 인프라)과 `docker-compose.infra.yml`(DevOps 인프라) 모두 동일 네트워크를 사용하여 Jenkins에서 Redpanda Connect(`playground-connect:4195`)로 webhook을 전송할 수 있습니다.

```yaml
# 양쪽 docker-compose 파일 공통
networks:
  default:
    name: playground-net
```
