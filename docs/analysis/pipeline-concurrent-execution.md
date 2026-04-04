# 파이프라인 동시 실행 분석: 2-토픽 DAG 패턴

> 분석 일자: 2026-03-23
> 시나리오: 다수 파이프라인(각 N개 Job 병렬) 동시 요청, 파이프라인 단위 순차 실행

## 1. 아키텍처 개요

### 핵심 설계: 2-토픽 분리

| 토픽 | 파티션 | 역할 |
|------|--------|------|
| `commands.execution` | **1** | Control 토픽 — 파이프라인 실행 순서 보장 |
| `commands.jenkins` | 3 | Task 토픽 — Jenkins 빌드 커맨드 병렬 처리 |

Control 토픽이 **1파티션 + concurrency=1 consumer**로 전역 순서를 보장한다. Consumer가 파이프라인 완료까지 **블로킹**하므로 앞선 파이프라인이 끝나야 다음 메시지를 소비한다. 별도의 인메모리 큐나 글로벌 카운터 없이 **Kafka 자체가 순서 제어**를 담당한다.

### 전체 아키텍처 흐름도

```mermaid
flowchart LR
    subgraph API["Pipeline API"]
        REQ[POST /execute]
    end

    subgraph Outbox["Transactional Outbox"]
        OT[(outbox 테이블)]
        OP[OutboxPoller<br>500ms]
    end

    subgraph Control["Control 토픽 (순서 보장)"]
        CT{{commands.execution<br>1파티션, Avro}}
    end

    subgraph Consumer["Control Consumer"]
        PEC[PipelineEvent<br>Consumer<br>concurrency=1]
        PE[PipelineEngine]
        DAG[DagExecution<br>Coordinator]
        WAIT["completionFuture<br>.get() 블로킹"]
    end

    subgraph Task["Task 토픽 (병렬 처리)"]
        JT{{commands.jenkins<br>3파티션, JSON}}
    end

    subgraph AppConsumer["App Consumer"]
        JBR[JenkinsCommandConsumer]
    end

    subgraph Webhook["Webhook 토픽"]
        WHT{{webhook.inbound<br>JSON}}
    end

    subgraph Jenkins["Jenkins"]
        JE[Executors]
    end

    subgraph Events["이벤트 토픽 (SSE)"]
        EVT{{events.step-changed<br>Avro}}
        COMP{{events.completed<br>Avro}}
    end

    REQ -->|Outbox INSERT| OT
    OT -->|poll| OP
    OP -->|publish| CT

    CT -->|"consume (블로킹)"| PEC
    PEC --> PE --> DAG
    DAG --> WAIT

    DAG -->|"Outbox INSERT<br>(Job 커맨드)"| OT
    OP -->|publish| JT
    JT -->|consume| JBR
    JBR -->|"REST API"| JE

    JE -->|"rpk produce"| WHT
    WHT -->|consume| DAG

    DAG -->|"Outbox INSERT<br>(상태 이벤트)"| OT
    OP -->|publish| EVT
    OP -->|publish| COMP

    WAIT -->|"complete → 다음 메시지 소비"| PEC

    style CT fill:#e3f2fd,stroke:#1976d2,color:#333
    style JT fill:#fff3e0,stroke:#f57c00,color:#333
    style WHT fill:#fff3e0,stroke:#f57c00,color:#333
    style EVT fill:#e8f5e9,stroke:#388e3c,color:#333
    style COMP fill:#e8f5e9,stroke:#388e3c,color:#333
    style OT fill:#e8eaf6,stroke:#3f51b5,color:#333
    style WAIT fill:#fce4ec,stroke:#c62828,color:#333
    style JE fill:#fce4ec,stroke:#c62828,color:#333
```

### 순차 실행 원리

```mermaid
flowchart TB
    subgraph Topic["commands.execution 토픽 (1파티션)"]
        M1["msg1: P1 실행 커맨드"]
        M2["msg2: P2 실행 커맨드"]
        M3["msg3: P3 실행 커맨드"]
        M1 --- M2 --- M3
    end

    subgraph Consumer["Control Consumer (concurrency=1)"]
        C1["① consume(P1)"]
        C2["② pipelineEngine.execute(P1)"]
        C3["③ completionFuture.get()<br>P1 완료까지 블로킹"]
        C4["④ offset commit"]
        C5["⑤ consume(P2)<br>다음 메시지 소비"]
        C1 --> C2 --> C3 -->|"P1 완료 시그널"| C4 --> C5
    end

    Topic --> Consumer

    style M1 fill:#c8e6c9,stroke:#388e3c,color:#333
    style M2 fill:#fff9c4,stroke:#f9a825,color:#333
    style M3 fill:#fff9c4,stroke:#f9a825,color:#333
    style C3 fill:#fce4ec,stroke:#c62828,color:#333
```

**왜 인메모리 큐가 불필요한가**: Kafka 토픽 자체가 메시지 큐이다. 1파티션은 전역 순서를 보장하고, consumer가 블로킹하면 다음 메시지를 소비하지 않는다. 앱 재시작 시에도 committed offset부터 재개하므로 크래시 복구가 자동이다.

## 2. 시나리오: 3 파이프라인 × 3 Job × executor 2

### 토픽 중심 메시지 흐름도

```mermaid
flowchart TB
    subgraph Phase1["Phase 1: P1 실행 (Control Consumer 블로킹)"]
        CT1["commands.execution<br>consume: P1 커맨드"]
        DAG1[DagCoordinator<br>startExecution P1]
        OB1[(Outbox INSERT)]
        JT1{{commands.jenkins}}
        RC1[JenkinsCommandConsumer]
        JK1["Jenkins<br>Exec1: P1-J1<br>Exec2: P1-J2"]
        WAIT1["completionFuture.get()<br>P1 완료 대기 중..."]

        CT1 --> DAG1
        DAG1 -->|"P1-J1, P1-J2<br>커맨드"| OB1
        OB1 -->|publish| JT1
        JT1 -->|consume| RC1
        RC1 -->|"buildWithParameters<br>REST API"| JK1
        DAG1 -.-> WAIT1
    end

    subgraph Phase2["Phase 2: P1 Job 완료 → 다음 Job 디스패치"]
        JK2[Jenkins 빌드 완료]
        WHT1{{webhook.inbound}}
        WEC1[WebhookConsumer]
        DAG2["onJobCompleted<br>→ dispatchReadyJobs<br>→ P1-J3 디스패치"]

        JK2 -->|"rpk produce<br>{P1, step:1, SUCCESS}"| WHT1
        WHT1 -->|consume| WEC1
        WEC1 --> DAG2
        DAG2 -->|"P1-J3 커맨드"| OB1
    end

    subgraph Phase3["Phase 3: P1 완전 종료 → P2 시작"]
        FIN["finalizeExecution(P1)<br>completionFuture.complete()"]
        EVT1{{events.completed}}
        CT2["commands.execution<br>consume: P2 커맨드"]
        DAG3[DagCoordinator<br>startExecution P2]

        FIN -->|"complete 시그널"| WAIT1
        FIN -->|"Outbox INSERT"| EVT1
        WAIT1 -->|"블로킹 해제<br>offset commit"| CT2
        CT2 --> DAG3
    end

    style CT1 fill:#e3f2fd,stroke:#1976d2,color:#333
    style CT2 fill:#e3f2fd,stroke:#1976d2,color:#333
    style JT1 fill:#fff3e0,stroke:#f57c00,color:#333
    style WHT1 fill:#fff3e0,stroke:#f57c00,color:#333
    style EVT1 fill:#e8f5e9,stroke:#388e3c,color:#333
    style WAIT1 fill:#fce4ec,stroke:#c62828,color:#333
    style FIN fill:#c8e6c9,stroke:#388e3c,color:#333
    style JK1 fill:#fce4ec,stroke:#c62828,color:#333
```

### 토픽별 메시지 타임라인

```mermaid
flowchart LR
    subgraph CMD["commands.execution (Control)"]
        direction TB
        CE1["T=0s  {exec:P1} → consume, 블로킹 시작"]
        CE2["T=6m  블로킹 해제 → offset commit"]
        CE3["T=6m  {exec:P2} → consume, 블로킹 시작"]
        CE4["T=12m 블로킹 해제"]
        CE5["T=12m {exec:P3} → consume"]
        CE1 --- CE2 --- CE3 --- CE4 --- CE5
    end

    subgraph JNK["commands.jenkins (Task)"]
        direction TB
        CJ1["T=0s  {P1-J1, step:1}"]
        CJ2["T=0s  {P1-J2, step:2}"]
        CJ3["T=2m  {P1-J3, step:3}"]
        SEP1["──── P1 완료 ────"]
        CJ4["T=6m  {P2-J1, step:1}"]
        CJ5["T=6m  {P2-J2, step:2}"]
        CJ1 --- CJ2 --- CJ3 --- SEP1 --- CJ4 --- CJ5
    end

    subgraph WH["webhook.inbound"]
        direction TB
        W1["T=2m  {P1, step:1, SUCCESS}"]
        W2["T=2.5m {P1, step:2, SUCCESS}"]
        W3["T=4m  {P1, step:3, SUCCESS}"]
        SEP2["──── P1→P2 전환 ────"]
        W4["T=8m  {P2, step:1, SUCCESS}"]
        W1 --- W2 --- W3 --- SEP2 --- W4
    end

    subgraph COMP["events.completed"]
        direction TB
        F1["T=6m  {P1, SUCCESS}"]
        F2["T=12m {P2, SUCCESS}"]
        F3["T=18m {P3, SUCCESS}"]
        F1 --- F2 --- F3
    end

    style CMD fill:#e3f2fd,stroke:#1976d2,color:#333
    style JNK fill:#fff3e0,stroke:#f57c00,color:#333
    style WH fill:#fff3e0,stroke:#f57c00,color:#333
    style COMP fill:#e8f5e9,stroke:#388e3c,color:#333
    style SEP1 fill:#ffcdd2,stroke:#c62828,color:#333
    style SEP2 fill:#ffcdd2,stroke:#c62828,color:#333
```

### 타임라인

```
commands.execution 토픽: [P1, P2, P3]

═══ P1 실행 (Consumer 블로킹) ═══
T=0s    consume(P1) → startExecution → completionFuture.get()
        (P1-J1, P1-J2) 동시 빌드
T=2min  P1-J1 완료 → webhook → P1-J3 디스패치
T=2.5m  P1-J2 완료 (ready job 없음)
T=4min  P1-J3 완료 → P1 종료!
        finalizeExecution → completionFuture.complete()
        → 블로킹 해제 → offset commit

═══ P2 실행 ═══
T=4min  consume(P2) → startExecution → completionFuture.get()
        (P2-J1, P2-J2) 동시 빌드
T=8min  P2 종료 → 블로킹 해제

═══ P3 실행 ═══
T=8min  consume(P3) → ...
T=12min P3 종료

총 소요: 3 파이프라인 × ~4분 = ~12분
```

## 3. Job 상태 전이

```mermaid
stateDiagram-v2
    [*] --> PENDING: JobExecution 생성
    PENDING --> RUNNING: executeJob() 시작
    RUNNING --> WAITING_WEBHOOK: Jenkins 빌드 커맨드 발행<br>스레드 반환
    WAITING_WEBHOOK --> SUCCESS: webhook 도착 (CAS 승리)
    WAITING_WEBHOOK --> FAILED: timeout 체커 (CAS 승리)
    RUNNING --> SUCCESS: 동기 실행 완료
    RUNNING --> FAILED: 실행 실패 (재시도 소진)
    RUNNING --> PENDING: 재시도 스케줄 (exponential backoff)
    PENDING --> SKIPPED: failurePolicy에 의해 건너뜀
    SUCCESS --> [*]
    FAILED --> [*]
    SKIPPED --> [*]

    note right of WAITING_WEBHOOK
        CAS 경쟁: webhook vs timeout
        updateStatusIfCurrent()로
        첫 번째 변경만 성공
    end note
```

## 4. Break-and-Resume + 완료 시그널

Control Consumer가 블로킹하지만, DAG 내부의 Job 실행은 **Break-and-Resume** (비블로킹)이다. 이 두 패턴이 결합되는 방식:

```mermaid
flowchart TB
    subgraph ControlConsumer["Control Consumer 스레드"]
        CC1["consume(P1 커맨드)"]
        CC2["pipelineEngine.execute(P1)"]
        CC3["completionFuture.get()<br>여기서 블로킹!"]
        CC4["블로킹 해제 → offset commit → 다음 소비"]
        CC1 --> CC2 --> CC3 -->|"complete 시그널"| CC4
    end

    subgraph DAGInternal["DAG 내부 (Thread Pool)"]
        D1["dispatchReadyJobs()"]
        D2["Thread Pool submit(J1)"]
        D3["executeJob → Kafka publish → return<br>(Break: 스레드 반환)"]
        D4["webhook 도착"]
        D5["onJobCompleted → dispatchReadyJobs"]
        D6["모든 Job 완료"]
        D7["finalizeExecution()<br>completionFuture.complete()"]

        D1 --> D2 --> D3
        D4 --> D5 -->|"다음 Job"| D2
        D5 -->|"isAllDone"| D6 --> D7
    end

    CC2 -->|"startExecution"| D1
    D7 -->|"complete()"| CC3

    style CC3 fill:#fce4ec,stroke:#c62828,color:#333
    style D7 fill:#c8e6c9,stroke:#388e3c,color:#333
    style D3 fill:#fff3e0,stroke:#f57c00,color:#333
```

**Control Consumer 스레드**는 `completionFuture.get()`에서 블로킹되지만, **DAG 내부 Job은 Thread Pool에서 비블로킹**으로 실행된다. webhook이 도착하면 webhook consumer 스레드에서 `onJobCompleted()`를 호출하고, 마지막 Job이 완료되면 `completionFuture.complete()`로 Control Consumer를 깨운다.

## 5. Jenkins Executor API

### API 엔드포인트

```
GET {JENKINS_URL}/computer/api/json?tree=busyExecutors,totalExecutors
```

| 필드 | 의미 | 산출 기준 |
|------|------|----------|
| `totalExecutors` | Jenkins 전체 executor 수 | Master + Agent 노드 합계 |
| `busyExecutors` | 빌드 실행 중인 executor 수 | BUILDING 상태 카운트 |
| **available** | `total - busy` | 즉시 빌드 가능한 슬롯 |

### 정상 모드 vs 폴백 모드

```mermaid
flowchart TD
    A[dispatchReadyJobs] --> B[jenkinsAdapter.getAvailableExecutors]
    B --> C{jenkinsAvailable >= 0?}

    C -->|Yes| D["정상 모드<br>Jenkins executor 기반 제한"]
    D --> G[available개 Job 디스패치]

    C -->|"No (-1)"| E["폴백 모드<br>per-execution 슬롯 제한"]
    E --> G

    style D fill:#e8f5e9,stroke:#388e3c,color:#333
    style E fill:#fff3e0,stroke:#f57c00,color:#333
```

| 모드 | 조건 | 동작 |
|------|------|------|
| 정상 | `totalExecutors >= 1` | `available = total - busy` 만큼 디스패치 |
| 폴백 | Jenkins 다운, K8s dynamic agent (`total=0`), 파싱 실패 | `available = maxConcurrentJobs - runningCount` |

현재 GCP Jenkins는 K8s Pod agent 방식(`totalExecutors=0`)이므로 **폴백 모드**로 동작한다.

## 6. Kafka 토픽 아키텍처

### 전체 토픽 목록

| 토픽 | 파티션 | 용도 | 포맷 | 보존 |
|------|--------|------|------|------|
| `commands.execution` | **1** | Control: 파이프라인 실행 순서 보장 | Avro | 7일 |
| `commands.jenkins` | 3 | Task: Jenkins 빌드 커맨드 | JSON | 7일 |
| `webhook.inbound` | 2 | Jenkins/GitLab webhook 결과 | JSON | 3일 |
| `events.step-changed` | 3 | Job 상태 변경 (SSE → UI) | Avro | 7일 |
| `events.completed` | 3 | 파이프라인 완료 이벤트 | Avro | 7일 |
| `events.dag-job` | 3 | DAG Job 이벤트 | Avro | 7일 |
| `ticket.events` | 3 | 티켓 도메인 이벤트 | Avro | 7일 |
| `audit.events` | 1 | 감사 로그 | Avro | 30일 |
| `dlq` | 1 | Dead Letter Queue | bytes | 30일 |

> `commands.execution`이 1파티션인 이유: 파이프라인 간 전역 순서를 보장하기 위해. `commands.jenkins`가 JSON인 이유: Redpanda Connect Bloblang이 Avro를 파싱하지 못하기 때문.

### 전체 토픽 흐름도

```mermaid
flowchart TB
    subgraph Producers["Producers"]
        CP[PipelineCommand<br>Producer]
        EP[PipelineEvent<br>Producer]
        DEP[DagEvent<br>Producer]
        AP[AuditEvent<br>Publisher]
    end

    subgraph Outbox["Transactional Outbox"]
        OT[(outbox 테이블)]
        OP[OutboxPoller<br>500ms 주기]
    end

    subgraph Topics["Redpanda Topics"]
        T1[commands.execution<br>1파티션 — Control]
        T2[commands.jenkins<br>3파티션 — Task]
        T3[events.step-changed]
        T4[events.completed]
        T5[events.dag-job]
        T6[webhook.inbound]
        T7[audit.events]
        T8[dlq]
    end

    subgraph AppConsumers["App Consumers"]
        JBR[JenkinsCommandConsumer<br>commands.jenkins → REST API]
    end

    subgraph Consumers["Consumers"]
        PEC[PipelineEvent<br>Consumer<br>concurrency=1]
        WEC[WebhookEvent<br>Consumer]
        SSE[PipelineSSE<br>Consumer]
        AC[AuditConsumer]
        DLC[DlqConsumer]
    end

    subgraph External["외부 시스템"]
        JK[Jenkins]
        GL[GitLab CE]
    end

    CP --> OT
    EP --> OT
    DEP --> OT
    AP --> OT
    OT --> OP

    OP -->|publish| T1
    OP -->|publish| T2
    OP -->|publish| T3
    OP -->|publish| T4
    OP -->|publish| T5
    OP -->|publish| T7

    T1 -->|"consume (블로킹)"| PEC
    T2 -->|consume| JBR
    JBR -->|"REST API"| JK

    JK -->|"rpk produce"| T6
    GL -->|"rpk produce"| T6

    T6 -->|consume| WEC
    T3 -->|consume| SSE
    T4 -->|consume| SSE
    T5 -->|consume| SSE
    T7 -->|consume| AC
    T8 -->|consume| DLC

    style T1 fill:#e3f2fd,stroke:#1976d2,color:#333
    style T2 fill:#fff3e0,stroke:#f57c00,color:#333
    style T3 fill:#e8f5e9,stroke:#388e3c,color:#333
    style T4 fill:#e8f5e9,stroke:#388e3c,color:#333
    style T5 fill:#e8f5e9,stroke:#388e3c,color:#333
    style T6 fill:#fff3e0,stroke:#f57c00,color:#333
    style T7 fill:#e8f5e9,stroke:#388e3c,color:#333
    style T8 fill:#fce4ec,stroke:#c62828,color:#333
    style OT fill:#e8eaf6,stroke:#3f51b5,color:#333
    style PEC fill:#e3f2fd,stroke:#1976d2,color:#333
```

### Outbox 패턴

```mermaid
sequenceDiagram
    participant BIZ as 비즈니스 로직
    participant DB as PostgreSQL
    participant OB as outbox 테이블
    participant OP as OutboxPoller
    participant KF as Redpanda

    Note over BIZ,KF: 원자적 쓰기 (같은 DB 트랜잭션)

    BIZ->>DB: BEGIN TRANSACTION
    BIZ->>DB: INSERT pipeline_execution
    BIZ->>OB: INSERT outbox (status=PENDING)
    BIZ->>DB: COMMIT

    Note over OP,KF: 비동기 발행 (500ms 주기)

    loop 매 500ms
        OP->>OB: SELECT PENDING (batch=50)
        alt PENDING 있음
            OP->>KF: KafkaTemplate.send()
            alt 성공
                OP->>OB: UPDATE status=SENT
            else 실패 (retryCount < 5)
                OP->>OB: retry_count++, next_retry_at
            else 실패 (retryCount >= 5)
                OP->>OB: UPDATE status=DEAD
            end
        end
    end
```

### 외부 시스템 연동 아키텍처

```mermaid
flowchart LR
    subgraph External["외부 시스템"]
        JK[Jenkins]
        GL[GitLab CE]
    end

    subgraph Redpanda["Redpanda"]
        T_CMD[commands.jenkins<br>JSON]
        T_WH[webhook.inbound<br>JSON]
    end

    subgraph App["Spring Boot"]
        JCC[JenkinsCommand<br>Consumer]
        WEC[WebhookEvent<br>Consumer]
    end

    JK -->|"rpk produce"| T_WH
    GL -->|"rpk produce"| T_WH
    T_CMD -->|consume| JCC
    JCC -->|"POST /buildWithParameters"| JK
    T_WH -->|consume| WEC

    style JCC fill:#e1f5fe,stroke:#0288d1,color:#333
    style WEC fill:#e1f5fe,stroke:#0288d1,color:#333
    style T_CMD fill:#fff3e0,stroke:#f57c00,color:#333
    style T_WH fill:#fff3e0,stroke:#f57c00,color:#333
    style JK fill:#fce4ec,stroke:#c62828,color:#333
    style GL fill:#fce4ec,stroke:#c62828,color:#333
```

### Consumer Group 및 멱등성

| Consumer | Group ID | 멱등성 방식 | 재시도 |
|----------|----------|------------|--------|
| PipelineEventConsumer | `pipeline-engine` | `ce_id` → ProcessedEvent | @RetryableTopic: 1s,2s,4s,8s → DLT |
| WebhookEventConsumer | `webhook-processor` | `webhook:{executionId}:{stepOrder}` | 4회 backoff → DLT |
| PipelineSseConsumer | `pipeline-sse` | 무상태 | - |
| AuditConsumer | `audit-consumer` | `ce_id` | 기본 재시도 |
| DlqConsumer | `dlq-handler` | 로깅만 | 없음 |

### 직렬화 전략

| 포맷 | 토픽 | 이유 |
|------|------|------|
| **Avro** | commands.execution, events.*, audit, ticket | Java Consumer 간 타입 안전성 + 스키마 진화 |
| **JSON** | commands.jenkins, webhook.inbound | Redpanda Connect Bloblang 호환성 |
| **bytes** | dlq | 이기종 페이로드 격리 |

## 7. 크래시 복구, 재시도, DLQ

### 복구 시나리오 상세

```mermaid
flowchart TB
    subgraph S1["시나리오 1: 파이프라인 실행 중 앱 크래시"]
        direction TB
        A1["P1 실행 중<br>completionFuture.get() 블로킹"]
        A2["앱 크래시!"]
        A3["offset 미커밋<br>(P1 메시지 ack 안 됨)"]
        A4["앱 재시작"]
        A5["@PostConstruct<br>recoverRunningExecutions()"]
        A6["DB에서 RUNNING 실행 조회<br>WAITING_WEBHOOK Job → FAILED"]
        A7["Control Consumer 재시작<br>P1 메시지 재소비"]
        A8["P1 재실행 (이미 SUCCESS인 Job은 skip)"]
        A1 --> A2 --> A3 --> A4 --> A5 --> A6 --> A7 --> A8
    end

    style A2 fill:#fce4ec,stroke:#c62828,color:#333
    style A5 fill:#e3f2fd,stroke:#1976d2,color:#333
    style A8 fill:#c8e6c9,stroke:#388e3c,color:#333
```

```mermaid
flowchart TB
    subgraph S2["시나리오 2: Outbox 발행 전 크래시"]
        direction TB
        B1["비즈니스 로직 + Outbox INSERT<br>같은 트랜잭션에서 COMMIT"]
        B2["앱 크래시!<br>(Kafka 발행 전)"]
        B3["앱 재시작"]
        B4["OutboxPoller 재가동<br>500ms 주기"]
        B5["PENDING 레코드 발견<br>→ Kafka 발행"]
        B6["정상 처리"]
        B1 --> B2 --> B3 --> B4 --> B5 --> B6
    end

    style B2 fill:#fce4ec,stroke:#c62828,color:#333
    style B5 fill:#c8e6c9,stroke:#388e3c,color:#333
```

```mermaid
flowchart TB
    subgraph S3["시나리오 3: Jenkins 빌드 중 크래시"]
        direction TB
        C1["Jenkins에서 빌드 실행 중<br>Job 상태: WAITING_WEBHOOK"]
        C2["앱 크래시!"]
        C3["Jenkins 빌드 완료<br>→ webhook 발송 시도<br>→ 앱 다운으로 수신 실패"]
        C4["앱 재시작"]
        C5["@PostConstruct 복구<br>WAITING_WEBHOOK → FAILED"]
        C6["WebhookTimeoutChecker<br>30초 주기 폴링"]
        C7["5분 초과 WAITING_WEBHOOK<br>→ CAS로 FAILED 전환"]
        C8["completionFuture.complete()<br>→ 다음 파이프라인 진행"]
        C1 --> C2 --> C3 --> C4 --> C5 --> C8
        C4 --> C6 --> C7 --> C8
    end

    style C2 fill:#fce4ec,stroke:#c62828,color:#333
    style C5 fill:#fff3e0,stroke:#f57c00,color:#333
    style C7 fill:#fff3e0,stroke:#f57c00,color:#333
    style C8 fill:#c8e6c9,stroke:#388e3c,color:#333
```

### 재시도 토픽 (@RetryableTopic)

Spring Kafka의 `@RetryableTopic`이 자동으로 **재시도 토픽**과 **DLT(Dead Letter Topic)**를 생성한다. 원본 토픽 이름에 suffix를 붙여 관리한다.

```mermaid
flowchart LR
    subgraph Original["원본 토픽"]
        T1[commands.execution]
        T2[webhook.inbound]
    end

    subgraph Retry["재시도 토픽 (자동 생성)"]
        R1[commands.execution-retry-0]
        R2[commands.execution-retry-1]
        R3[commands.execution-retry-2]
        R4[webhook.inbound-retry-0]
        R5[webhook.inbound-retry-1]
        R6[webhook.inbound-retry-2]
    end

    subgraph DLT["DLT (Dead Letter Topic)"]
        D1[commands.execution-dlt]
        D2[webhook.inbound-dlt]
    end

    T1 -->|"실패"| R1
    R1 -->|"1s 후 재시도"| R2
    R2 -->|"2s 후 재시도"| R3
    R3 -->|"4s 후 재시도 실패"| D1

    T2 -->|"실패"| R4
    R4 -->|"1s 후"| R5
    R5 -->|"2s 후"| R6
    R6 -->|"4s 후 실패"| D2

    style T1 fill:#e3f2fd,stroke:#1976d2,color:#333
    style T2 fill:#fff3e0,stroke:#f57c00,color:#333
    style R1 fill:#fff9c4,stroke:#f9a825,color:#333
    style R2 fill:#fff9c4,stroke:#f9a825,color:#333
    style R3 fill:#fff9c4,stroke:#f9a825,color:#333
    style R4 fill:#fff9c4,stroke:#f9a825,color:#333
    style R5 fill:#fff9c4,stroke:#f9a825,color:#333
    style R6 fill:#fff9c4,stroke:#f9a825,color:#333
    style D1 fill:#fce4ec,stroke:#c62828,color:#333
    style D2 fill:#fce4ec,stroke:#c62828,color:#333
```

| Consumer | 재시도 정책 | 재시도 토픽 | DLT |
|----------|-----------|-----------|-----|
| PipelineEventConsumer | 4회, backoff 1s→2s→4s→8s | `commands.execution-retry-{0,1,2}` | `commands.execution-dlt` |
| WebhookEventConsumer | 4회, backoff 1s→2s→4s→8s | `webhook.inbound-retry-{0,1,2}` | `webhook.inbound-dlt` |
| TicketStatusEventConsumer | 4회, backoff 1s→2s→4s→8s | `ticket.events-retry-{0,1,2}` | `ticket.events-dlt` |

### 재시도 흐름 상세

```mermaid
sequenceDiagram
    participant OT as 원본 토픽
    participant C as Consumer
    participant R0 as retry-0
    participant R1 as retry-1
    participant R2 as retry-2
    participant DLT as DLT

    OT->>C: 메시지 소비
    C->>C: 처리 실패 (예외)
    Note over C: 1회차 실패

    C->>R0: 메시지 이동
    Note over R0: 1초 대기
    R0->>C: 재소비
    C->>C: 처리 실패
    Note over C: 2회차 실패

    C->>R1: 메시지 이동
    Note over R1: 2초 대기
    R1->>C: 재소비
    C->>C: 처리 실패
    Note over C: 3회차 실패

    C->>R2: 메시지 이동
    Note over R2: 4초 대기
    R2->>C: 재소비
    C->>C: 처리 실패
    Note over C: 4회차 실패 (재시도 소진)

    C->>DLT: 메시지 이동 + 에러 헤더 첨부
    Note over DLT: @DltHandler가 로깅<br>운영자 수동 처리 대기
```

### DLQ에 간 메시지 수동 재처리

DLT에 도달한 메시지는 자동 복구되지 않는다. 운영자가 원인을 분석하고 수동으로 재처리해야 한다.

**재처리 절차:**

```mermaid
flowchart TB
    DLT["DLT 메시지 발견<br>(로그 알림 또는 모니터링)"]
    DIAG["원인 분석"]
    DIAG_DB{"DB 문제?"}
    DIAG_JENKINS{"Jenkins 문제?"}
    DIAG_BUG{"코드 버그?"}

    FIX_DB["DB 복구<br>(연결, 마이그레이션 등)"]
    FIX_JENKINS["Jenkins 복구<br>(재시작, 설정 수정)"]
    FIX_BUG["코드 수정 + 배포"]

    REPLAY["원본 토픽에 메시지 재발행"]
    VERIFY["정상 처리 확인"]

    DLT --> DIAG
    DIAG --> DIAG_DB
    DIAG --> DIAG_JENKINS
    DIAG --> DIAG_BUG
    DIAG_DB -->|Yes| FIX_DB --> REPLAY
    DIAG_JENKINS -->|Yes| FIX_JENKINS --> REPLAY
    DIAG_BUG -->|Yes| FIX_BUG --> REPLAY
    REPLAY --> VERIFY

    style DLT fill:#fce4ec,stroke:#c62828,color:#333
    style REPLAY fill:#e3f2fd,stroke:#1976d2,color:#333
    style VERIFY fill:#c8e6c9,stroke:#388e3c,color:#333
```

**재발행 방법:**

```bash
# 1. DLT 메시지 확인 (rpk CLI)
rpk topic consume commands.execution-dlt --num 1 --format json

# 2. 에러 헤더 확인
# kafka_dlt-exception-fqcn: 예외 클래스
# kafka_dlt-exception-message: 에러 메시지
# kafka_dlt-original-topic: 원본 토픽

# 3. 원인 해결 후 원본 토픽에 재발행
rpk topic produce commands.execution --key {executionId} < message.json

# 4. 또는 API로 파이프라인 재실행
curl -X POST http://localhost:8070/api/pipelines/{id}/execute
```

**DLT 메시지에 자동 첨부되는 헤더:**

| 헤더 | 내용 |
|------|------|
| `kafka_dlt-exception-fqcn` | 예외 클래스명 (예: `java.lang.IllegalStateException`) |
| `kafka_dlt-exception-message` | 에러 메시지 |
| `kafka_dlt-original-topic` | 원본 토픽 이름 |
| `kafka_dlt-original-partition` | 원본 파티션 번호 |
| `kafka_dlt-original-offset` | 원본 오프셋 |
| `kafka_dlt-original-timestamp` | 원본 메시지 타임스탬프 |

### Outbox 레벨 재시도 vs Kafka 레벨 재시도

시스템에는 **두 레벨의 재시도**가 존재한다.

```mermaid
flowchart LR
    subgraph Level1["Level 1: Outbox 재시도<br>(Kafka 발행 실패)"]
        direction TB
        L1A["OutboxPoller<br>Kafka send() 실패"]
        L1B["retry_count++<br>next_retry_at = NOW() + 2^n초"]
        L1C{"retry >= 5?"}
        L1D["다음 폴링에서 재시도"]
        L1E["status = DEAD<br>운영자 확인"]
        L1A --> L1B --> L1C
        L1C -->|No| L1D
        L1C -->|Yes| L1E
    end

    subgraph Level2["Level 2: @RetryableTopic 재시도<br>(Consumer 처리 실패)"]
        direction TB
        L2A["Consumer<br>비즈니스 로직 예외"]
        L2B["retry 토픽 이동<br>backoff 대기"]
        L2C{"retry >= 4?"}
        L2D["재소비 시도"]
        L2E["DLT 이동<br>@DltHandler 로깅"]
        L2A --> L2B --> L2C
        L2C -->|No| L2D
        L2C -->|Yes| L2E
    end

    style L1E fill:#fce4ec,stroke:#c62828,color:#333
    style L2E fill:#fce4ec,stroke:#c62828,color:#333
    style L1D fill:#c8e6c9,stroke:#388e3c,color:#333
    style L2D fill:#c8e6c9,stroke:#388e3c,color:#333
```

| 레벨 | 대상 | 재시도 횟수 | 백오프 | 실패 시 |
|------|------|-----------|--------|---------|
| **Outbox** | Kafka 브로커 연결 실패, 발행 타임아웃 | 5회 | 2^n초 (1,2,4,8,16) | status=DEAD (outbox 테이블) |
| **@RetryableTopic** | Consumer 비즈니스 로직 예외 (DB 오류, 파싱 실패 등) | 4회 | 1s→2s→4s→8s | DLT 토픽 이동 |

Outbox DEAD와 DLT 모두 자동 복구되지 않으며, 운영자가 원인 해결 후 수동 재처리한다. Outbox DEAD는 DB에서 확인(`SELECT * FROM outbox WHERE status='DEAD'`), DLT는 Kafka에서 확인(`rpk topic consume {topic}-dlt`).
