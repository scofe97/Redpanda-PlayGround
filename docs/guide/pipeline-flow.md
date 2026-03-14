# Pipeline 흐름도

파이프라인 실행의 전체 흐름을 Mermaid graph로 표현한다. 각 흐름은 독립적인 다이어그램으로 분리하여 한 눈에 파악할 수 있도록 했다.

---

## Pipeline Start 흐름

파이프라인 시작 요청부터 Jenkins 빌드 완료, SSE 실시간 알림까지의 전체 흐름이다. Break-and-Resume 패턴으로 Jenkins 웹훅 대기 중 스레드를 해제하고, 웹훅 수신 시 이어서 실행한다.

### 개요

전체 흐름을 고수준으로 요약한 다이어그램이다. 각 번호는 아래 Phase 상세 다이어그램과 대응한다.

```mermaid
graph LR
    A[Client<br>POST /start] -->|"Phase 1"| B[API + DB<br>Ticket, Execution, Outbox]
    B --> C[OutboxPoller<br>500ms 폴링]
    C -->|"Phase 2"| D[/CMD_EXECUTION/]
    D --> E[Pipeline Engine<br>Step 실행]
    E -->|"Jenkins 필요 시"| F[Jenkins<br>Break-and-Resume]
    F -->|"웹훅 복귀"| E
    E -->|"Phase 3"| G[/EVT 토픽/]
    G --> H[SSE + Ticket 갱신]

    style A fill:#e8f5e9,stroke:#4caf50,color:#333
    style D fill:#fff3e0,stroke:#ff9800,color:#333
    style G fill:#fff3e0,stroke:#ff9800,color:#333
    style F fill:#fce4ec,stroke:#e91e63,color:#333
    style B fill:#e3f2fd,stroke:#2196f3,color:#333
```

### Phase 1: 요청 접수 ~ Outbox 발행 (①~②)

클라이언트 요청을 받아 DB에 Ticket/Execution/Outbox를 기록하고, OutboxPoller가 Kafka 토픽으로 발행하는 구간이다.

```mermaid
graph TB
    subgraph client["Client"]
        A[POST /pipeline/start]
    end

    subgraph api["API Layer"]
        B[PipelineController]
        C[PipelineService]
    end

    subgraph db["Database"]
        D[(Ticket DB<br>status → DEPLOYING)]
        E[(Execution + Steps<br>INSERT)]
        F[(Outbox<br>INSERT PENDING)]
    end

    subgraph outbox["Outbox Poller"]
        G[OutboxPoller<br>500ms 주기 폴링]
    end

    H[/CMD_EXECUTION<br>Avro/]

    A -->|"① 202 Accepted"| B --> C
    C --> D
    C --> E
    C --> F
    G -->|"② 폴링 + 발행"| F
    G --> H

    style A fill:#e8f5e9,stroke:#4caf50,color:#333
    style D fill:#e3f2fd,stroke:#2196f3,color:#333
    style E fill:#e3f2fd,stroke:#2196f3,color:#333
    style F fill:#e3f2fd,stroke:#2196f3,color:#333
    style H fill:#fff3e0,stroke:#ff9800,color:#333
```

### Phase 2: 엔진 실행 ~ Jenkins Break-and-Resume (③~⑦)

CMD_EXECUTION을 소비하여 스텝을 순차 실행한다. Jenkins 빌드가 필요한 스텝은 스레드를 해제(Break)하고 웹훅 수신 시 재개(Resume)한다. 실패 시 SAGA 보상이 역순으로 실행된다.

```mermaid
graph TB
    subgraph kafka_in["Redpanda (수신)"]
        H[/CMD_EXECUTION<br>Avro/]
    end

    subgraph engine["Pipeline Engine"]
        M[PipelineEventConsumer<br>ce_id 멱등성 체크]
        N[PipelineEngine<br>executeFrom]
        P[Step Executor<br>GIT_CLONE / BUILD / DEPLOY]
        O{waitingForWebhook?}
        Q[PipelineCommandProducer<br>Jenkins 빌드 명령]
    end

    subgraph kafka_mid["Redpanda (Jenkins)"]
        I[/CMD_JENKINS<br>JSON/]
        J[/WEBHOOK_INBOUND<br>JSON/]
    end

    subgraph connect["Redpanda Connect"]
        R[jenkins-command<br>→ Jenkins REST API]
        S[jenkins-webhook<br>HTTP :4197 수신]
    end

    subgraph jenkins["Jenkins"]
        T[Jenkins Job<br>buildWithParameters]
        U[Jenkins Webhook<br>빌드 완료 콜백]
    end

    subgraph webhook["Webhook Processing"]
        V[WebhookEventConsumer]
        W[JenkinsWebhookHandler<br>멱등성 + 콘솔 로그 조회]
        X[PipelineEngine<br>resumeAfterWebhook<br>CAS: WAITING → SUCCESS/FAILED]
    end

    subgraph saga["SAGA 보상"]
        CC[SagaCompensator<br>역순 보상 실행]
    end

    EVT[Phase 3로 →]

    H -->|"③ 비동기 실행"| M --> N
    N --> P
    P --> O

    O -->|"Yes: 스레드 해제"| Q
    Q --> I
    I -->|"④ Connect 전달"| R --> T

    T -->|"⑤ 빌드 완료"| U --> S
    S --> J
    J -->|"⑥ 웹훅 처리"| V --> W --> X
    X -->|"⑦ 다음 스텝 계속"| N

    O -->|"No: 즉시 완료"| EVT
    P -->|"실패 시"| CC
    CC -->|"역순 보상 후"| EVT

    style H fill:#fff3e0,stroke:#ff9800,color:#333
    style I fill:#fff3e0,stroke:#ff9800,color:#333
    style J fill:#fff3e0,stroke:#ff9800,color:#333
    style T fill:#fce4ec,stroke:#e91e63,color:#333
    style CC fill:#ffebee,stroke:#f44336,color:#333
    style EVT fill:#f5f5f5,stroke:#9e9e9e,color:#333
```

### Phase 3: 이벤트 발행 ~ SSE/티켓 갱신 (⑧~⑨)

스텝 완료 또는 전체 완료 이벤트를 발행하여 SSE로 클라이언트에 실시간 전달하고, 티켓 상태를 최종 갱신한다.

```mermaid
graph TB
    subgraph event["Event Publishing"]
        Y[PipelineEventProducer]
    end

    subgraph kafka_out["Redpanda (이벤트)"]
        K[/EVT_STEP_CHANGED<br>Avro/]
        L[/EVT_COMPLETED<br>Avro/]
    end

    subgraph sse["SSE Layer"]
        Z[PipelineSseConsumer]
        AA[SseRegistry<br>send to client]
    end

    subgraph client["Client"]
        SSE[SSE 구독<br>/pipeline/events]
    end

    subgraph ticket["Ticket Update"]
        BB[TicketStatusEventConsumer<br>SUCCESS → DEPLOYED<br>FAILED → FAILED]
    end

    Y --> K
    Y --> L
    K -->|"⑧ SSE 전송"| Z --> AA --> SSE
    L --> Z
    L -->|"⑨ 티켓 상태 갱신"| BB

    style SSE fill:#e8f5e9,stroke:#4caf50,color:#333
    style K fill:#fff3e0,stroke:#ff9800,color:#333
    style L fill:#fff3e0,stroke:#ff9800,color:#333
```

---

## 최근 실행이력 조회

특정 티켓의 가장 최근 파이프라인 실행 결과를 스텝 목록과 함께 반환한다.

```mermaid
graph LR
    A[Client<br>GET /api/tickets/ID/pipeline] --> B[PipelineController<br>getLatest]
    B --> C[PipelineService<br>getLatestExecution]
    C --> D[(PipelineExecutionMapper<br>findLatestByTicketId)]
    D --> E[(PipelineStepMapper<br>findByExecutionId)]
    E --> F[PipelineExecutionResponse<br>from execution + steps]
    F --> G[200 OK<br>JSON Response]

    style A fill:#e8f5e9,stroke:#4caf50,color:#333
    style D fill:#e3f2fd,stroke:#2196f3,color:#333
    style E fill:#e3f2fd,stroke:#2196f3,color:#333
    style G fill:#e8f5e9,stroke:#4caf50,color:#333
```

---

## 모든 실행이력 조회

특정 티켓의 전체 파이프라인 실행 이력을 조회한다. 각 실행마다 소속 스텝 목록을 포함한다.

```mermaid
graph LR
    A[Client<br>GET /api/tickets/ID/pipeline/history] --> B[PipelineController<br>getHistory]
    B --> C[PipelineService<br>getHistory]
    C --> D[(PipelineExecutionMapper<br>findByTicketId)]
    D --> E{각 execution 순회}
    E --> F[(PipelineStepMapper<br>findByExecutionId)]
    F --> E
    E --> G["List#lt;PipelineExecutionResponse#gt;"]
    G --> H[200 OK<br>JSON Array]

    style A fill:#e8f5e9,stroke:#4caf50,color:#333
    style D fill:#e3f2fd,stroke:#2196f3,color:#333
    style F fill:#e3f2fd,stroke:#2196f3,color:#333
    style H fill:#e8f5e9,stroke:#4caf50,color:#333
```
