# Jenkins 설정 및 Webhook 발송 전략

이 문서는 Jenkins 환경 구성(Part 1)과 빌드 완료 후 webhook 발송 전략(Part 2)을 다룬다.

---

# Part 1 — Jenkins 설정

## 1. Jenkins 컨테이너 구성

### Dockerfile

```dockerfile
FROM jenkins/jenkins:lts-jdk17

ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8
ENV JAVA_OPTS="-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Djenkins.install.runSetupWizard=false"

RUN jenkins-plugin-cli --plugins \
    configuration-as-code \
    job-dsl \
    workflow-job workflow-cps workflow-basic-steps \
    workflow-durable-task-step workflow-step-api workflow-aggregator \
    pipeline-model-definition pipeline-stage-step pipeline-input-step
```

핵심 설정:
- **UTF-8 로케일**: `LANG=C.UTF-8` + `LC_ALL=C.UTF-8` — 한글 깨짐 방지
- **JVM 인코딩**: `JAVA_OPTS`에 `-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8`
- **셋업 위자드 스킵**: `-Djenkins.install.runSetupWizard=false` — CasC로 자동 설정하므로 위자드 불필요
- **CasC 플러그인**: `configuration-as-code` — `casc.yaml`로 Jenkins 설정을 코드로 관리
- **Folder 플러그인**: `cloudbees-folder` — PipelineJobType별 폴더 그룹화 (2026-03-20 추가)

### docker-compose.jenkins.yml

```yaml
jenkins:
  build:
    context: ./jenkins
    dockerfile: Dockerfile
  environment:
    JAVA_OPTS: -Xmx1g -Xms512m -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Djenkins.install.runSetupWizard=false
    CASC_JENKINS_CONFIG: /var/jenkins_home/casc.yaml
  volumes:
    - jenkins-data:/var/jenkins_home
    - ./jenkins/casc.yaml:/var/jenkins_home/casc.yaml:ro
    - ./jenkins/Jenkinsfile:/var/jenkins_home/Jenkinsfile:ro
    - ./jenkins/Jenkinsfile-deploy:/var/jenkins_home/Jenkinsfile-deploy:ro
```

**주의**: docker-compose의 `JAVA_OPTS`가 Dockerfile의 `ENV JAVA_OPTS`를 **덮어쓴다.** 따라서 UTF-8 설정과 `-Djenkins.install.runSetupWizard=false`는 양쪽 모두에 명시해야 한다. Jenkins는 기본 CSRF 보호를 유지하며, 비밀번호 기반 POST 자동화는 crumb + session cookie를 함께 보내야 한다. CasC의 Job은 `sandbox(true)`로 실행되어 별도 스크립트 승인이 불필요하다.

### CasC (Configuration as Code)

```yaml
# casc.yaml
jenkins:
  securityRealm:
    local:
      users:
        - id: admin
          password: admin
```

CasC가 동작하려면 `configuration-as-code` 플러그인이 **Dockerfile에서 사전 설치**되어야 한다. 플러그인 없이 `casc.yaml`을 마운트해도 무시된다.

---

## 2. Job 등록 방식

`setup-jenkins.sh` 스크립트가 Jenkins XML API로 2개 Pipeline Job을 생성한다:

| Job | 역할 | 스텝 |
|-----|------|------|
| `playground-build` | 빌드 시뮬레이션 | Checkout → Build → Test |
| `playground-deploy` | 배포 시뮬레이션 | Pre-Deploy → Stop → Deploy → Verify |

각 Job은 빌드 완료 후 `rpk produce`로 Kafka 토픽에 직접 결과를 발행한다. 페이로드에는 `executionId`, `stepOrder`, `result`, `buildNumber`, `jobName`, `duration`, `url`이 포함된다. 이것이 Break-and-Resume 패턴의 핵심이다.

```bash
# Job 등록 명령
make setup-jenkins
# 또는
JENKINS_PASS=admin bash infra/docker/shared/scripts/setup-jenkins.sh
```

---

## 3. 해결한 이슈

### 3-1. 한글 깨짐 (mojibake)

**증상**: Jenkins 웹 UI에서 Pipeline 스크립트의 한글이 `?` 또는 깨진 문자로 표시됨.

**원인**: Jenkins JVM의 기본 charset이 UTF-8이 아님. 컨테이너 로케일도 미설정.

**해결**:
1. Dockerfile에 `LANG=C.UTF-8`, `LC_ALL=C.UTF-8` 추가
2. `JAVA_OPTS`에 `-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8` 추가
3. docker-compose의 `JAVA_OPTS`에도 동일 설정 추가 (덮어쓰기 방지)

**확인**: 소스 파일(Jenkinsfile, Jenkinsfile-deploy)은 정상 UTF-8이었다. 파일 인코딩이 아닌 JVM/컨테이너 인코딩이 문제였다.

### 3-2. CasC 미적용 (비밀번호 변경 불가)

**증상**: `casc.yaml`에서 비밀번호를 변경해도 적용되지 않음. 초기 관리자 비밀번호(`initialAdminPassword`)가 계속 사용됨.

**원인**: `configuration-as-code` 플러그인이 Dockerfile에 없었다.

**해결**:
1. Dockerfile의 `jenkins-plugin-cli`에 `configuration-as-code` 추가
2. 셋업 위자드 스킵 (`-Djenkins.install.runSetupWizard=false`)
3. 기존 Jenkins 볼륨 삭제 후 재생성 (이전 설정이 볼륨에 남아있으므로)

```bash
# 볼륨 초기화가 필요한 경우
docker compose -f docker-compose.jenkins.yml stop jenkins
docker compose -f docker-compose.jenkins.yml rm -f jenkins
docker volume rm docker_jenkins-data
docker compose -f docker-compose.jenkins.yml up -d --build jenkins
```

### 3-3. Groovy init script Permission denied

**증상**: Dockerfile의 `COPY ... /usr/share/jenkins/ref/init.groovy.d/`로 복사한 Groovy 스크립트가 `Permission denied`로 실행 안 됨.

**원인**: Jenkins ref 메커니즘은 `jenkins_home`에 이미 파일이 있으면 덮어쓰지 않는다. 볼륨이 이미 존재하면 ref 복사가 실패한다.

**해결**: Groovy init script 대신 **CasC**로 비밀번호를 설정한다. init script가 필요한 경우 docker-compose에서 bind mount로 직접 연결한다:

```yaml
volumes:
  - ./jenkins/my-init.groovy:/var/jenkins_home/init.groovy.d/my-init.groovy:ro
```

---

## 4. 접속 정보

| 항목 | 값 |
|------|---|
| URL | http://localhost:29080 |
| 사용자 | admin |
| 비밀번호 | admin |
| API Token | `infra/docker/.env`의 `JENKINS_TOKEN` |

---

## 5. 운영 명령어

```bash
# Jenkins 시작 (빌드 포함)
make infra-all

# Job 등록/갱신
make setup-jenkins

# Jenkins 로그 확인
docker logs -f playground-jenkins

# Jenkins 볼륨 초기화 (설정 리셋 필요 시)
docker compose -f docker-compose.jenkins.yml stop jenkins
docker compose -f docker-compose.jenkins.yml rm -f jenkins
docker volume rm docker_jenkins-data
docker compose -f docker-compose.jenkins.yml up -d --build jenkins
```

---

# Part 2 — 빌드 완료 알림 전략

> **[업데이트 2026-03]**: Jenkins 빌드 완료 알림은 Redpanda Connect HTTP 엔드포인트를 거치지 않고,
> RunListener Groovy init-script가 `rpk produce`로 Kafka 토픽에 직접 발행하는 방식으로 변경되었다.
> 아래 §2-1~§2-3(curl/HTTP 방식)은 과거 검토 이력으로 남겨두며, 현재 채택된 방식은 §2-4다.

## 1. 목표

Jenkins 빌드가 완료되면 그 결과(성공/실패, 소요시간, 빌드 번호 등)를 Kafka 토픽에 발행하여,
executor/operator-stub 컨슈머가 파이프라인 상태를 이어서 처리(Break-and-Resume)할 수 있게 한다.

핵심 요구사항은 3가지다:
1. **빌드 완료 감지**: 모든 Jenkins 잡의 완료를 빠짐없이 감지
2. **결과 전달**: 실제 빌드 결과(SUCCESS/FAILURE)와 메타데이터를 정확히 전송
3. **비간섭**: 고객이 커스텀한 파이프라인 스크립트에 영향을 주지 않을 것

---

## 2. 방법 비교

Jenkins에서 외부 시스템으로 빌드 완료를 알리는 방법은 4가지가 있다.

### 2-1. 빌드 스크립트 내 curl — HTTP (과거 PoC, 미사용)

빌드 셸 스크립트 마지막에 `curl`로 직접 HTTP POST를 보낸다.
Connect HTTP 엔드포인트가 제거된 현재는 사용하지 않는다.

```bash
# [미사용] Jenkinsfile 또는 Shell Script
curl -s -X POST "http://connect:4195/jenkins-webhook/webhook/jenkins" \
  -H "Content-Type: application/json" \
  -d '{"result": "SUCCESS", "duration": 4000, ...}'
```

### 2-2. Jenkinsfile post 블록 — HTTP (과거 검토, 미사용)

Declarative Pipeline의 `post` 섹션에서 Groovy로 HTTP 호출한다.
Connect HTTP 엔드포인트가 제거된 현재는 사용하지 않는다.

```groovy
// [미사용] — Connect HTTP 엔드포인트 제거로 더 이상 유효하지 않음
pipeline {
    stages { ... }
    post {
        always {
            script {
                def conn = new URL("http://connect:4195/jenkins-webhook/webhook/jenkins").openConnection()
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.write("""{"result":"${currentBuild.result}"}""".bytes)
                conn.responseCode
            }
        }
    }
}
```

### 2-3. HTTP Request Plugin (과거 검토, 미사용)

Jenkins 플러그인을 설치하여 Post-build Action에서 UI로 설정한다.
Connect HTTP 엔드포인트가 제거된 현재는 사용하지 않는다.

```groovy
// [미사용]
post {
    always {
        httpRequest url: 'http://connect:4195/jenkins-webhook/webhook/jenkins',
            httpMode: 'POST',
            contentType: 'APPLICATION_JSON',
            requestBody: '{"result": "${currentBuild.result}"}'
    }
}
```

### 2-4. RunListener Groovy Init Script — rpk produce (현재 채택)

`init.groovy.d/`에 전역 리스너를 등록하여, 모든 잡의 빌드 완료를 자동 감지한다.

```groovy
class WebhookListener extends RunListener<Run> {
    void onFinalized(Run run) {
        def payload = [job_name: run.parent.fullDisplayName,
                       status: run.result?.toString(),
                       build_number: run.number]
        Thread.start { /* HTTP POST */ }
    }
}
```

---

## 3. 장단점

| 방법 | 장점 | 단점 | 상태 |
|------|------|------|------|
| **curl (스크립트)** | 플러그인 불필요, 구현 간단 | 모든 잡 스크립트에 삽입 필요, 고객 커스텀 시 누락 위험, 동기 블로킹 | 미사용 |
| **Jenkinsfile post** | 파이프라인 코드로 관리 가능, 실제 result/duration 사용 | 잡마다 작성 필요, Freestyle 잡은 미지원 | 미사용 |
| **HTTP Request Plugin** | UI 설정 가능, 재시도 옵션 | 플러그인 설치/관리 필요, 잡마다 설정 필요 | 미사용 |
| **RunListener + rpk produce (전역)** | 전체 잡 자동 적용, 고객 파이프라인 비간섭, Connect 없이 Kafka 직접 발행 | Jenkins 재시작 시 재등록, Groovy 보안 설정 필요 | **현재 채택** |

---

## 4. 실무 사례 — TPS에서 전역 리스너를 검토하는 이유

TPS는 CI/CD 플랫폼으로, 고객이 Jenkins 파이프라인을 직접 커스텀한다.
이 환경에서 방법 1~3은 모두 **잡 스크립트에 webhook 코드를 삽입**해야 하므로,
고객이 파이프라인을 수정하면 webhook이 누락되거나 깨질 수 있다.

```
문제 시나리오:
1. TPS가 Jenkinsfile에 post { httpRequest ... } 삽입
2. 고객이 파이프라인을 커스텀하면서 post 블록 수정/삭제
3. 빌드는 되지만 완료 이벤트가 안 나감
4. TPS 파이프라인 상태가 WAITING_COMPLETION에서 멈춤 (타임아웃까지 대기)
```

전역 RunListener는 Jenkins 코어 API(`hudson.model.listeners.RunListener`)를 사용하므로
개별 잡의 스크립트와 완전히 독립적이다. 고객이 파이프라인을 어떻게 수정하든
빌드가 끝나면 `onCompleted()`가 호출된다.

```
해결:
1. init.groovy.d/에 RunListener 등록 (Jenkins 시작 시 자동 로드)
2. 고객이 파이프라인을 자유롭게 커스텀
3. 빌드 완료 → RunListener가 자동 감지 → rpk produce로 Kafka 직접 발행
4. 고객 스크립트와 완료 알림 로직이 완전 분리
```

실제 TPS 검토안 코드: `tps_manifest/docs/redpanda-connect/jenkins/scripts/webhook-init-script.groovy`

주요 설계 포인트:
- **K8s 내부 DNS 사용**: `redpanda-connect.trb-oss.svc.cluster.local` — 외부 노출 없이 클러스터 내부 통신
- **`onFinalized` 시점**: `onCompleted`보다 늦게 호출되어 빌드 데이터가 완전히 확정된 상태
- **비동기 전송**: `Thread.start`로 webhook 전송이 Jenkins 컨트롤러를 블로킹하지 않음
- **중복 방지**: 등록 전 기존 리스너를 제거하여 Jenkins 재시작 시 중복 등록 방지

---

## 5. 흐름

### 현재 구현 (rpk produce 직접 발행)

```
[Jenkins 잡 실행]
  고객 커스텀 파이프라인 (실제 빌드/테스트/배포)
  빌드 완료
    ↓
[RunListener.onCompleted()] ← Jenkins 코어가 자동 호출
  Thread.start {
    rpk produce → Kafka 토픽 (비동기, 실제 결과)
  }
    → Kafka (executor/operator-stub 컨슈머 토픽)
      → JobCompletionConsumer (executor 또는 operator-stub)
        → PipelineEngine.resumeAfterCompletion()
```

### 과거 PoC (미사용 — 참고용)

```
[Jenkins 빌드 스크립트]
  sleep 2 (시뮬레이션)
  curl → Connect HTTP (동기, 하드코딩 결과)
    → Kafka (playground.webhook.inbound)
      → Spring WebhookEventConsumer
        → JenkinsWebhookHandler
          → PipelineEngine.resumeAfterWebhook()
```

현재 구현은 Redpanda Connect HTTP 엔드포인트를 거치지 않고 `rpk produce`로 Kafka에 직접 발행한다.
Jenkins groovy init-script가 RunListener를 통해 모든 잡의 완료를 전역으로 감지하므로,
고객 파이프라인 스크립트를 수정하지 않아도 된다.

---

## 6. RunListener에서 전달 가능한 값

`RunListener.onFinalized(Run run)`에서 `run` 객체를 통해 접근 가능한 데이터다.

### 기본 빌드 정보

| 필드 | API | 타입 | 설명 |
|------|-----|------|------|
| 잡 이름 | `run.parent.fullDisplayName` | String | 폴더 경로 포함 전체 이름 (예: `folder/my-pipeline`) |
| 잡 URL명 | `run.parent.fullName` | String | URL에 사용되는 이름 |
| 빌드 번호 | `run.number` | int | 이 잡의 순차 빌드 번호 |
| 빌드 결과 | `run.result?.toString()` | String | `SUCCESS`, `FAILURE`, `UNSTABLE`, `ABORTED`, `NOT_BUILT` |
| 빌드 URL | `run.absoluteUrl` | String | `http://jenkins:8080/job/my-pipeline/42/` |

### 시간 정보

| 필드 | API | 타입 | 설명 |
|------|-----|------|------|
| 시작 시각 | `run.startTimeInMillis` | long | epoch millis |
| 소요 시간 | `run.duration` | long | 밀리초 단위 실제 빌드 시간 |
| 대기 시간 | `run.getTimeInMillis()` | long | 큐 진입부터 시작까지 대기 시간 |
| 타임스탬프 | `run.getTimestamp()` | Calendar | 빌드 시작 Calendar 객체 |

### 파이프라인 파라미터 (커스텀 값)

빌드 시 전달한 파라미터를 읽을 수 있다. TPS에서 `EXECUTION_ID`, `STEP_ORDER`를 파라미터로 넘기면
리스너에서 꺼내 webhook 페이로드에 포함할 수 있다.

```groovy
// 파라미터 접근
def params = run.getAction(hudson.model.ParametersAction.class)
if (params) {
    params.parameters.each { p ->
        // p.name, p.value
    }
}

// 특정 파라미터 직접 접근
def executionId = params?.getParameter("EXECUTION_ID")?.value
def stepOrder = params?.getParameter("STEP_ORDER")?.value
```

| 필드 | API | 설명 |
|------|-----|------|
| 모든 파라미터 | `run.getAction(ParametersAction)` | 빌드 시 전달된 Key-Value 목록 |
| 특정 파라미터 | `.getParameter("KEY")?.value` | 이름으로 단건 조회 |
| 환경변수 | `run.getEnvironment(listener)` | 빌드 환경변수 전체 (PATH 등 포함) |

### 빌드 원인 (누가/무엇이 트리거했는가)

| 필드 | API | 설명 |
|------|-----|------|
| 트리거 원인 | `run.getCauses()` | `UserIdCause`, `TimerTriggerCause`, `RemoteCause` 등 |
| 트리거 사용자 | `cause.userId` (UserIdCause) | 수동 실행한 사용자 ID |
| SCM 변경 | `run.getChangeSets()` | Git 커밋 목록 (커밋 해시, 작성자, 메시지) |

### 실무 페이로드 예시

위 API를 조합하면 다음과 같은 페이로드를 구성할 수 있다:

```groovy
def params = run.getAction(hudson.model.ParametersAction.class)
def payload = [
    // 기본 빌드 정보
    source:       "jenkins-global-hook",
    job_name:     run.parent.fullDisplayName,
    build_number: run.number,
    status:       run.result?.toString() ?: "UNKNOWN",
    build_url:    run.absoluteUrl,

    // 시간 정보
    start_time:   new Date(run.startTimeInMillis).format("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
    duration_ms:  run.duration,

    // TPS 커스텀 파라미터 (파이프라인 실행 추적)
    execution_id: params?.getParameter("EXECUTION_ID")?.value,
    step_order:   params?.getParameter("STEP_ORDER")?.value,

    // 트리거 정보
    trigger:      run.getCauses().collect { it.shortDescription }.join(", ")
]
```

---

## 7. 주의점

### 7-1. onCompleted vs onFinalized — 시점 차이

| 콜백 | 시점 | result 확정 | 로그 완료 |
|------|------|:-----------:|:---------:|
| `onCompleted` | 빌드 로직 종료 직후 | O | X (후처리 중) |
| `onFinalized` | 모든 후처리 완료 후 | O | O |

**`onFinalized`를 써야 한다.** `onCompleted` 시점에는 빌드 로그가 아직 닫히지 않았거나
post-build action이 실행 중일 수 있다. `onFinalized`는 Jenkins가 빌드를 완전히 마감한 후 호출된다.

### 7-2. 비동기 전송 필수

`onCompleted`는 Jenkins 컨트롤러 스레드에서 호출된다.
여기서 동기 `rpk produce` 호출을 하면 Kafka 응답이 느릴 때 Jenkins 전체가 블로킹될 수 있다.
반드시 `Thread.start { ... }`로 비동기 처리해야 한다.

### 7-3. run.result가 null일 수 있다

빌드가 비정상 종료되면(Jenkins 재시작, kill 등) `run.result`가 null이다.
반드시 `run.result?.toString() ?: "UNKNOWN"`으로 null-safe 처리해야 한다.

### 7-4. Groovy 보안 샌드박스

Jenkins의 Script Security 플러그인이 활성화되어 있으면 `init.groovy.d/`의 스크립트도
제한될 수 있다. `init.groovy.d/`는 SYSTEM 권한으로 실행되므로 보통 문제없지만,
Jenkins 업그레이드 시 보안 정책 변경을 확인해야 한다.

### 7-5. 중복 등록 방지

Jenkins가 재시작되면 `init.groovy.d/` 스크립트가 다시 실행된다.
기존 리스너를 제거하지 않으면 같은 리스너가 중복 등록되어 webhook이 2번 전송된다.

```groovy
// 반드시 등록 전 기존 제거
def listeners = ExtensionList.lookup(RunListener.class)
listeners.removeAll(listeners.findAll { it.class.name.contains("Redpanda") })
listeners.add(new RedpandaStableListener())
```

### 7-6. Kafka/rpk 장애 시 이벤트 유실

현재 구조에서는 `rpk produce`가 실패하면 완료 이벤트가 유실된다. 대응 방안:

| 방안 | 설명 |
|------|------|
| **재시도** | `rpk produce` 실패 시 3회 재시도 + exponential backoff |
| **로컬 큐** | 실패한 페이로드를 Jenkins 파일시스템에 저장, 주기적 재전송 |
| **타임아웃 감지** | Spring에서 완료 이벤트 타임아웃 시 Jenkins API로 빌드 결과 직접 조회 |

### 7-7. 파이프라인 파라미터 의존성

`EXECUTION_ID`와 `STEP_ORDER`를 파라미터로 받아야 webhook 페이로드에 포함할 수 있다.
TPS가 Jenkins 잡을 트리거할 때 이 파라미터를 반드시 전달해야 하며,
잡이 파라미터 없이 실행되면 리스너가 null을 보내게 된다.
리스너에서 해당 파라미터가 없는 잡은 webhook을 보내지 않도록 필터링하는 것이 안전하다.

```groovy
// TPS 파이프라인만 필터링
def executionId = params?.getParameter("EXECUTION_ID")?.value
if (executionId == null) {
    // TPS 관리 잡이 아님 — webhook 스킵
    return
}
```

---

# Part 3 — 현재 구현 상태 (2026-03-20)

## 1. K8s Helm 환경 (GCP)

Part 1의 Docker 환경과 별도로 K8s Helm chart(`infra/k8s/jenkins/values.yaml`)로 Jenkins를 운영한다.

| 항목 | 값 |
|------|---|
| Chart | `jenkins/jenkins` (https://charts.jenkins.io) |
| Namespace | `rp-jenkins` |
| Image | `jenkins/jenkins:lts-jdk17` |
| NodePort | `31080` |
| URL | `http://34.47.74.0:31080` |
| Agent | K8s 동적 Pod (containerCap: 3) |

### 설치된 플러그인

```
configuration-as-code, cloudbees-folder, job-dsl,
workflow-job, workflow-cps, workflow-basic-steps,
workflow-durable-task-step, workflow-step-api, workflow-aggregator,
pipeline-model-definition, pipeline-stage-step, pipeline-input-step,
git, pipeline-stage-view, kubernetes
```

`cloudbees-folder`는 2026-03-20에 추가되었다. Docker Dockerfile과 K8s values.yaml 양쪽에 명시해야 한다.

---

## 2. 빌드 완료 전역 리스너 — 현재 구현

Part 2에서 검토한 방법 중 **RunListener (전역) + rpk produce**를 채택하여 K8s Helm values.yaml의 `initScripts.webhook-listener`로 구현했다. Docker 환경에서는 `webhook-listener.groovy`가 `init.groovy.d/`에 마운트된다.

### 동작 방식

```
[모든 Jenkins Job 빌드 완료]
  ↓
RunListener.onCompleted(run, listener)
  ↓
EXECUTION_ID 파라미터 확인 → 없으면 스킵
  ↓
run.parent.fullName으로 jobName 추출
  (폴더 내 Job은 "build/playground-job-28" 형태)
  ↓
rpk produce → Kafka 토픽 (직접 발행, Connect HTTP 미경유)
  ↓
JobCompletionConsumer (executor / operator-stub)
  → PipelineEngine.resumeAfterCompletion()
```

### 필터링 조건

현재는 `EXECUTION_ID` 파라미터 유무만으로 필터링한다. Playground가 트리거한 Job만 이 파라미터를 가지므로, 수동 실행 Job이나 외부 Job은 자동으로 제외된다.

특정 폴더 경로로 필터링하는 것도 가능하다. `run.parent.fullName`이 폴더 경로를 포함하므로 `startsWith("build/")` 같은 조건을 추가할 수 있다. 다만 현재는 `EXECUTION_ID` 필터가 충분하므로 별도 경로 필터는 불필요하다.

### 현재 한계와 개선 방향

**재시도 없음 (fire-and-forget)**: `rpk produce`가 실패하면 로그만 남기고 끝난다. 개선 옵션 2가지가 있다.

| 옵션 | 방식 | 장점 | 단점 |
|------|------|------|------|
| A | Groovy 단 재시도 (3회, 지수 백오프) | 구현 간단 | 빌드 스레드 블로킹 |
| B | JobCompletionTimeoutChecker 폴링 보완 | 이미 존재하는 안전망 활용 | 타임아웃까지 지연 |

현재는 JobCompletionTimeoutChecker(옵션 B)가 최종 안전망 역할을 한다. 일정 시간 완료 이벤트가 안 오면 Jenkins API로 빌드 상태를 직접 조회한다. A + B 조합이 현 단계에서 적절하다.

### onCompleted vs onFinalized

현재 구현은 `onCompleted`를 사용한다. Part 2 §7-1에서 `onFinalized` 사용을 권장했지만, PoC 범위에서는 `onCompleted` 시점에 이미 `run.result`가 확정되어 있으므로 실무 문제는 발생하지 않았다. 프로덕션에서는 `onFinalized`로 전환하는 것이 더 안전하다.

### webhook payload

```json
{
  "executionId": "uuid",
  "stepOrder": 1,
  "result": "SUCCESS",
  "buildNumber": 1,
  "jobName": "build/playground-job-28",
  "duration": 15175,
  "url": "http://34.47.74.0:31080/job/build/job/playground-job-28/1/"
}
```

`jobName`은 `run.parent.fullName`에서 나오므로 폴더 내 Job은 `folder/jobname` 형태가 된다. 수신 쪽(`JobCompletionConsumer`)은 `EXECUTION_ID` + `STEP_ORDER`로 매칭하므로 jobName 형태가 바뀌어도 영향이 없다.

---

## 3. Jenkins Folder 구조

PipelineJobType별로 Jenkins 폴더를 생성하여 Job을 그룹화한다. 폴더명은 `PipelineJobType.toFolderName()`에서 파생된다.

```
Jenkins UI:
├── build/                  ← BUILD 타입 per-Job
│   ├── playground-job-5
│   └── playground-job-28
├── deploy/                 ← DEPLOY 타입 per-Job
│   └── playground-job-12
├── artifact-download/      ← ARTIFACT_DOWNLOAD 타입 per-Job
├── image-pull/             ← IMAGE_PULL 타입 per-Job
├── playground-build        ← 기본 빌드 Job (루트 유지, fallback)
└── playground-deploy       ← 기본 배포 Job (루트 유지, fallback)
```

### 폴더명 매핑

| PipelineJobType | toFolderName() |
|-----------------|---------------|
| `BUILD` | `build` |
| `DEPLOY` | `deploy` |
| `IMPORT` | `import` |
| `ARTIFACT_DOWNLOAD` | `artifact-download` |
| `IMAGE_PULL` | `image-pull` |

변환 규칙: `name().toLowerCase().replace("_", "-")`

### 폴더 생성 방식

lazy 생성이다. `JenkinsAdapter.ensureFolderExists(folderName)`이 첫 Job 생성 시 호출되며, 폴더가 없으면 Folder Plugin XML로 생성한다. 이미 존재하면 무시한다 (멱등).

```xml
<?xml version='1.1' encoding='UTF-8'?>
<com.cloudbees.hudson.plugins.folder.Folder plugin="cloudbees-folder">
  <description>Auto-created folder for build jobs</description>
</com.cloudbees.hudson.plugins.folder.Folder>
```

### Jenkins Folder API 경로

| 동작 | 경로 |
|------|------|
| 폴더 내 Job 생성 | `POST /job/{folder}/createItem?name={jobName}` |
| 폴더 내 Job 설정 변경 | `POST /job/{folder}/job/{jobName}/config.xml` |
| 폴더 내 Job 삭제 | `POST /job/{folder}/job/{jobName}/doDelete` |
| 폴더 내 Job 목록 | `GET /job/{folder}/api/json?tree=jobs[name]` |
| 폴더 내 Job 트리거 | `POST /job/{folder}/job/{jobName}/buildWithParameters` |

### Bloblang 호환

Kafka 커맨드 메시지의 `jobName` 필드에 `build/job/playground-job-5` 형태로 폴더 경로를 포함하면, Bloblang의 `/job/%s/buildWithParameters` 포맷이 `/job/build/job/playground-job-5/buildWithParameters`를 생성한다. Jenkins Folder Plugin API와 정확히 일치하므로 Bloblang이나 Avro 스키마 변경이 불필요하다.

### Reconciler 동작

`JenkinsReconciler`는 60초 주기로 DB(desired state)와 Jenkins(actual state)를 비교한다. 폴더 도입 후에는 타입별로 그룹화하여 `listJobNamesInFolder(folder)`로 폴더별 Job 목록을 조회한 뒤, DB에 ACTIVE인데 Jenkins 폴더에 없는 Job을 재생성한다.

### 기존 Job 마이그레이션

루트에 있는 기존 `playground-job-*`은 Reconciler가 다음 실행 시 폴더 안에 재생성한다. 루트의 기존 Job은 Jenkins UI에서 수동 삭제해야 한다. 기본 Job(`playground-build`, `playground-deploy`)은 루트에 유지된다.

### 변경 파일 요약

| # | 파일 | 변경 |
|---|------|------|
| 1 | `PipelineJobType.java` | `toFolderName()` 메서드 추가 |
| 2 | `JenkinsAdapter.java` | `ensureFolderExists()` + 폴더 지원 CRUD + `listJobNamesInFolder()` |
| 3 | `JobService.java` | outbox payload에 `jobType` 필드 추가 (4곳) |
| 4 | `JenkinsOutboxHandler.java` | payload에서 jobType → 폴더 경로 사용 (3 메서드) |
| 5 | `JenkinsCloneAndBuildStep.java` | jenkinsJobName에 폴더 경로 prefix |
| 6 | `JenkinsDeployStep.java` | jenkinsJobName에 폴더 경로 prefix |
| 7 | `JenkinsReconciler.java` | 폴더별 순회로 변경 |
| 8 | `Dockerfile` | `cloudbees-folder` 플러그인 추가 |
| 9 | `values.yaml` | `cloudbees-folder:latest` 추가 |

### 검증 결과 (2026-03-20, GCP 환경)

| 테스트 | 결과 |
|--------|------|
| `./gradlew :pipeline:test` (빌드 + 단위 테스트) | PASS |
| Folder Plugin 설치 확인 | `cloudbees-folder 6.1079` |
| Job 생성 API → 폴더 내 생성 | `build/playground-job-28` 확인 |
| 폴더 내 Job 트리거 (buildWithParameters) | HTTP 201, Build SUCCESS |
| Webhook 전송 | `[WEBHOOK] Sent to Connect (HTTP 200): SUCCESS` |
| Bloblang 경로 호환 | `/job/build/job/playground-job-28/buildWithParameters` 일치 |
