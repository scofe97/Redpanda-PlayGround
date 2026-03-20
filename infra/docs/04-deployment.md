# GCP 분산 배포 가이드

로컬 단일 머신에서 실행하던 Redpanda Playground를 GCP 3대 VM에 분산 배포한다.
로컬에서는 Spring Boot + React 개발만 하고, 인프라와 모니터링은 GCP에서 운영한다.

## 아키텍처

```
┌─────────────────────────────────────────────────────────────┐
│                       GCP VPC (10.178.0.0/20)               │
│                                                             │
│  ┌─────────────────┐  ┌──────────────┐  ┌────────────────┐  │
│  │ Server 1        │  │ Server 2     │  │ Server 3       │  │
│  │ 10.178.0.2      │  │ 10.178.0.3   │  │ 10.178.0.4     │  │
│  │                 │  │              │  │                │  │
│  │ Redpanda        │  │ Jenkins      │  │ Loki           │  │
│  │ Console         │  │ GitLab       │  │ Tempo          │  │
│  │ Connect ────────┼──→ (API호출)    │  │                │  │
│  │ PostgreSQL      │  │ Alloy ←──────┼──→ Alloy(central)│  │
│  │ Alloy ←─────────┼──┼─────────────┼──→ Prometheus     │  │
│  │   sidecar       │  │   sidecar    │  │ Grafana        │  │
│  └────────▲────────┘  └──────▲───────┘  └───────▲────────┘  │
│           │                  │                  │           │
└───────────┼──────────────────┼──────────────────┼───────────┘
            │ 34.47.83.38      │ 34.47.74.0       │ 34.22.78.240
            │                  │                  │
┌───────────┼──────────────────┼──────────────────┼───────────┐
│  Local    │                  │                  │           │
│  macOS    │                  │                  │           │
│           │                  │                  │           │
│  Spring Boot ──(Kafka/DB)────┘                  │           │
│  React       ──(OTel)──→ Alloy sidecar ────────┘           │
│                           (localhost)                       │
└─────────────────────────────────────────────────────────────┘
```

## 서버 배치

| VM | 내부 IP | 외부 IP | 서비스 | 역할 |
|----|---------|---------|--------|------|
| dev-server (1) | 10.178.0.2 | 34.47.83.38 | Redpanda, Console, Connect, PostgreSQL, Alloy | 데이터 플레인 |
| dev-server-2 (2) | 10.178.0.3 | 34.47.74.0 | Jenkins, GitLab, Alloy | CI/CD |
| dev-server-3 (3) | 10.178.0.4 | 34.22.78.240 | Loki, Tempo, Prometheus, Grafana, Alloy | 모니터링 |
| 로컬 macOS | - | - | Spring Boot, React, Alloy | 개발 |

## 네트워크 통신 규칙

서버 간 통신은 내부IP, 로컬에서 GCP 접근은 외부IP, 같은 Docker Compose 내부는 서비스명을 사용한다. 같은 VPC의 내부IP 통신은 빠르고 이그레스 요금이 발생하지 않는다.

| 경로 | IP 종류 | 예시 |
|------|---------|------|
| Server 1 → Server 3 | 내부 IP | `10.178.0.4:3100` |
| Server 1 Connect → Server 2 Jenkins | 내부 IP | `10.178.0.3:29080` |
| 로컬 → Server 1 | 외부 IP | `34.47.83.38:29092` |
| 로컬 → Server 2 | 외부 IP | `34.47.74.0:29080` |
| Alloy → Loki (같은 compose) | 서비스명 | `loki:3100` |

## 텔레메트리 흐름

각 서버의 Alloy sidecar가 로컬 Docker 로그와 메트릭을 수집하여 Server 3으로 전송한다.
노이즈 필터는 Server 3 central Alloy에만 배치하여 필터 규칙을 한 곳에서 관리한다.

| Alloy | 로그 수집 | OTLP | 메트릭 | 전송 대상 |
|-------|----------|------|--------|----------|
| Server 1 (sidecar) | Redpanda, Connect, PostgreSQL | Connect 트레이스 릴레이 | Redpanda, Connect x2 | Server 3 내부IP |
| Server 2 (sidecar) | Jenkins만 (GitLab drop) | 없음 | 없음 | Server 3 내부IP |
| Server 3 (central) | 모니터링 자체 컨테이너 | OTLP 수신 → 노이즈 필터 → Tempo | 없음 | 로컬 Loki/Tempo |
| Local (sidecar) | 없음 | Spring Boot 트레이스 릴레이 | Spring Boot actuator | Server 3 외부IP |

## 파일 구조

```
infra/docker/deploy/
├── server-1/                       # Redpanda + DB + Connect
│   ├── docker-compose.yml
│   ├── .env
│   ├── console-config.yml
│   ├── connect/                    # 4개 파이프라인 (Jenkins URL → Server 2 내부IP)
│   ├── init-db/01-init.sql
│   └── monitoring/alloy-config.alloy
├── server-2/                       # Jenkins + GitLab + Alloy sidecar
│   ├── docker-compose.yml          # Jenkins(빌드) + GitLab + Alloy 풀 구성
│   ├── .env
│   ├── jenkins/                    # Jenkins 빌드 컨텍스트
│   │   ├── Dockerfile
│   │   ├── casc.yaml
│   │   ├── disable-csrf.groovy
│   │   ├── webhook-listener.groovy
│   │   ├── Jenkinsfile
│   │   └── Jenkinsfile-deploy
│   └── monitoring/alloy-config.alloy
├── server-3/                       # 모니터링 풀 스택
│   ├── docker-compose.yml
│   ├── .env
│   └── monitoring/                 # Alloy, Loki, Tempo, Prometheus, Grafana
├── local/                          # macOS Alloy sidecar
│   ├── docker-compose.yml
│   └── monitoring/alloy-config.alloy
└── scripts/
    ├── deploy.sh                   # 전체/개별 배포
    └── verify.sh                   # 헬스체크
```

## 핵심 변경 사항

### Redpanda advertise 주소

로컬 Spring Boot가 GCP Redpanda에 연결하려면 advertise 주소가 외부IP여야 한다.
Kafka 클라이언트는 bootstrap 연결 후 브로커 메타데이터의 advertise 주소로 재연결하기 때문이다.

```yaml
# 기존 (로컬): external://localhost:29092
# 변경 (GCP): external://34.47.83.38:29092
--advertise-kafka-addr internal://redpanda:9092,external://34.47.83.38:29092
```

### Connect Jenkins URL

Jenkins가 Server 2에 있으므로 Connect의 HTTP 호출 대상을 Server 2 내부IP로 설정한다.
Connect(Server 1)와 Jenkins(Server 2)는 같은 VPC에 있어 내부IP로 통신한다.

```yaml
# 기존: http://playground-jenkins:8080 (같은 Docker 네트워크 — 잘못된 설정)
# 변경: http://10.178.0.3:29080 (Server 2 내부IP + 호스트포트)
url: 'http://10.178.0.3:29080${! meta("jenkins_path") }'
```

### Server 2 독립 Compose

1차 배포에서는 Jenkins/GitLab이 Server 1에 잘못 배치되었다.
Server 2의 docker-compose.yml이 Jenkins + GitLab + Alloy를 모두 포함하도록 재작성하여
단일 `docker compose up -d`로 배포할 수 있게 했다.

## 배포 절차

### 사전 준비

```bash
# 1. 방화벽 규칙 생성 (1회)
cd infra/docker/deploy/scripts
./deploy.sh firewall

# 2. Server 1에서 잘못 배치된 Jenkins/GitLab 제거
gcloud compute ssh dev-server --zone=asia-northeast3-a --command \
  "sg docker -c 'docker rm -f playground-jenkins playground-gitlab && docker volume prune -f'"
```

### 배포 (순서 중요)

```bash
# 전체 배포 (3 → 1 → 2 → local 순서)
./deploy.sh all

# 또는 개별 배포
./deploy.sh server-3    # 모니터링 먼저 — Alloy가 연결할 대상
./deploy.sh server-1    # 데이터 플레인
./deploy.sh server-2    # CI/CD (Jenkins 빌드 포함, 초기 실행 시 수 분 소요)
./deploy.sh local       # 로컬 Alloy
```

### Server 2 배포 상세

Server 2는 Jenkins 커스텀 이미지 빌드가 필요하므로 전송할 파일이 많다.

```bash
# 파일 전송
gcloud compute scp --recurse infra/docker/deploy/server-2/ dev-server-2:~/deploy/server-2/ \
  --zone=asia-northeast3-a

# 배포 (Jenkins 이미지 빌드 포함)
gcloud compute ssh dev-server-2 --zone=asia-northeast3-a --command \
  "sg docker -c 'cd ~/deploy/server-2 && docker compose up -d --build'"
```

### Spring Boot 시작

```bash
SPRING_PROFILES_ACTIVE=gcp make backend
```

`application-gcp.yml`이 datasource/kafka/connect URL을 Server 1 외부IP로 오버라이드한다.
OTel 엔드포인트는 `localhost:24318` 유지 (로컬 Alloy가 중계).

## 검증

```bash
# 자동 헬스체크
./verify.sh

# 수동 확인
# Server 1 (데이터)
curl -sf http://34.47.83.38:29644/v1/cluster/health       # Redpanda
curl -sf http://34.47.83.38:28081/subjects                 # Schema Registry
curl -sf http://34.47.83.38:28080                          # Console

# Server 2 (CI/CD)
curl -sf http://34.47.74.0:29080/login                     # Jenkins
curl -sf http://34.47.74.0:29180/users/sign_in             # GitLab

# Server 3 (모니터링)
curl -sf http://34.22.78.240:23000/api/health              # Grafana
curl -sf http://34.22.78.240:3100/ready                    # Loki

# DB 연결
PGPASSWORD=playground psql -h 34.47.83.38 -p 25432 -U playground -c "SELECT 1"

# Jenkins/GitLab이 Server 1에 없는지 확인
gcloud compute ssh dev-server --command "sg docker -c 'docker ps'"
# → playground-jenkins, playground-gitlab이 없어야 함
```

### E2E 검증

1. Grafana 접속: `http://34.22.78.240:23000`
2. Explore > Loki: `{container=~"playground-.*"}` → 3개 서버 로그 확인
3. Spring Boot에서 API 호출 → Tempo에서 트레이스 확인 → Loki 로그 연결

## 기존 설정과의 관계

`infra/docker/deploy/`는 멀티 서버 전용 설정이다. 기존 `infra/docker/` 디렉토리는 로컬 올인원 개발용으로 그대로 유지한다.

| 기존 파일 | deploy 대응 | 주요 변경 |
|----------|------------|----------|
| `docker-compose.yml` | `server-1/docker-compose.yml` | advertise-kafka-addr + DB 통합 |
| `docker-compose.db.yml` | (server-1에 통합) | - |
| `docker-compose.jenkins.yml` | `server-2/docker-compose.yml` | Server 2 독립 배포 |
| `docker-compose.gitlab.yml` | `server-2/docker-compose.yml` | Server 2 독립 배포 |
| `docker-compose.monitoring.yml` | `server-3/docker-compose.yml` | 포트 호스트 노출 |
| `monitoring/alloy-config.alloy` | 4개 variant | 엔드포인트 IP 변경 |
| `connect/*.yaml` | `server-1/connect/` | Jenkins URL → Server 2 내부IP |

## GCP 방화벽 규칙

```bash
# Server 1: 로컬에서 Redpanda/DB/Console 접근
gcloud compute firewall-rules create playground-server1 \
  --allow tcp:29092,tcp:28081,tcp:28080,tcp:25432,tcp:29644 \
  --target-tags http-server

# Server 3: 로컬에서 Grafana/OTLP/Prometheus/Loki 접근
gcloud compute firewall-rules create playground-server3 \
  --allow tcp:23000,tcp:4317,tcp:4318,tcp:9090,tcp:3100 \
  --target-tags http-server

# Server 2: dev-server-middleware 규칙이 29080,29180,29122를 이미 열고 있음
# default-allow-internal로 VPC 내부 통신도 이미 허용됨
```

## 트러블슈팅

### Spring Boot가 Redpanda에 연결 실패

원인: advertise 주소가 `localhost`로 되어 있으면, 브로커 메타데이터 응답에 `localhost:29092`가 포함되어 로컬 머신이 자기 자신에게 연결을 시도한다.

확인: `rpk cluster metadata -X brokers=34.47.83.38:29092`로 advertise 주소가 외부IP인지 확인.

### Alloy가 Server 3에 연결 실패

원인: 방화벽 규칙이 없거나 Server 3이 아직 시작되지 않았다.

확인: `curl http://10.178.0.4:3100/ready` (서버 간) 또는 `curl http://34.22.78.240:3100/ready` (로컬).

### Server 2 Jenkins 빌드 실패

원인: Docker 이미지 빌드 시 jenkins-plugin-cli가 플러그인 다운로드에 실패할 수 있다 (네트워크 또는 메모리 부족).

확인: `docker compose logs jenkins`로 빌드 로그 확인. 메모리 부족이면 GitLab을 먼저 중지하고 Jenkins 빌드 후 재시작.

### Server 2 Alloy 네트워크 문제

Server 2의 모든 서비스가 같은 `playground-net`에 있으므로 서비스명 접근이 가능하다. Alloy는 Docker socket으로 로그를 수집하므로 네트워크와 무관하게 동작한다.

---

## 재배포 작업 이력 (2026-03-15)

### 배경

1차 배포에서 Jenkins/GitLab을 Server 1에 몰아넣는 실수를 했다. 이를 수정하여 계획대로 3대에 분산 배포했다.

### 변경 전 상태

- Server 1 (34.47.83.38): Redpanda, Console, Connect, PostgreSQL, Alloy, **Jenkins(Exited), GitLab** — 잘못됨
- Server 2 (34.47.74.0): Alloy sidecar만 (비어있음)
- Server 3 (34.22.78.240): Loki, Tempo, Prometheus, Grafana, Alloy (정상)

### 변경 후 상태

- Server 1: Redpanda, Console, Connect, PostgreSQL, Alloy (데이터 플레인)
- Server 2: Jenkins, GitLab, Alloy (CI/CD)
- Server 3: Loki, Tempo, Prometheus, Grafana, Alloy (모니터링)

### 실행 단계

#### Step 1: Server 1에서 Jenkins/GitLab 제거

```bash
gcloud compute ssh dev-server --zone=asia-northeast3-a --command \
  "sg docker -c 'docker rm -f playground-jenkins playground-gitlab'"
```

#### Step 2: Server 2에 Jenkins + GitLab + Alloy 배포

- `infra/docker/deploy/server-2/docker-compose.yml` 재작성 (Alloy-only → Jenkins+GitLab+Alloy 풀 구성)
- `infra/docker/deploy/server-2/.env` 신규 생성
- `infra/docker/deploy/server-2/jenkins/` 빌드 컨텍스트 복사 (Dockerfile, casc.yaml, groovy, Jenkinsfile)
- Jenkins casc.yaml URL을 Server 2 외부IP(34.47.74.0:29080)로 변경
- 파일 권한 수정 필요 (Google Drive에서 복사 시 600 → 644)
- Jenkins 이미지 --no-cache 빌드 필요

#### Step 3: Server 1 Connect Jenkins URL 수정

- `jenkins-command.yaml`: `http://playground-jenkins:8080` → `http://10.178.0.3:29080`
- Server 1의 docker-compose.yml network에서 `external: true` 제거

#### Step 4: DB support_tool URL 업데이트

Jenkins는 외부IP(브라우저 접근용), GitLab은 Docker 서비스명(Jenkins에서 git clone 시 같은 네트워크 접근용)으로 설정한다.

```sql
UPDATE support_tool SET url='http://34.47.74.0:29080' WHERE tool_type='JENKINS';
UPDATE support_tool SET url='http://playground-gitlab:29180' WHERE tool_type='GITLAB';
```

#### Step 5: GitLab 셋업

Server 2의 GitLab은 새로 배포한 것이므로 프로젝트와 PAT을 생성해야 한다.

```bash
# PAT 생성 (gitlab-rails)
docker exec playground-gitlab gitlab-rails runner "
user = User.find_by(username: 'root')
token = user.personal_access_tokens.create!(
  name: 'jenkins-token',
  scopes: [:api, :read_repository, :write_repository],
  expires_at: 365.days.from_now
)
token.set_token('glpat-playground-jenkins-2026')
token.save!
"

# 그룹 + 프로젝트 생성 (API)
curl -X POST "$GITLAB/api/v4/groups" -H "PRIVATE-TOKEN: $TOKEN" \
  -d '{"name":"egov-sample","path":"egov-sample","visibility":"public"}'
curl -X POST "$GITLAB/api/v4/projects" -H "PRIVATE-TOKEN: $TOKEN" \
  -d '{"name":"portal-app","namespace_id":2,"visibility":"public","initialize_with_readme":true}'
```

Server 2의 `.env`에 `GITLAB_TOKEN=glpat-playground-jenkins-2026` 설정 후 Jenkins 재생성(`docker compose up -d jenkins`).

#### Step 6: Flyway baseline 수정

- GCP DB의 flyway_schema_history baseline을 V1 → V11로 변경 (이미 적용된 마이그레이션 스킵)

#### Step 7: Spring Boot gcp 프로필로 재시작

```bash
SPRING_PROFILES_ACTIVE=gcp make backend
```

### 수정된 파일

| 파일 | 변경 내용 |
|------|----------|
| `infra/docker/deploy/server-2/docker-compose.yml` | Jenkins+GitLab+Alloy 풀 구성으로 재작성 |
| `infra/docker/deploy/server-2/.env` | 신규 — Jenkins/GitLab 환경변수 |
| `infra/docker/deploy/server-2/jenkins/*` | 신규 — 빌드 컨텍스트 6파일 복사 + casc URL 수정 |
| `infra/docker/deploy/server-1/connect/jenkins-command.yaml` | 메시지에서 jenkinsUrl/username/credential 읽도록 변경, Base64 Authorization 헤더 생성 |
| `infra/docker/deploy/server-1/docker-compose.yml` | network external: true 제거 |
| `common-kafka/.../JenkinsBuildCommand.avsc` | username, credential 필드 추가 (Avro union) |
| `app/.../PipelineCommandProducer.java` | support_tool의 username/credential을 메시지에 포함 |
| `app/.../JenkinsCloneAndBuildStep.java` | 외부IP → Docker 서비스명 URL 변환 |
| `docs/guide/distributed-deployment-guide.md` | Server 2 섹션 + 파일 구조 + 트러블슈팅 업데이트 |

### E2E 검증 결과

#### 서비스 헬스체크

| 서비스 | URL | 결과 |
|--------|-----|------|
| Schema Registry | http://34.47.83.38:28081/subjects | 200 OK |
| Console | http://34.47.83.38:28080 | 200 OK |
| Jenkins | http://34.47.74.0:29080/login | 200 OK (healthy) |
| GitLab | http://34.47.74.0:29180/users/sign_in | 200 OK (healthy) |
| Grafana | http://34.22.78.240:23000/api/health | 200 OK (v11.5.2) |
| Loki | http://34.22.78.240:3100/ready | ready |

#### 로그 수집 (Loki)

11개 컨테이너 로그가 3서버에서 모두 수집됨:

- Server 1: redpanda(41), connect(11), postgres(2), alloy, console
- Server 2: jenkins(534), alloy (gitlab은 의도대로 drop)
- Server 3: grafana, loki, prometheus, tempo, alloy

#### 파이프라인 E2E (최종: Build #6, Pipeline SUCCESS)

전체 흐름 + 인증 전달 정상 동작 확인:

```
Spring Boot(로컬)
  → support_tool에서 Jenkins URL/username/credential 조회
  → Avro 메시지에 인증 정보 포함하여 Outbox INSERT
  → OutboxPoller → Redpanda 토픽 발행
  → Connect(Server 1) → 메시지에서 jenkinsUrl + 인증 정보 추출 → Base64 Authorization 헤더 생성
  → Jenkins API 호출 (http://34.47.74.0:29080)
  → Jenkins(Server 2) → Git Clone (playground-gitlab:29180 + PAT 인증) → Build SUCCESS
  → Jenkins webhook → Connect(Server 1) → Redpanda → Spring Boot → Pipeline 상태 업데이트
```

Jenkins Build #6 결과:
- Pipeline status: **SUCCESS** (3단계 전체 완료)
- Step 1 (Clone): SUCCESS — `http://oauth2:glpat-*@playground-gitlab:29180/egov-sample/portal-app.git`
- Step 2 (Build): SUCCESS
- Step 3 (Deploy): SUCCESS

인증 흐름:
- DB(`support_tool`): Jenkins `username=admin`, `credential=admin` / GitLab `credential=glpat-*`
- Spring Boot: Avro 메시지에 username/credential 포함 (union type)
- Connect: Avro union JSON `{"string":"admin"}` 파싱 → `Basic YWRtaW46YWRtaW4=` 헤더 생성
- Jenkins: 인증 성공, Jenkinsfile에서 GITLAB_TOKEN으로 git clone 인증

URL 흐름:
- support_tool GitLab URL: `http://34.47.74.0:29180` (프론트엔드 표시용 외부IP)
- JenkinsCloneAndBuildStep: 외부IP → `playground-gitlab:29180` 서비스명 변환
- Jenkins 컨테이너: 같은 Docker 네트워크에서 GitLab 직접 접근

### 재배포 트러블슈팅

#### 1. Jenkins groovy 파일 권한 거부

- 증상: `cp: cannot open '/usr/share/jenkins/ref/init.groovy.d/webhook-listener.groovy' for reading: Permission denied`
- 원인: Google Drive에서 scp 시 파일 권한 600으로 복사됨
- 해결: `chmod 644` + Jenkins 이미지 --no-cache 리빌드

#### 2. Alloy가 삭제된 컨테이너를 계속 추적

- 증상: Server 1 Alloy가 삭제된 Jenkins/GitLab 컨테이너 ID를 5초마다 inspect 시도
- 원인: Docker socket discovery가 삭제된 컨테이너 포지션을 캐시
- 해결: `docker compose restart alloy`

#### 3. Vite 프록시 HTML 반환

- 증상: 프론트엔드 "Failed to load tickets — Unexpected token '<'"
- 원인: 금요일부터 실행된 Vite 프로세스의 프록시 연결이 끊김
- 해결: Vite 프로세스 kill + 재시작

#### 4. Flyway checksum 불일치

- 증상: `Migration checksum mismatch for migration version 2~6`
- 원인: GCP DB의 flyway_schema_history baseline이 V1이라 V2부터 재실행 시도 + 수동 INSERT 시 checksum=0
- 해결: 잘못된 레코드 삭제 + baseline을 V11로 변경

#### 5. Connect가 여전히 playground-jenkins:8080 호출

- 증상: `dial tcp: lookup playground-jenkins on 127.0.0.11:53: server misbehaving`
- 원인: scp --recurse로 connect/ 디렉토리 전송 시 중첩 문제로 파일이 갱신 안 됨
- 해결: 개별 파일 직접 scp 후 Connect 재시작

#### 6. Jenkins git clone 시 외부IP 접근 불가

- 증상: `fatal: could not read Username for 'http://34.47.74.0:29180': No such device or address`
- 원인: Docker 컨테이너 안에서 호스트의 외부IP(34.47.74.0)에 접근 불가 + 인증 정보 없음
- 해결: support_tool의 GitLab URL을 Docker 서비스명(`http://playground-gitlab:29180`)으로 변경, GITLAB_TOKEN 환경변수 설정

#### 7. GitLab 프로젝트 미존재

- 증상: `remote: The project you were looking for could not be found`
- 원인: Server 2의 GitLab은 새로 배포한 것이라 이전 Server 1의 데이터가 없음
- 해결: GitLab API로 egov-sample 그룹 + portal-app 프로젝트 생성 (initialize_with_readme)

#### 8. Connect basic_auth에서 Bloblang interpolation 미지원

- 증상: `basic_auth.username`/`password`에 `${! meta("...") }` 사용 시 문자열 그대로 전달되어 401
- 원인: Redpanda Connect의 `basic_auth` 필드는 정적 문자열만 지원, interpolation 미지원
- 해결: Bloblang mapping에서 `username:password`를 Base64 인코딩 후 `Authorization: Basic ...` 헤더로 직접 전달

#### 9. Avro union type의 JSON 직렬화 형태

- 증상: `cannot add types object and string` — Bloblang에서 username + ":" 문자열 연결 실패
- 원인: Avro union `["null","string"]`이 JSON으로 직렬화되면 `{"string":"admin"}` 형태 (wrapped object)
- 해결: Bloblang에서 `this.username.type() == "object"` 분기 후 `.string`으로 내부 값 추출

#### 10. 로컬 Spring Boot에서 GCP 내부IP 접근 불가

- 증상: `Jenkins not available: HTTP connect timed out` — JenkinsAdapter가 `10.178.0.3:29080`으로 health check
- 원인: support_tool에 Jenkins URL을 내부IP로 설정했으나 로컬 macOS에서 GCP VPC 내부IP에 접근 불가
- 해결: Jenkins URL을 외부IP(`34.47.74.0:29080`)로 변경. Connect(Server 1)에서도 외부IP로 접근 가능

### 추가 작업: GitLab 프로젝트 등록

샘플 프로젝트 2개를 GitLab(`http://34.47.74.0:29180`)에 독립 프로젝트로 등록:

| 프로젝트 | GitLab 경로 | 소스 |
|----------|------------|------|
| egov-sample | `root/egov-sample` | `infra/docker/sample-apps/egov-sample/` |
| portal-app | `root/portal-app` | `infra/docker/sample-apps/portal-app/` |

GitLab 인증: PAT `glpat-playground-jenkins-2026` (root, api/read_repository/write_repository)

### 최종 DB 상태

```
support_tool:
  GITLAB   | http://34.47.74.0:29180  | root  | glpat-playground-jenkins-2026
  JENKINS  | http://34.47.74.0:29080  | admin | admin
  NEXUS    | http://34.47.83.38:28881 | admin | admin123
  REGISTRY | http://34.47.83.38:25050 |       |
```
