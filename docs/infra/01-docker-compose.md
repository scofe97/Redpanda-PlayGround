# Docker Compose 구성

## 배경

기존에 `docker-compose.yml`에 Redpanda + Connect + Postgres가 섞여 있었고, `docker-compose.infra.yml`에 Jenkins/GitLab/Nexus/Registry 4종이 하나로 묶여 있었다. 역할별로 독립 실행할 수 있도록 파일을 분리하고, 무거운 서비스(GitLab, Jenkins)는 GCP 서버로 옮겼다.

## Compose 파일 구조 (변경 후)

| 파일 | 서비스 | 실행 위치 | 비고 |
|------|--------|-----------|------|
| `docker-compose.yml` | redpanda, console, connect | 로컬 | 네트워크 `playground-net` 생성 주체 |
| `docker-compose.db.yml` | postgres | 로컬 | `external: true` |
| `docker-compose.jenkins.yml` | jenkins | **GCP** | `external: true`, build 필요 |
| `docker-compose.gitlab.yml` | gitlab | **GCP** | `external: true` |
| `docker-compose.nexus.yml` | nexus, registry, registry-ui | 로컬 (미사용) | 필요 시 실행 |
| `docker-compose.monitoring.yml` | loki, tempo, alloy, prometheus, grafana | 로컬 | 변경 없음 |
| `docker-compose.infra.yml` | (삭제됨) | - | 위 3개 파일로 분리 |

총 7개 Compose 파일로 구성된다. 삭제된 파일: `docker-compose.infra.yml` (3개 파일로 분리됨)

## GCP 서버 정보

| 항목 | 값 |
|------|-----|
| VM | `dev-server` (e2-custom-4-8192, 4 vCPU / 8GB RAM) |
| IP | `34.47.83.38` (ephemeral) |
| 파일 위치 | `~/playground/docker/` |
| 네트워크 | `playground-net` (bridge, 로컬과 별도) |

## 방화벽 규칙

`dev-server-middleware` 규칙을 추가했다.

```
gcloud compute firewall-rules create dev-server-middleware \
  --allow tcp:29080,tcp:29180,tcp:29122,tcp:50000 \
  --target-tags=http-server \
  --description="Jenkins(29080,50000) and GitLab(29180,29122) ports"
```

| 포트 | 서비스 | 용도 |
|------|--------|------|
| 29080 | Jenkins | 웹 UI |
| 50000 | Jenkins | Agent 통신 |
| 29180 | GitLab | HTTP |
| 29122 | GitLab | SSH (git clone/push) |

## GCP 서비스 관리

```bash
# SSH 접속
gcloud compute ssh dev-server --zone=asia-northeast3-a

# 서비스 시작
cd ~/playground/docker
docker compose -f docker-compose.gitlab.yml up -d
docker compose -f docker-compose.jenkins.yml up -d --build

# 서비스 중지
docker compose -f docker-compose.gitlab.yml down
docker compose -f docker-compose.jenkins.yml down

# 로그 확인
docker logs playground-gitlab
docker logs playground-jenkins
```

## 접속 URL

| 서비스 | URL |
|--------|-----|
| Jenkins | `http://34.47.83.38:29080` | admin / admin |
| GitLab | `http://34.47.83.38:29180` | root / playground1234! |
| GitLab SSH | `ssh://git@34.47.83.38:29122` | — |

> IP는 ephemeral이므로 VM stop/start 시 변경될 수 있다. 변경 시 `gcloud compute instances describe dev-server --zone=asia-northeast3-a --format="value(networkInterfaces[0].accessConfigs[0].natIP)"`로 확인.

## 로컬 ↔ GCP 통신

현재는 로컬과 GCP가 별도 Docker 네트워크이므로 컨테이너 이름 기반 통신이 불가능하다.

### 통신 방향별 해결

| 방향 | 방법 | 비고 |
|------|------|------|
| 로컬 앱 → GCP Jenkins/GitLab | 호스트 포트(`34.47.83.38:29080`) | 방화벽 규칙으로 허용 |
| 로컬 Connect → GCP Jenkins | `jenkins-command.yaml`에 GCP IP 직접 지정 | `http://34.47.83.38:29080` |
| **GCP Jenkins → 로컬 Connect** | **cloudflared 터널** | 아래 참조 |
| GCP Jenkins → GCP GitLab | IP 기반 (`34.47.83.38:29180`) + PAT 인증 | 같은 VM이지만 별도 compose 프로젝트라 컨테이너명 불가 |

### cloudflared 터널 (GCP → 로컬 역방향)

Jenkins 빌드 완료 후 webhook callback을 로컬 Connect(`localhost:4197`)로 보내야 하는데, GCP에서 로컬 머신에 직접 접근할 수 없다. `cloudflared`로 임시 터널을 생성하여 해결한다.

```bash
# 로컬에서 터널 시작 (Connect webhook 포트 노출)
cloudflared tunnel --url http://localhost:4197 &

# 출력에서 터널 URL 확인 (예: https://xxx-yyy.trycloudflare.com)
```

GCP Jenkins에는 두 가지 환경변수를 전달한다:

```bash
cd ~/playground/docker
export CONNECT_WEBHOOK_URL='https://xxx-yyy.trycloudflare.com/webhook/jenkins'
export GITLAB_TOKEN='glpat-...'  # GitLab PAT (git clone 인증용)
docker compose -f docker-compose.jenkins.yml up -d --build
```

| 환경변수 | 용도 | 사용 위치 |
|----------|------|-----------|
| `CONNECT_WEBHOOK_URL` | 빌드 완료 후 webhook 콜백 URL | `webhook-listener.groovy` |
| `GITLAB_TOKEN` | git clone 시 PAT 인증 주입 | `Jenkinsfile` (`env.GITLAB_TOKEN`) |

> **주의**: cloudflared 무료 터널은 세션마다 URL이 바뀐다. Jenkins를 재시작할 때 새 URL로 갱신해야 한다. 영구 터널이 필요하면 Cloudflare 계정으로 Named Tunnel을 설정하거나 Tailscale/WireGuard를 사용한다.

### Jenkins 볼륨 캐시 함정

`init.groovy.d/` 스크립트는 Jenkins 최초 시작 시 `/usr/share/jenkins/ref/`에서 `/var/jenkins_home/`으로 복사된다. 이후 Docker 이미지를 재빌드해도 볼륨에 캐시된 구버전이 사용된다. 스크립트를 업데이트하려면:

```bash
# 방법 1: docker cp로 직접 덮어쓰기
docker cp webhook-listener.groovy playground-jenkins:/var/jenkins_home/init.groovy.d/
docker restart playground-jenkins

# 방법 2: 볼륨 삭제 후 재생성 (데이터 손실 주의)
docker compose -f docker-compose.jenkins.yml down -v
docker compose -f docker-compose.jenkins.yml up -d --build
```

`Jenkinsfile`은 bind mount(`:ro`)이므로 호스트 파일을 수정하면 즉시 반영된다. 단, casc.yaml이 시작 시 Job에 주입하므로 **Jenkins 재시작이 필요**하다.

## 트러블슈팅

### 프론트엔드 403 (Invalid CORS request)

프론트엔드 포트를 변경하면 Spring Boot CORS `allowedOrigins`도 함께 변경해야 한다. vite proxy(`/api` → `localhost:8080`)를 사용하더라도 브라우저가 `Origin` 헤더를 보내고, vite가 이를 그대로 백엔드에 전달하기 때문이다.

- 파일: `app/.../common/config/WebConfig.java`
- 수정: `.allowedOrigins("http://localhost:5170", "http://localhost:5173")`
- 반영: **백엔드 재시작 필요** (`./gradlew :app:bootRun`)

### Jenkins sandbox에서 System.getenv 차단

Jenkins Pipeline sandbox 모드에서는 `System.getenv()`가 보안상 차단된다. 환경변수를 읽으려면 `env.VARIABLE_NAME`을 사용해야 한다. `webhook-listener.groovy`(init script)는 sandbox 밖이므로 `System.getenv()`가 가능하다.

### Jenkins init.groovy.d 볼륨 캐시

`/usr/share/jenkins/ref/init.groovy.d/`의 스크립트는 최초 기동 시 `/var/jenkins_home/init.groovy.d/`로 복사된다. 이후 Docker 이미지를 재빌드해도 볼륨에 캐시된 구버전이 사용된다. `docker cp`로 직접 덮어쓰거나 볼륨을 삭제해야 한다.

### GCP Jenkins에서 GitLab git clone 실패 (exit 128)

같은 GCP VM이라도 Jenkins와 GitLab이 별도 compose 프로젝트이면 Docker 네트워크가 달라 컨테이너명으로 접근할 수 없다. IP 기반 접근 + GitLab PAT 인증이 필요하다. Jenkinsfile에서 `env.GITLAB_TOKEN`으로 URL에 credential을 주입한다.

## Makefile 타겟 (변경 후)

```
make infra        # Redpanda 코어 — redpanda, console, connect (로컬)
make db           # PostgreSQL (로컬)
make nexus        # Nexus + Registry (로컬, 필요 시)
make infra-all    # 로컬 인프라 전체 (infra + db + nexus)
make infra-down   # 로컬 인프라 전체 중지
make backend      # Spring Boot 실행
make frontend     # React 프론트엔드 (localhost:5170)
make monitoring   # Grafana + Loki + Tempo + Alloy + Prometheus
```

Jenkins/GitLab은 GCP에서 직접 관리하므로 Makefile 타겟에서 제외했다.

## 작업 이력

- **2026-03-13**: docker-compose.yml에서 postgres 분리, YAML 문법 오류 수정 (L61 `service_healthyprometheus:` → `service_healthy`), docker-compose.infra.yml을 jenkins/gitlab/nexus 3개로 분리, GitLab+Jenkins를 GCP dev-server로 이전, 방화벽 규칙 추가, Makefile 타겟 재구성 + JAVA_HOME 17→21
- **2026-03-14**: GCP Jenkins ↔ 로컬 Connect 역방향 통신 해결 (cloudflared 터널), `webhook-listener.groovy`에 `CONNECT_WEBHOOK_URL` 환경변수 지원 추가, `Jenkinsfile`에 `GITLAB_TOKEN`(PAT) 기반 git clone 인증 추가 (`env.GITLAB_TOKEN` — Jenkins sandbox에서 `System.getenv` 차단되므로 `env.`으로 접근), GCP GitLab에 egov-sample/portal-app 프로젝트 생성, `jenkins-command.yaml` Jenkins URL을 GCP IP로 변경, WebConfig CORS에 `localhost:5170` 추가 (프론트엔드 포트 변경으로 인한 403 해결), E2E 테스트 성공 (GIT_CLONE → BUILD → DEPLOY 전체 SUCCESS)
