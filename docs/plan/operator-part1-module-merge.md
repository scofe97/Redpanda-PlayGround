# Part 1: 모듈 통합 (operator-stub + pipeline + app → operator)

## 전제조건
- 저장소: `https://github.com/scofe97/Redpanda-PlayGround`
- 기존 모듈: `app` (Boot, port 8070, public schema), `pipeline` (java-library), `operator-stub` (Boot, port 8072, operator_stub schema)
- 통합 결과: `operator` (Boot, port 8070, operator schema)

## 구조 변경

```
Before:                          After:
┌─────────────┐                  ┌─────────────────────────────────┐
│ app (Boot)  │──depends──┐      │ operator (Boot)                 │
│ port:8070   │           │      │ port:8070                       │
│ public      │           │      │ schema: operator                │
├─────────────┤           ▼      │                                 │
│operator-stub│     ┌──────────┐ │  ├── project/                   │
│ (Boot)      │     │ pipeline │ │  ├── purpose/                   │
│ port:8072   │     │ (lib)    │ │  ├── supporttool/               │
│operator_stub│     └──────────┘ │  ├── pipeline/ (from pipeline)  │
└─────────────┘                  │  ├── operatorjob/ (from stub)   │
                                 │  └── common/                    │
                                 └─────────────────────────────────┘
```

---

## Step 1: 디렉토리 생성

```bash
mkdir -p operator/src/main/java/com/study/playground/operator
mkdir -p operator/src/main/resources/db/migration
mkdir -p operator/src/main/resources/connect-templates
mkdir -p operator/src/test/java/com/study/playground/operator
```

## Step 2: operator/build.gradle 생성

```groovy
plugins {
    id 'org.springframework.boot'
}

dependencies {
    implementation project(':common')
    implementation project(':common-kafka')

    // Spring Boot
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.boot:spring-boot-starter-aop'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'

    // QueryDSL
    implementation 'com.querydsl:querydsl-jpa:5.1.0:jakarta'
    annotationProcessor 'com.querydsl:querydsl-apt:5.1.0:jakarta'
    annotationProcessor 'jakarta.annotation:jakarta.annotation-api'
    annotationProcessor 'jakarta.persistence:jakarta.persistence-api'

    // Database
    runtimeOnly 'org.postgresql:postgresql'
    implementation 'org.flywaydb:flyway-core'
    implementation 'org.flywaydb:flyway-database-postgresql'

    // Micrometer
    implementation 'io.micrometer:micrometer-registry-prometheus'

    // Loki4j
    implementation 'com.github.loki4j:loki-logback-appender:1.6.0'

    // OpenTelemetry
    implementation 'io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter'

    // SpringDoc OpenAPI
    implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.4'

    // Jackson
    implementation 'com.fasterxml.jackson.core:jackson-databind'
    implementation 'com.fasterxml.jackson.datatype:jackson-datatype-jsr310'

    // Lombok
    compileOnly 'org.projectlombok:lombok:1.18.42'
    annotationProcessor 'org.projectlombok:lombok:1.18.42'
    testCompileOnly 'org.projectlombok:lombok:1.18.42'
    testAnnotationProcessor 'org.projectlombok:lombok:1.18.42'

    // Test
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springframework.kafka:spring-kafka-test'
    testImplementation 'com.tngtech.archunit:archunit-junit5:1.3.0'
}

tasks.named('bootRun') {
    def profile = providers.environmentVariable('SPRING_PROFILES_ACTIVE').getOrElse('')
    if (profile) {
        jvmArgs "-Dspring.profiles.active=${profile}"
    }
    jvmArgs "-Dotel.java.global-autoconfigure.enabled=true"
    jvmArgs "-Dotel.service.name=operator"
}

tasks.named('test') {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
    }
}
```

## Step 3: 소스 파일 복사

```bash
# app → operator
cp -r app/src/main/java/com/study/playground/common operator/src/main/java/com/study/playground/operator/
cp -r app/src/main/java/com/study/playground/project operator/src/main/java/com/study/playground/operator/
cp -r app/src/main/java/com/study/playground/purpose operator/src/main/java/com/study/playground/operator/
cp -r app/src/main/java/com/study/playground/supporttool operator/src/main/java/com/study/playground/operator/

# pipeline → operator/pipeline
cp -r pipeline/src/main/java/com/study/playground/pipeline operator/src/main/java/com/study/playground/operator/

# operator-stub → operator/operatorjob
mkdir -p operator/src/main/java/com/study/playground/operator/operatorjob
cp -r operator-stub/src/main/java/com/study/playground/operatorstub/domain operator/src/main/java/com/study/playground/operator/operatorjob/
cp -r operator-stub/src/main/java/com/study/playground/operatorstub/listener operator/src/main/java/com/study/playground/operator/operatorjob/
cp -r operator-stub/src/main/java/com/study/playground/operatorstub/publisher operator/src/main/java/com/study/playground/operator/operatorjob/
cp -r operator-stub/src/main/java/com/study/playground/operatorstub/api operator/src/main/java/com/study/playground/operator/operatorjob/
cp -r operator-stub/src/main/java/com/study/playground/operatorstub/fixture operator/src/main/java/com/study/playground/operator/operatorjob/

# 리소스
cp -r app/src/main/resources/connect-templates/* operator/src/main/resources/connect-templates/

# 테스트
cp -r app/src/test/java/com/study/playground/* operator/src/test/java/com/study/playground/operator/
```

## Step 4: 패키지 선언 일괄 변경

**변경 규칙:**

| 원본 패키지 | 변경 후 |
|---|---|
| `com.study.playground.common.audit` | `com.study.playground.operator.common.audit` |
| `com.study.playground.common.config` | `com.study.playground.operator.common.config` |
| `com.study.playground.project.` | `com.study.playground.operator.project.` |
| `com.study.playground.purpose.` | `com.study.playground.operator.purpose.` |
| `com.study.playground.supporttool.` | `com.study.playground.operator.supporttool.` |
| `com.study.playground.pipeline.` | `com.study.playground.operator.pipeline.` |
| `com.study.playground.operatorstub.` | `com.study.playground.operator.operatorjob.` |

**주의**: `com.study.playground.kafka.` (common-kafka)와 `com.study.playground.common.dto`, `com.study.playground.common.exception`, `com.study.playground.common.idempotency` (common 모듈)은 변경하지 않는다. operator 모듈 내부의 파일만 변경.

```bash
# operator 모듈 내 모든 Java 파일에 대해 패키지/임포트 치환
find operator/src -name "*.java" -exec sed -i \
  -e 's/package com\.study\.playground\.operatorstub/package com.study.playground.operator.operatorjob/g' \
  -e 's/import com\.study\.playground\.operatorstub/import com.study.playground.operator.operatorjob/g' \
  -e 's/package com\.study\.playground\.pipeline/package com.study.playground.operator.pipeline/g' \
  -e 's/import com\.study\.playground\.pipeline/import com.study.playground.operator.pipeline/g' \
  -e 's/package com\.study\.playground\.common\.audit/package com.study.playground.operator.common.audit/g' \
  -e 's/import com\.study\.playground\.common\.audit/import com.study.playground.operator.common.audit/g' \
  -e 's/package com\.study\.playground\.common\.config/package com.study.playground.operator.common.config/g' \
  -e 's/import com\.study\.playground\.common\.config/import com.study.playground.operator.common.config/g' \
  -e 's/package com\.study\.playground\.project/package com.study.playground.operator.project/g' \
  -e 's/import com\.study\.playground\.project/import com.study.playground.operator.project/g' \
  -e 's/package com\.study\.playground\.purpose/package com.study.playground.operator.purpose/g' \
  -e 's/import com\.study\.playground\.purpose/import com.study.playground.operator.purpose/g' \
  -e 's/package com\.study\.playground\.supporttool/package com.study.playground.operator.supporttool/g' \
  -e 's/import com\.study\.playground\.supporttool/import com.study.playground.operator.supporttool/g' \
  {} \;
```

**테스트 파일:**

```bash
find operator/src/test -name "*.java" -exec sed -i \
  -e 's/package com\.study\.playground\.arch/package com.study.playground.operator.arch/g' \
  -e 's/package com\.study\.playground;/package com.study.playground.operator;/g' \
  -e 's/import com\.study\.playground\.PlaygroundApplication/import com.study.playground.operator.OperatorApplication/g' \
  -e 's/PlaygroundApplicationTest/OperatorApplicationTest/g' \
  -e 's/PlaygroundApplication/OperatorApplication/g' \
  {} \;
```

## Step 5: OperatorApplication.java 생성

파일: `operator/src/main/java/com/study/playground/operator/OperatorApplication.java`

```java
package com.study.playground.operator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
        "com.study.playground.operator"
        , "com.study.playground.kafka"
        , "com.study.playground.common"
})
@EntityScan(basePackages = {
        "com.study.playground.operator"
        , "com.study.playground.kafka.outbox"
        , "com.study.playground.common.idempotency"
})
@EnableJpaRepositories(basePackages = {
        "com.study.playground.operator"
        , "com.study.playground.kafka.outbox"
        , "com.study.playground.common.idempotency"
})
@EnableScheduling
public class OperatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(OperatorApplication.class, args);
    }
}
```

## Step 6: application.yml 통합

파일: `operator/src/main/resources/application.yml`

```yaml
server:
  port: 8070

spring:
  servlet:
    multipart:
      max-file-size: 200MB
      max-request-size: 200MB
  application:
    name: operator
  datasource:
    url: jdbc:postgresql://localhost:25432/playground?currentSchema=operator
    username: ${DB_USERNAME:playground}
    password: ${DB_PASSWORD:playground}
    driver-class-name: org.postgresql.Driver
    hikari:
      data-source-properties:
        reWriteBatchedInserts: true
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
    properties:
      hibernate:
        default_schema: operator
        default_batch_fetch_size: 20
        jdbc:
          batch_size: 50
        order_inserts: true
        order_updates: true
        format_sql: true
  flyway:
    enabled: true
    schemas: operator
    locations: classpath:db/migration
    baseline-on-migrate: true
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP:localhost:19092}
    properties:
      schema.registry.url: ${SCHEMA_REGISTRY_URL:http://localhost:18081}
    consumer:
      group-id: operator-group
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.ByteArrayDeserializer
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.ByteArraySerializer

app:
  connect:
    url: ${CONNECT_URL:http://localhost:4195}

springdoc:
  swagger-ui:
    path: /swagger-ui.html
    tags-sorter: alpha
    operations-sorter: method
  api-docs:
    path: /v3/api-docs

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always

otel:
  java:
    global-autoconfigure:
      enabled: true
  service:
    name: operator
  exporter:
    otlp:
      endpoint: http://localhost:24318
      protocol: http/protobuf
  metrics:
    exporter: none
  logs:
    exporter: none

logging:
  level:
    org.hibernate.SQL: DEBUG
    org.hibernate.type.descriptor.sql.BasicBinder: TRACE
    com.study.playground.operator: DEBUG
    org.apache.kafka: WARN

pipeline:
  max-concurrent-jobs: 3
  webhook-timeout-minutes: 5
  job-max-retries: 2
  stale-execution-timeout-minutes: 30
  executor-wait-timeout-minutes: 5

outbox:
  batch-size: 50
  poll-interval-ms: 500
  max-retries: 5
  cleanup-retention-days: 7
  cleanup-cron: "0 0 3 * * *"
```

파일: `operator/src/main/resources/application-gcp.yml`

```yaml
spring:
  datasource:
    url: jdbc:postgresql://34.47.83.38:30275/playground?currentSchema=operator
  kafka:
    bootstrap-servers: 34.47.83.38:31092
    properties:
      schema.registry.url: http://34.47.83.38:31081

app:
  connect:
    url: http://34.47.83.38:31195

otel:
  exporter:
    otlp:
      endpoint: http://34.47.83.38:30318
```

## Step 7: Flyway 마이그레이션

app의 V1~V49 마이그레이션을 그대로 복사:
```bash
cp app/src/main/resources/db/migration/* operator/src/main/resources/db/migration/
```

신규 마이그레이션 `V50__migrate_to_operator_schema.sql`:

```sql
-- operator 스키마 생성 및 기존 테이블 이동
CREATE SCHEMA IF NOT EXISTS operator;

-- public 스키마에서 operator 스키마로 테이블 이동
ALTER TABLE IF EXISTS public.project SET SCHEMA operator;
ALTER TABLE IF EXISTS public.support_tool SET SCHEMA operator;
ALTER TABLE IF EXISTS public.purpose SET SCHEMA operator;
ALTER TABLE IF EXISTS public.purpose_entry SET SCHEMA operator;
ALTER TABLE IF EXISTS public.job SET SCHEMA operator;
ALTER TABLE IF EXISTS public.job_dependency SET SCHEMA operator;
ALTER TABLE IF EXISTS public.pipeline SET SCHEMA operator;
ALTER TABLE IF EXISTS public.pipeline_version SET SCHEMA operator;
ALTER TABLE IF EXISTS public.pipeline_step SET SCHEMA operator;
ALTER TABLE IF EXISTS public.outbox_event SET SCHEMA operator;
ALTER TABLE IF EXISTS public.processed_event SET SCHEMA operator;

-- operator_stub 스키마에서 operator 스키마로 테이블 이동
ALTER TABLE IF EXISTS operator_stub.operator_job SET SCHEMA operator;
-- operator_stub의 outbox_event는 삭제 (operator의 것과 합칠 수 없음)
DROP TABLE IF EXISTS operator_stub.outbox_event;

-- 기존 스키마 정리
DROP SCHEMA IF EXISTS operator_stub CASCADE;
```

## Step 8: settings.gradle 수정

```diff
-include 'common', 'common-kafka', 'pipeline', 'app', 'executor', 'operator-stub'
+include 'common', 'common-kafka', 'operator', 'executor'
```

## Step 9: executor 모듈의 cross-schema 쿼리 수정

**파일**: `executor/.../infrastructure/persistence/JobDefinitionQueryAdapter.java`

```diff
-        FROM public.job j
-        JOIN public.purpose p ON p.id = CAST(j.preset_id AS BIGINT)
-        JOIN public.purpose_entry pe ON pe.purpose_id = p.id AND pe.category = 'CI_CD_TOOL'
-        JOIN public.support_tool st ON st.id = pe.tool_id
+        FROM operator.job j
+        JOIN operator.purpose p ON p.id = CAST(j.preset_id AS BIGINT)
+        JOIN operator.purpose_entry pe ON pe.purpose_id = p.id AND pe.category = 'CI_CD_TOOL'
+        JOIN operator.support_tool st ON st.id = pe.tool_id
```

**파일**: `executor/.../infrastructure/jenkins/JenkinsClient.java`

```diff
-var sql = "SELECT url, username, credential FROM public.support_tool WHERE id = ?";
+var sql = "SELECT url, username, credential, max_executors FROM operator.support_tool WHERE id = ?";
```

## Step 10: 기존 모듈 삭제

```bash
rm -rf app/ pipeline/ operator-stub/
```

## Step 11: ArchitectureBoundaryTest 수정

- `@AnalyzeClasses(packages = "com.study.playground.operator", ...)` 로 변경
- `PlaygroundApplicationTest.java` → `OperatorApplicationTest.java` 리네임 + `OperatorApplication` 참조

## 검증

```bash
./gradlew clean :operator:compileJava   # 컴파일 확인
./gradlew :executor:compileJava          # executor cross-schema 변경 확인
./gradlew :operator:bootRun              # 기동 확인
```
