# Redpanda 운영 가이드 — 개발자가 알아야 할 것들

이 문서는 Kafka 경험이 있는 개발자가 Redpanda를 도입할 때 알아야 할 차이점, 운영 주의사항, rpk CLI 활용법을 정리한다.

---

## 1. Redpanda vs Kafka — 왜 Redpanda인가

### 핵심 차이

| 항목 | Kafka | Redpanda |
|------|-------|----------|
| 런타임 | JVM (Java/Scala) | C++ 단일 바이너리 |
| 합의 알고리즘 | ZooKeeper → KRaft (전환 중) | Raft (내장, 외부 의존 없음) |
| Schema Registry | Confluent Schema Registry (별도 서비스) | 내장 (추가 컨테이너 불필요) |
| 리소스 | JVM 힙 튜닝 필요 (Broker + ZK) | `--memory`, `--smp` 플래그로 직접 제어 |
| API 호환 | - | Kafka API 100% 호환 (클라이언트 코드 변경 없음) |
| 로컬 개발 | 3개+ 컨테이너 (Broker + ZK + SR) | 1개 컨테이너 (all-in-one) |

Redpanda를 선택한 이유는 단순하다. **로컬 개발 환경이 가볍고, Kafka 클라이언트 코드를 그대로 쓸 수 있기 때문이다.** Spring Kafka의 `KafkaTemplate`, `@KafkaListener`, `AdminClient` 등 모든 API가 그대로 동작한다.

### 주의: "호환"이 "동일"은 아니다

Kafka API 호환이라도 내부 구현이 다르므로 주의할 점이 있다:

- **트랜잭션**: Redpanda의 트랜잭션 지원은 Kafka와 동일하지만, `transaction.timeout.ms` 기본값이 다를 수 있다. 반드시 명시적으로 설정할 것.
- **압축(compaction)**: 로그 컴팩션 동작은 호환되지만, 내부 타이밍이 다르다. `min.cleanable.dirty.ratio` 등의 설정이 동일 결과를 보장하지 않을 수 있다.
- **Consumer Group Rebalancing**: Redpanda는 자체 구현이므로, Cooperative Sticky Assignor 사용 시 미세한 동작 차이가 있을 수 있다. 이 프로젝트에서는 기본 assignor를 사용한다.

---

## 2. 운영 주의사항

### 2-1. 메모리 설정 (`--memory`)

```yaml
# docker-compose.yml
command:
  - redpanda start
  - --memory 2G        # Redpanda가 사용할 최대 메모리
  - --overprovisioned  # 단일 코어 환경 최적화
```

**`--memory`는 JVM의 `-Xmx`와 다르다.** Redpanda는 C++로 작성되어 OS 메모리를 직접 관리한다. 이 값은 Redpanda가 사용할 총 메모리 상한이며, 이를 초과하면 OOM이 아니라 요청을 거부한다.

| 환경 | 권장 값 | 이유 |
|------|---------|------|
| 로컬 개발 | 1-2G | 다른 컨테이너와 공존해야 하므로 |
| 스테이징 | 4-8G | 부하 테스트에 충분한 버퍼 |
| 프로덕션 | 시스템 메모리의 80% | OS와 페이지 캐시에 20% 남겨둘 것 |

### 2-2. `--overprovisioned` 플래그

이 플래그는 **Redpanda가 전용 하드웨어가 아닌 공유 환경에서 실행될 때** 사용한다. Docker, VM, CI 환경 등에서 CPU를 독점할 수 없을 때 활성화해야 한다.

- **없을 때**: Redpanda가 CPU를 100% 점유하려 시도 → 다른 컨테이너 성능 저하
- **있을 때**: CPU를 양보하며 실행 → 약간의 지연시간 증가, 하지만 공존 가능

**프로덕션 전용 서버에서는 이 플래그를 빼야 최대 성능을 낸다.**

### 2-3. 디스크 I/O

Redpanda는 직접 I/O(Direct I/O)를 사용한다. 이는 OS 페이지 캐시를 우회하고 디스크에 직접 쓴다는 의미다.

- **SSD 필수**: HDD에서는 성능이 심각하게 저하된다
- **Docker 볼륨**: named volume 사용 권장. bind mount는 macOS에서 FUSE 오버헤드로 느려질 수 있다
- **Google Drive 동기화**: 이 프로젝트처럼 Google Drive에 저장하면, Redpanda 데이터 볼륨이 동기화 대상이 되지 않도록 named volume(`redpanda-data`)을 사용한다

### 2-4. Schema Registry 내장의 함정

Redpanda Schema Registry는 Confluent Schema Registry API와 호환되지만, 몇 가지 차이가 있다:

| 항목 | Confluent SR | Redpanda 내장 SR |
|------|-------------|-----------------|
| 저장소 | `_schemas` 토픽 | Raft 기반 내부 저장 |
| 호환성 모드 | BACKWARD, FORWARD, FULL, NONE 등 | 동일 |
| Avro/JSON/Protobuf | 모두 지원 | 모두 지원 |
| 참조(Reference) | 지원 | 제한적 (nested schema 참조 시 확인 필요) |
| 인증 | 별도 설정 | Redpanda ACL과 통합 |

**이 프로젝트에서의 선택**: `ByteArraySerializer/Deserializer`를 사용하고 Avro 직렬화를 코드에서 직접 수행한다. Schema Registry를 자동 serde로 사용하지 않는 이유는, Outbox 테이블에 BYTEA로 저장한 후 폴러가 그대로 Kafka에 보내는 구조이기 때문이다.

### 2-5. 토픽 자동 생성

```yaml
# application.yml
spring.kafka.admin.auto-create: true  # (기본값)
```

**개발 환경**에서는 편하지만, **프로덕션에서는 반드시 끄고 `KafkaTopicConfig`에서 명시적으로 생성해야 한다.** 자동 생성된 토픽은 기본 파티션(1)과 기본 보관 기간을 사용하므로 의도한 설정과 다를 수 있다.

이 프로젝트는 `KafkaTopicConfig.java`에서 6개 토픽을 명시적으로 정의한다 — 파티션 수, 보관 기간까지 코드로 관리하는 것이 올바른 방식이다.

---

## 3. rpk CLI 활용법

`rpk`는 Redpanda의 공식 CLI 도구로, Kafka의 `kafka-topics.sh`, `kafka-console-consumer.sh` 등을 하나로 통합한 것이다.

### 기본 명령어

```bash
# 클러스터 상태 확인
rpk cluster health
rpk cluster info

# 토픽 관리
rpk topic list
rpk topic describe playground.pipeline.commands
rpk topic create test-topic -p 3 -r 1
rpk topic delete test-topic

# 토픽 설정 확인/변경
rpk topic alter-config playground.audit.events --set retention.ms=2592000000
```

### 메시지 소비/생산 (디버깅용)

```bash
# 토픽 메시지 실시간 소비 (최신부터)
rpk topic consume playground.pipeline.events

# 처음부터 소비
rpk topic consume playground.pipeline.events --offset start

# 특정 파티션만
rpk topic consume playground.pipeline.events --partitions 0

# 메시지 생산 (테스트용)
echo '{"test":"hello"}' | rpk topic produce playground.webhook.inbound
```

### 컨슈머 그룹 모니터링

```bash
# 그룹 목록
rpk group list

# 그룹 상세 (래그 확인)
rpk group describe playground
# → PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
# → 0          42              42              0    ← 정상 (래그 0)
# → 1          38              45              7    ← 래그 발생

# 오프셋 리셋 (주의: 컨슈머 중지 후 실행)
rpk group seek playground --to start  # 처음부터 재처리
rpk group seek playground --to end    # 최신으로 건너뛰기
```

### Docker 환경에서 rpk 실행

이 프로젝트에서 rpk를 실행하려면 Redpanda 컨테이너 안에서 실행하거나, `docker exec`를 사용한다:

```bash
# 컨테이너 안에서 실행
docker exec -it playground-redpanda rpk topic list

# 토픽 상세
docker exec -it playground-redpanda rpk topic describe playground.pipeline.commands

# 컨슈머 그룹 래그 확인
docker exec -it playground-redpanda rpk group describe playground

# 클러스터 헬스
docker exec -it playground-redpanda rpk cluster health
```

편의를 위해 Makefile에 alias를 추가하는 것도 좋다:

```makefile
rpk: ## rpk CLI (예: make rpk CMD="topic list")
	docker exec playground-redpanda rpk $(CMD)
```

---

## 4. Redpanda Console 활용

Redpanda Console(`http://localhost:28080`)은 토픽/메시지/컨슈머 그룹을 웹 UI로 모니터링하는 도구다.

### 유용한 기능

| 기능 | 위치 | 활용 |
|------|------|------|
| **토픽 메시지 조회** | Topics → 토픽 선택 → Messages | Avro 메시지를 자동 디시리얼라이즈하여 JSON으로 표시. 디버깅 시 `rpk consume`보다 편리하다. |
| **컨슈머 그룹 래그** | Consumer Groups → 그룹 선택 | 파티션별 래그를 시각적으로 확인. 래그가 증가하면 컨슈머 처리 속도에 문제가 있다는 신호다. |
| **토픽 설정** | Topics → 토픽 선택 → Configuration | 파티션 수, 보관 기간, 압축 설정 등을 UI에서 확인/변경. |
| **스키마 레지스트리** | Schema Registry | 등록된 Avro 스키마와 버전을 확인. 호환성 검증 결과도 표시. |

### 데모 시 활용 팁

파이프라인 실행 데모 중 Console을 열어두면, 이벤트가 토픽에 실시간으로 들어오는 것을 볼 수 있다:

1. Console에서 `playground.pipeline.events` 토픽의 Messages 탭 열기
2. 프론트엔드에서 파이프라인 시작
3. 스텝이 진행될 때마다 새 메시지가 추가되는 것을 확인
4. 메시지 클릭 → Avro 필드(executionId, stepType, status 등) 확인

이 흐름은 "Outbox → Kafka → Consumer → SSE" 전체 파이프를 시각적으로 증명한다.

---

## 5. 프로덕션 고려사항

이 프로젝트는 학습용이므로 단일 노드지만, 프로덕션에서는 다음을 고려해야 한다:

### 클러스터 구성

| 항목 | 개발 | 프로덕션 |
|------|------|---------|
| 노드 수 | 1 | 최소 3 (Raft 합의에 과반수 필요) |
| 복제 계수 | 1 | 3 (노드 1개 장애에도 데이터 유지) |
| `--smp` | 1 | 코어 수 (전용 서버) |
| `--memory` | 2G | 시스템 메모리의 80% |
| `--overprovisioned` | 있음 | **없음** (전용 서버) |

### Tiered Storage

Redpanda의 Tiered Storage는 오래된 세그먼트를 S3/GCS로 자동 이관한다. `playground.audit.events`처럼 보관 기간이 긴 토픽(30일)에서 로컬 디스크 비용을 절감할 수 있다.

```yaml
# redpanda.yaml (프로덕션)
cloud_storage_enabled: true
cloud_storage_region: ap-northeast-2
cloud_storage_bucket: my-redpanda-tiered
```

### 모니터링

Redpanda는 Prometheus 메트릭을 내장 HTTP 엔드포인트(`/metrics`, 포트 9644)로 노출한다:

```bash
# 메트릭 확인
curl http://localhost:29644/public_metrics

# 주요 메트릭
# redpanda_kafka_request_latency_seconds  → 요청 지연시간
# redpanda_storage_log_read_bytes_total   → 디스크 읽기량
# redpanda_kafka_under_replicated_replicas → 복제 미달 파티션 (0이어야 정상)
```

Grafana 대시보드는 [Redpanda 공식 대시보드](https://grafana.com/grafana/dashboards/16262)를 사용하면 된다.

---

## 6. 이 프로젝트에서 배울 수 있는 Redpanda 활용 패턴

| 패턴 | 이 프로젝트의 적용 | 실무 확장 |
|------|-------------------|----------|
| **Schema Registry 내장 활용** | Avro 스키마를 Schema Registry에 등록하지 않고 ByteArray로 직접 관리 | 프로덕션에서는 자동 serde + 호환성 검증을 활용하는 것이 안전하다 |
| **Redpanda Connect 브릿지** | Jenkins HTTP → Kafka 토픽 변환 | 외부 시스템(Slack, GitHub 등)의 webhook을 Kafka로 통합할 때 동일 패턴 |
| **Console로 이벤트 추적** | 데모 시 토픽 메시지 실시간 확인 | 장애 시 메시지 내용/순서 확인, 컨슈머 래그 모니터링 |
| **rpk로 운영** | `rpk topic list`, `rpk group describe` | 토픽 설정 변경, 오프셋 리셋, 파티션 재배분 |
| **단일 바이너리 경량 인프라** | Docker Compose 1개 컨테이너로 Kafka+SR 대체 | CI/CD 파이프라인에서 통합 테스트 인프라로 활용 (Testcontainers와 조합) |

---

## 참조

- [Redpanda 공식 문서](https://docs.redpanda.com)
- [rpk CLI 레퍼런스](https://docs.redpanda.com/current/reference/rpk/)
- [Kafka API 호환성 가이드](https://docs.redpanda.com/current/develop/kafka-clients/)
- 프로젝트 내 관련 문서:
  - [docs/patterns/06-redpanda-connect.md](../patterns/06-redpanda-connect.md) — Connect 브릿지 패턴
  - [docs/patterns/07-topic-message-design.md](../patterns/07-topic-message-design.md) — 토픽/스키마 설계
  - [docs/infra/01-connect-streams.md](01-connect-streams.md) — Connect Streams 모드
