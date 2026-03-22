# Phase 2: K8s 전면 이관

> 로컬 Docker Compose 인프라를 GCP K8s 클러스터로 전면 이관하고, Phase 1 E2E에서 발견된 버그 4건을 수정한 단계이다.

## 목표

개발 환경을 로컬 Docker에서 실제 운영 환경과 동일한 K8s 클러스터로 전환한다. 이관 후에는 Docker Compose 잔여물을 완전히 제거하여 K8s 단일 인프라로 일원화한다. 동시에 Phase 1 DAG 파이프라인 E2E 검증에서 발견된 버그 4건을 함께 수정한다.

## 범위

- 이관 대상: PostgreSQL, Kafka(Redpanda), Schema Registry, Connect
- Jenkins는 이미 K8s(`rp-jenkins` 네임스페이스)에서 운영 중이었으므로 이관 대상 제외
- DB 데이터 이관: 15개 테이블 `pg_dump`/`pg_restore`
- Jenkins webhook URL 갱신: Docker 내부 DNS → K8s 서비스 DNS
- 버그 수정 4건: `JenkinsReconciler` 복구 누락, SSE NPE, `TicketStatusEventConsumer` NPE, URL 인코딩 누락

## 기간

2026-03-21 완료

## 구현 항목

- [x] PostgreSQL K8s 이관 (Bitnami Helm, `rp-oss` 네임스페이스)
- [x] Kafka(Redpanda) K8s 이관 (Helm, `rp-oss` 네임스페이스)
- [x] Schema Registry K8s 이관
- [x] Redpanda Connect K8s 이관
- [x] `pg_dump`/`pg_restore`로 15개 테이블 데이터 이관
- [x] Docker 컨테이너 및 볼륨 전부 삭제 (잔여물 제거)
- [x] `application-gcp.yml` NodePort 설정 갱신
- [x] Jenkins RunListener webhook URL 갱신 (K8s 서비스 DNS)
- [x] 버그 수정 4건

## 포트 매핑

| 서비스 | Docker 포트 | K8s NodePort | 네임스페이스 |
|--------|------------|-------------|-------------|
| PostgreSQL | 25432 | 30275 | rp-oss |
| Kafka (Redpanda) | 29092 | 31092 | rp-oss |
| Schema Registry | 28081 | 31081 | rp-oss |
| Connect | 4195 | 31195 | rp-oss |

Connect는 단일 포트(4195)를 그대로 유지했다. NodePort 31196(GitLab), 31197(Jenkins)를 이관 시 별도 할당했으나, Connect 단일포트 전환(Phase 3 Feature 10)에서 경로 기반으로 변경되면서 제거했다.

## Jenkins Webhook URL 갱신

Docker Compose 환경에서 Jenkins의 RunListener Groovy 스크립트는 `connect:4195`(Docker 내부 DNS)로 콜백을 보냈다. K8s 이관 후에는 `connect.rp-oss.svc.cluster.local:4195`(K8s ClusterIP 서비스 DNS)로 변경했다.

Jenkins와 Connect가 같은 K8s 클러스터 내에 있으므로 ClusterIP 통신이 가능하다. `rp-jenkins` 네임스페이스에서 `rp-oss` 네임스페이스의 서비스에 접근할 때는 풀 네임(`<svc>.<ns>.svc.cluster.local`)을 사용해야 한다는 점이 핵심이었다. RunListener Groovy 스크립트의 콜백 URL 한 줄만 바꾸는 것으로 전환이 완료되었다.

## 수정한 버그 4건

**버그 1: JenkinsReconciler FAILED 상태 복구 누락**
`JenkinsReconciler`가 Jenkins와 DB 간의 Job 상태를 주기적으로 동기화하는데, 복구 대상을 `CREATED` 상태 Job으로만 한정하고 있었다. `FAILED` 상태 Job은 재시도 대상임에도 불구하고 Reconciler가 건너뛰어 영구적으로 실패 상태에 머물렀다. 상태 체크 조건에 `FAILED`를 추가하여 재등록을 시도하도록 수정했다.

**버그 2: SSE ticketId null NPE**
Phase 1에서 `ticket_id`를 nullable로 변경(V30)한 뒤, SSE 이벤트 전송 코드에서 `ticketId`를 null 체크 없이 사용하고 있었다. DAG 파이프라인 실행(티켓 없음)이 완료되면 SSE 전송 시점에 NPE가 발생했다. 전송 전에 `ticketId != null` 조건을 추가하여 티켓 없는 실행에서는 티켓 관련 SSE를 건너뛰도록 수정했다.

**버그 3: TicketStatusEventConsumer ticketId null NPE**
동일한 nullable 변경의 영향으로 `TicketStatusEventConsumer`에서도 NPE가 발생했다. 이 Consumer는 파이프라인 완료 이벤트를 받아 티켓 상태를 업데이트하는 역할인데, DAG 파이프라인(티켓 없음)의 완료 이벤트가 들어오면 `ticketId`가 null이어서 크래시가 났다. `ticketId`가 null인 이벤트는 무시(early return)하도록 수정했다.

**버그 4: JenkinsAdapter URL 인코딩 누락**
Jenkins Job 이름에 슬래시(`/`), 공백, 한글 등 특수문자가 포함될 때 Jenkins REST API 호출 URL이 올바르게 인코딩되지 않아 404가 발생했다. `JenkinsAdapter`의 Job 이름 파라미터를 `URLEncoder.encode(jobName, StandardCharsets.UTF_8)`로 감싸는 것으로 해결했다. 슬래시는 폴더 구분자로 사용되므로 `/` 인코딩은 예외 처리했다.

## Flyway 마이그레이션

Phase 2는 인프라 이관과 버그 수정이 중심이었기 때문에 별도의 Flyway 마이그레이션이 없었다. 스키마 변경 없이 연결 설정(`application-gcp.yml`)과 코드 수정만으로 이관이 완료되었다.

## 회고

**잘한 것**

`pg_dump`/`pg_restore` 방식으로 15개 테이블의 데이터를 무손실 이관한 것이 깔끔했다. Docker 볼륨을 직접 복사하는 방식 대신 논리 덤프를 선택한 덕분에 PostgreSQL 버전 차이(로컬 Docker vs K8s Bitnami Helm 버전)에 영향을 받지 않았다.

이관 완료 즉시 Docker 컨테이너와 볼륨을 전부 삭제한 결정이 좋았다. "혹시 몰라서" 로컬에 남겨두면 나중에 어느 쪽을 기준으로 해야 하는지 혼동이 생긴다. K8s 단일 인프라로 강제 전환하여 로컬 Docker 회귀 가능성을 차단했다.

**개선할 것**

버그 2, 3(ticketId null NPE)이 Phase 1 E2E 검증에서 발견되지 않은 것은 DAG 실행 시나리오를 충분히 커버하지 않았기 때문이다. E2E 체크리스트에 "티켓 없는 DAG 실행 → SSE 수신"을 명시적으로 포함했다면 Phase 1 단계에서 발견할 수 있었다.

`/etc/hosts`에 `redpanda-0` 호스트명을 수동으로 추가해야 하는 절차가 자동화되지 않았다. Kafka advertised listener가 `redpanda-0` 호스트명을 반환하는데, 개발 머신의 `/etc/hosts`에 K8s 노드 IP를 매핑하지 않으면 연결이 실패한다. 이 절차가 README에는 문서화되어 있지만 인프라 설정 스크립트로 자동화하는 편이 더 나았다.
