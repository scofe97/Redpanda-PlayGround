# Connect 파이프라인

3개 YAML 파이프라인이 앱 재시작 없이 라이브 수정 가능하다.

## 파이프라인 목록

| 파이프라인 | 입력 | 출력 | 역할 |
|-----------|------|------|------|
| `jenkins-command` | `commands.jenkins` 토픽 | Jenkins HTTP API | 빌드 트리거 |
| `gitlab-webhook` | HTTP POST `:4196` | `webhook.events.inbound` 토픽 | GitLab 이벤트 수집 |
| `jenkins-webhook` | HTTP POST `:4197` | `webhook.events.inbound` 토픽 | Jenkins 콜백 수집 |

## jenkins-command

Kafka 토픽에서 빌드 명령을 소비하여 Jenkins REST API를 호출한다.

- 입력: `playground.pipeline.commands.jenkins` (Avro)
- 처리: Avro 역직렬화 → HTTP POST 변환
- 출력: Jenkins `/buildWithParameters` API
- 실패 시: DLQ로 라우팅

## gitlab-webhook

GitLab의 push/merge 이벤트를 수신하여 정규화된 웹훅 토픽으로 발행한다.

- 입력: HTTP POST `:4196` (GitLab JSON)
- 처리: JSON → Avro 직렬화 + 필드 정규화
- 출력: `playground.webhook.events.inbound` (키: `gitlab`)

## jenkins-webhook

Jenkins 빌드 완료 콜백을 수신한다. Jenkins Job의 `webhook-listener.groovy`가 빌드 결과를 POST한다.

- 입력: HTTP POST `:4197` (Jenkins JSON)
- 처리: JSON → Avro 직렬화 + 필드 정규화
- 출력: `playground.webhook.events.inbound` (키: `jenkins`)
- K8s 내부 DNS: `http://connect.rp-oss.svc.cluster.local:4197/webhook/jenkins`
