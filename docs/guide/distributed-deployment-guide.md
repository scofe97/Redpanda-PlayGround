# GCP 멀티 서버 분산 배포 가이드

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
docker/deploy/
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
cd docker/deploy/scripts
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
gcloud compute scp --recurse docker/deploy/server-2/ dev-server-2:~/deploy/server-2/ \
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

`docker/deploy/`는 멀티 서버 전용 설정이다. 기존 `docker/` 디렉토리는 로컬 올인원 개발용으로 그대로 유지한다.

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
