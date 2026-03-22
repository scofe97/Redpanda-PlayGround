# ADR-003: Redpanda Connect를 전송 전용 브릿지로 한정

## 상태

Accepted

## 맥락

Redpanda Connect(구 Benthos)는 데이터 파이프라인 도구로, 입력 → 처리 → 출력의 세 레이어를 지원한다. 처리 레이어에서는 필터링, 변환, 조건 분기, 블러핑(bloblang 스크립트) 등 복잡한 비즈니스 로직을 구현할 수 있다.

초기에 Jenkins Webhook에서 오는 이벤트를 Kafka로 전달하는 경로를 설계할 때, Connect의 처리 레이어를 활용하여 이벤트 필터링과 라우팅 로직을 구현하는 방안을 검토했다. 예를 들어 "Job 상태가 SUCCESS인 이벤트만 통과시키고, 나머지는 dead-letter 토픽으로 보내는" 로직을 Connect의 bloblang으로 작성할 수 있다.

## 결정

Redpanda Connect는 HTTP 수신 → Kafka 전송의 단순 전달(transport) 역할만 담당하도록 범위를 제한한다. 이벤트 필터링, 상태 판단, 라우팅 결정 등 비즈니스 로직은 모두 Spring Boot 애플리케이션에서 처리한다.

Connect 설정 파일에는 다음 구조만 허용한다.

```yaml
input:
  http_server:
    path: /jenkins-webhook/webhook/jenkins

output:
  kafka_franz:
    seed_brokers: [...]
    topic: jenkins-events
```

파싱, 변환, 조건 분기는 Connect 설정에 포함하지 않는다.

## 근거

비즈니스 로직을 Connect에 넣지 않는 이유는 세 가지다.

첫째, 테스트 불가능성이다. Connect의 bloblang 스크립트는 단위 테스트를 작성하기 어렵다. Spring Boot 서비스는 JUnit으로 로직을 격리하여 테스트할 수 있지만, Connect 처리 레이어는 전체 파이프라인을 띄워야만 동작을 검증할 수 있다.

둘째, 디버깅 복잡도다. 이벤트가 예상과 다르게 처리될 때, 로직이 Connect와 Spring Boot에 분산되어 있으면 어느 쪽에서 문제가 생겼는지 추적이 어렵다. 로직을 Spring Boot에 집중시키면 로그와 디버거로 한 곳에서 추적할 수 있다.

셋째, 역할 명확성이다. Connect는 "데이터를 A에서 B로 옮기는 도구"이고, Spring Boot는 "비즈니스 규칙을 처리하는 도구"다. 각 도구의 설계 목적에 맞게 역할을 배분하면 코드의 응집도가 높아진다.

## 대안

**Connect에서 전부 처리**: 필터링과 변환까지 bloblang으로 구현하는 방법이다. 서비스 수가 줄어드는 장점이 있지만, 로직 분산과 테스트 불가능성 문제가 있어 폐기했다.

**Connect 없이 Spring Boot만 사용**: HTTP 엔드포인트를 Spring Boot가 직접 수신하고 Kafka로 전달하는 방법이다. 가능하지만 Redpanda Connect 학습이 이 PoC의 목적 중 하나이므로, Connect를 브릿지로만 쓰더라도 인프라 구성 경험을 얻기 위해 유지하기로 했다.

## 영향

- Connect 설정 파일이 단순해지고, 변경 이유가 "전달 경로 변경"으로 한정된다
- 비즈니스 로직 전체가 Spring Boot에 있으므로 테스트 커버리지를 Spring Boot 레벨에서 관리할 수 있다
- Connect 설정 오류와 비즈니스 로직 오류가 발생 위치로 명확히 구분된다
- Jenkins Webhook 인증, 이벤트 파싱, 상태 매핑 로직은 모두 Spring Boot `JenkinsWebhookConsumer`에서 구현한다
