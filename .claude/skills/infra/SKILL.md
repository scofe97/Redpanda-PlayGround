---
name: playground-infra
description: GCP 3서버 토폴로지, K8s NodePort, 기동/종료 절차, 포트 동기화 체크리스트
---

# Playground 인프라 스킬

트리거: "GCP", "서버", "K8s", "인프라", "포트", "NodePort"

## 3-서버 토폴로지

| 서버 | 외부 IP | 역할 | 주요 서비스 |
|------|---------|------|------------|
| Server 1 (dev-server) | `34.47.83.38` | 데이터 플레인 | Redpanda, Console, Connect, PostgreSQL, Alloy |
| Server 2 (dev-server-2) | `34.47.74.0` | CI/CD | GitLab(Docker), Jenkins(K8s Helm), Alloy |
| Server 3 (dev-server-3) | `34.22.78.240` | 관측성 | Grafana, Loki, Tempo, Prometheus, Alloy |

의존 순서: `Server 3 → Server 1 → Server 2`

## K8s NodePort 매핑 (Server 1: 34.47.83.38)

| 서비스 | 네임스페이스 | NodePort | 비고 |
|--------|-------------|----------|------|
| PostgreSQL | rp-oss | **30275** | Bitnami Helm |
| Redpanda (Kafka) | rp-oss | **31092** | advertised: `redpanda-0:31092` |
| Schema Registry | rp-oss | **31081** | |
| Console | rp-oss | 31880 | |
| Connect (HTTP) | rp-oss | **31195** | webhook: 31197 |
| Jenkins | rp-jenkins | 31080 | admin / K8s Secret |
| Grafana | rp-mgm | **30000** | 익명 인증 |
| Alloy (OTLP HTTP) | rp-mgm | **30318** | gRPC: 30317 |
| ArgoCD | argocd | 31134 | |

> **굵은 글씨**: `application-gcp.yml`에서 사용하는 포트

## 필수 사전 설정

1. **`/etc/hosts`**: `34.47.83.38 redpanda-0`
2. **externalTrafficPolicy**: `kubectl patch svc redpanda-external -n rp-oss -p '{"spec":{"externalTrafficPolicy":"Cluster"}}'`
3. **Jenkins webhook URL**: `http://connect.rp-oss.svc.cluster.local:4197/webhook/jenkins`

## 기동 절차

```bash
# 1. VM 시작
gcloud compute instances start dev-server dev-server-2 dev-server-3 --zone=asia-northeast3-a

# 2. K8s 노드 Ready 확인 (30초~1분)
gcloud compute ssh dev-server --zone=asia-northeast3-a --command="kubectl get nodes"

# 3. Docker Compose (의존 순서: S3 → S1 → S2)
gcloud compute ssh dev-server-3 --zone=asia-northeast3-a --command="cd ~/deploy/server-3 && docker compose up -d"
gcloud compute ssh dev-server --zone=asia-northeast3-a --command="cd ~/deploy/server-1 && docker compose up -d"
gcloud compute ssh dev-server-2 --zone=asia-northeast3-a --command="cd ~/deploy/server-2 && docker compose up -d"

# 4. 로컬 앱
make backend    # GCP 프로필
make frontend   # 별도 터미널
```

## 종료

```bash
# Docker Compose (역순: S2 → S1 → S3)
gcloud compute ssh dev-server-2 --zone=asia-northeast3-a --command="cd ~/deploy/server-2 && docker compose down"
gcloud compute ssh dev-server --zone=asia-northeast3-a --command="cd ~/deploy/server-1 && docker compose down"
gcloud compute ssh dev-server-3 --zone=asia-northeast3-a --command="cd ~/deploy/server-3 && docker compose down"

# VM 종료
gcloud compute instances stop dev-server dev-server-2 dev-server-3 --zone=asia-northeast3-a
```

## 포트 변경 시 동기화 체크리스트

| 변경 위치 | 파일/대상 |
|----------|----------|
| 앱 설정 | `application-gcp.yml` |
| Jenkins Job 스크립트 | DB `pipeline_job.jenkins_script` + Jenkins config.xml |
| GCP 방화벽 | `gcloud compute firewall-rules` |
| K8s Service | `kubectl get svc -n rp-oss` |
| /etc/hosts | 로컬 머신 |
| 프로젝트 문서 | `PROJECT_SPEC.md`, `README.md` |

## 접속 URL

| 서비스 | 로컬 | GCP |
|--------|------|-----|
| Frontend | `http://localhost:5170` | - |
| API | `http://localhost:8070` | - |
| Jenkins | - | `http://34.47.74.0:31080` |
| Redpanda Console | `http://localhost:28080` | `http://34.47.83.38:31880` |
| Grafana | `http://localhost:23000` | `http://34.47.83.38:30000` |
| Nexus | - | `http://34.47.83.38:31280` |
| ArgoCD | - | `http://34.47.83.38:31134` |

## 헬스 체크

```bash
# Redpanda
gcloud compute ssh dev-server --zone=asia-northeast3-a --command="docker exec playground-redpanda rpk cluster health"

# PostgreSQL
gcloud compute ssh dev-server --zone=asia-northeast3-a --command="docker exec playground-postgres pg_isready"

# 전체 컨테이너
gcloud compute ssh dev-server --zone=asia-northeast3-a --command="docker ps --format 'table {{.Names}}\t{{.Status}}'"
```
