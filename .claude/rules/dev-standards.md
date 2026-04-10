# 개발 표준 규칙

## 외과적 변경 (Surgical Changes)

- 요청 범위의 코드**만** 수정 -- 주변 "개선" 금지
- 기존 네이밍/포맷/패턴을 그대로 따름
- 내가 추가한 코드에서 발생한 고아(unused import 등)만 정리
- diff 최소화: 포맷팅/공백 변경은 로직 변경과 분리

## 함수 추출 기준

추출하지 않는 경우:
- 2~3줄 이하이고 호출 지점이 1곳인 코드
- 함수 이름이 본문과 거의 같은 경우

추출하는 경우:
- 동일 코드가 3곳 이상 반복 (Rule of Three)
- 5줄 이상이고 독립된 의미 단위
- 테스트에서 직접 검증해야 하는 로직

## 타입 안전성

- `Map<String, Object>` 사용 지양 -- 전용 DTO(record/class)로 대체
- 외부 API 응답은 반드시 타입이 있는 DTO로 매핑
- `@JsonIgnoreProperties(ignoreUnknown = true)`로 불필요 필드 무시

## Java 21 코딩 규칙

### 금지 사항
- `@SuppressWarnings` 사용 금지 -- 워닝 무시 대신 근본 원인 해결
- `Collectors.toList()` 금지 -- `.toList()` 사용 (불변 리스트)
- 문자열 `+` 연결로 메시지 조립 금지 -- `.formatted()` 사용
- raw type 사용 금지

### var 적극 사용
타입이 우변에서 명확할 때 좌변 타입을 반복하지 않는다.
```java
var tool = supportToolMapper.findById(id);
var preset = new MiddlewarePreset();
for (var entry : entries) { ... }
```

### switch 표현식
```java
var path = switch (impl) {
    case JENKINS -> "/api/json";
    case GITLAB -> "/api/v4/version";
    default -> "/";
};
```

### record (DTO / Value Object)
- 응답 DTO는 `record`로 선언
- 요청 DTO는 `@Getter @Setter` 클래스 (Jackson 바인딩)

### null 처리
- 생성자: `Objects.requireNonNull(param, "param required")`
- JPA 조회: null 체크 후 BusinessException (`getXxxOrThrow` 패턴)
- Optional은 반환 타입에만 사용, 필드/파라미터에 사용 금지

### 기타
- `String.formatted()` 사용
- `List.of()`, `Map.of()`, `Set.of()` 불변 컬렉션 팩토리
- sealed interface (닫힌 계층)
- text block (SQL, JSON 리터럴)
- pattern matching instanceof

## 메서드 호출 줄바꿈 (Comma-Leading)

인자가 한 줄에 들어오지 않을 때 comma-leading 스타일:
```java
eventPublisher.publish(
        AGGREGATE_TYPE, executionId
        , EXECUTION_COMPLETED_EVENT_TYPE
        , avroSerializer.serialize(event)
        , Topics.PIPELINE_EVT_COMPLETED, executionId
);
```

## Observability 로그 마커 규칙

이벤트 발행 클래스에서 Kafka 이벤트 발행 직후 반드시 아래 형식으로 로그:

| 마커 | 용도 |
|------|------|
| `[StepChanged]` | Job 상태 변경 -- Timeline 패널 |
| `[ExecutionCompleted]` | 파이프라인 완료 -- Status 패널 |
| `[DagJobDispatched]` | DAG Job 시작 -- Logs 패널 |

형식: `[마커] executionId={}, jobName={}, status={}, ...`

마커명/필드 순서 변경 시 `infra/k8s/monitoring/grafana/dashboards/pipeline-tracker.json`의 Loki 쿼리도 함께 수정.

## Avro Schema 규칙

- BACKWARD 호환 유지
- 버전 접미사(`.v1`) 사용하지 않음 -- Schema Registry가 스키마 진화를 담당

## 보안

- 사용자 입력은 항상 검증 (허용 목록 방식)
- 시크릿/API 키 하드코딩 금지 -- 환경변수 사용
- SQL 파라미터화, 쉘 인자 이스케이프
