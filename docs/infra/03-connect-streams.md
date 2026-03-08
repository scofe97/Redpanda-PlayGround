# Redpanda Connect Streams 모드

Redpanda Connect(구 Benthos)의 Streams 모드를 사용하여 여러 파이프라인을 하나의 프로세스에서 관리하는 구조를 설명한다.

---

## 단일 모드 vs Streams 모드

### 단일 모드
```bash
rpk connect run config.yaml
```
하나의 YAML = 하나의 input → pipeline → output. 파이프라인 추가 시 별도 프로세스가 필요하다.

### Streams 모드
```bash
rpk connect streams /connect/*.yaml
```
> **v4.43.0+**: `streams`는 `run`의 서브커맨드가 아닌 **독립 서브커맨드**다. `run --streams`는 더 이상 유효하지 않다.
디렉토리 내 모든 YAML이 독립 스트림으로 로드된다. 파일명(확장자 제외)이 스트림 ID가 된다.
하나의 프로세스에서 여러 파이프라인을 운영하므로 리소스 효율이 높다.

**주의**: Streams 모드에서 `http_server` input의 `address` 필드는 무시되고 공유 포트(기본 4195)에 등록된다. 이 프로젝트에서는 webhook 수신을 별도 포트(4197)로 분리하여 Connect API(4195)와 충돌을 방지했다.

---

## Streams REST API

Streams 모드에서 제공하는 관리 API로, 런타임에 스트림을 동적으로 추가/수정/삭제할 수 있다.

| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/streams` | 활성 스트림 목록 |
| GET | `/streams/{id}` | 스트림 설정 조회 |
| POST | `/streams/{id}` | 새 스트림 등록 (YAML body) |
| PUT | `/streams/{id}` | 스트림 설정 변경 (무중단) |
| DELETE | `/streams/{id}` | 스트림 삭제 |

파일 기반 스트림(기동 시 YAML로 로드)은 REST API로 삭제할 수 없다. 파일을 직접 제거하고 재기동해야 한다.
REST API로 등록한 스트림은 컨테이너 재시작 시 소멸한다. 영속화가 필요하면 DB에 설정을 저장하고 기동 시 복원하는 로직이 필요하다.

---

## 현재 프로젝트 구조

```
docker/connect/
├── jenkins-webhook.yaml    # Event:  Jenkins → HTTP → Kafka (webhook 수신)
├── gitlab-webhook.yaml     # Event:  GitLab → HTTP → Kafka (webhook 수신)
└── jenkins-command.yaml    # Command: Kafka → HTTP → Jenkins REST (빌드 트리거)
```

docker-compose에서 `streams /etc/connect/*.yaml`로 기동하면 3개 스트림이 자동 로드된다.

### YAML 파이프라인 예시 — jenkins-webhook.yaml

```yaml
input:
  http_server:
    address: 0.0.0.0:4197
    path: /webhook/jenkins
    allowed_verbs:
      - POST

pipeline:
  processors:
    - mapping: |
        root.webhookSource = "JENKINS"
        root.payload = content().string()
        root.headers = @
        root.receivedAt = now()

output:
  kafka_franz:
    seed_brokers:
      - redpanda:9092
    topic: playground.webhook.inbound
    key: ${! json("webhookSource") }
```

Jenkins 빌드 완료 → HTTP POST → Connect가 메타데이터(source, timestamp)를 추가 → Kafka 토픽으로 발행하는 흐름이다.

### CQRS 패턴 및 동적 커넥터 관리

Connect 파이프라인의 CQRS 매핑(Command/Query/Event 분리)은 [patterns/06-redpanda-connect.md](../patterns/06-redpanda-connect.md)에서 상세히 다룬다.

웹 UI를 통한 커넥터 동적 등록/관리 시나리오는 [patterns/10-dynamic-connector-management.md](../patterns/10-dynamic-connector-management.md)를 참조한다.

---

## 제약 사항

1. **파일 vs API 스트림 혼용**: 파일 스트림은 API 삭제 불가, API 스트림은 재시작 시 소멸. 기본 파이프라인은 파일, 동적 파이프라인은 API로 분리하는 것이 실용적이다.
2. **포트 공유**: Streams 모드에서 모든 `http_server` input은 4195 포트를 공유한다. path가 겹치지 않도록 주의해야 한다.
3. **리소스 격리 없음**: 하나의 프로세스이므로 특정 스트림의 장애가 전체에 영향을 줄 수 있다. 크리티컬한 파이프라인은 별도 프로세스로 분리를 고려한다.
4. **설정 검증**: REST API로 잘못된 YAML을 등록하면 해당 스트림만 실패한다. 등록 전 dry-run 검증(`POST /streams/{id}?dry_run=true`)을 활용한다.
