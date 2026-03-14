# GCP 멀티 서버 분산 재배포 작업 보고서

## 배경

1차 배포에서 Jenkins/GitLab을 Server 1에 몰아넣는 실수를 했다. 이를 수정하여 계획대로 3대에 분산 배포했다.

## 작업 일시

2026-03-15

## 변경 전 상태

- Server 1 (34.47.83.38): Redpanda, Console, Connect, PostgreSQL, Alloy, **Jenkins(Exited), GitLab** ← 잘못됨
- Server 2 (34.47.74.0): Alloy sidecar만 (비어있음)
- Server 3 (34.22.78.240): Loki, Tempo, Prometheus, Grafana, Alloy (정상)

## 변경 후 상태

- Server 1: Redpanda, Console, Connect, PostgreSQL, Alloy (데이터 플레인)
- Server 2: Jenkins, GitLab, Alloy (CI/CD)
- Server 3: Loki, Tempo, Prometheus, Grafana, Alloy (모니터링)

## 실행 단계

### Step 1: Server 1에서 Jenkins/GitLab 제거

```bash
gcloud compute ssh dev-server --zone=asia-northeast3-a --command \
  "sg docker -c 'docker rm -f playground-jenkins playground-gitlab'"
```

### Step 2: Server 2에 Jenkins + GitLab + Alloy 배포

- `docker/deploy/server-2/docker-compose.yml` 재작성 (Alloy-only → Jenkins+GitLab+Alloy 풀 구성)
- `docker/deploy/server-2/.env` 신규 생성
- `docker/deploy/server-2/jenkins/` 빌드 컨텍스트 복사 (Dockerfile, casc.yaml, groovy, Jenkinsfile)
- Jenkins casc.yaml URL을 Server 2 외부IP(34.47.74.0:29080)로 변경
- 파일 권한 수정 필요 (Google Drive에서 복사 시 600 → 644)
- Jenkins 이미지 --no-cache 빌드 필요

### Step 3: Server 1 Connect Jenkins URL 수정

- `jenkins-command.yaml`: `http://playground-jenkins:8080` → `http://10.178.0.3:29080`
- Server 1의 docker-compose.yml network에서 `external: true` 제거

### Step 4: DB support_tool URL 업데이트

Jenkins는 외부IP(브라우저 접근용), GitLab은 Docker 서비스명(Jenkins에서 git clone 시 같은 네트워크 접근용)으로 설정한다.

```sql
UPDATE support_tool SET url='http://34.47.74.0:29080' WHERE tool_type='JENKINS';
UPDATE support_tool SET url='http://playground-gitlab:29180' WHERE tool_type='GITLAB';
```

### Step 5: GitLab 셋업

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

### Step 6: Flyway baseline 수정

- GCP DB의 flyway_schema_history baseline을 V1 → V11로 변경 (이미 적용된 마이그레이션 스킵)

### Step 6: Spring Boot gcp 프로필로 재시작

```bash
SPRING_PROFILES_ACTIVE=gcp make backend
```

## 수정된 파일

| 파일 | 변경 내용 |
|------|----------|
| `docker/deploy/server-2/docker-compose.yml` | Jenkins+GitLab+Alloy 풀 구성으로 재작성 |
| `docker/deploy/server-2/.env` | 신규 — Jenkins/GitLab 환경변수 |
| `docker/deploy/server-2/jenkins/*` | 신규 — 빌드 컨텍스트 6파일 복사 + casc URL 수정 |
| `docker/deploy/server-1/connect/jenkins-command.yaml` | 메시지에서 jenkinsUrl/username/credential 읽도록 변경, Base64 Authorization 헤더 생성 |
| `docker/deploy/server-1/docker-compose.yml` | network external: true 제거 |
| `common-kafka/.../JenkinsBuildCommand.avsc` | username, credential 필드 추가 (Avro union) |
| `app/.../PipelineCommandProducer.java` | support_tool의 username/credential을 메시지에 포함 |
| `app/.../JenkinsCloneAndBuildStep.java` | 외부IP → Docker 서비스명 URL 변환 |
| `docs/guide/distributed-deployment-guide.md` | Server 2 섹션 + 파일 구조 + 트러블슈팅 업데이트 |

## E2E 검증 결과

### 서비스 헬스체크

| 서비스 | URL | 결과 |
|--------|-----|------|
| Schema Registry | http://34.47.83.38:28081/subjects | 200 OK |
| Console | http://34.47.83.38:28080 | 200 OK |
| Jenkins | http://34.47.74.0:29080/login | 200 OK (healthy) |
| GitLab | http://34.47.74.0:29180/users/sign_in | 200 OK (healthy) |
| Grafana | http://34.22.78.240:23000/api/health | 200 OK (v11.5.2) |
| Loki | http://34.22.78.240:3100/ready | ready |

### 로그 수집 (Loki)

11개 컨테이너 로그가 3서버에서 모두 수집됨:

- Server 1: redpanda(41), connect(11), postgres(2), alloy, console
- Server 2: jenkins(534), alloy (gitlab은 의도대로 drop)
- Server 3: grafana, loki, prometheus, tempo, alloy

### 파이프라인 E2E (최종: Build #6, Pipeline SUCCESS)

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

## 트러블슈팅 기록

### 1. Jenkins groovy 파일 권한 거부

- 증상: `cp: cannot open '/usr/share/jenkins/ref/init.groovy.d/webhook-listener.groovy' for reading: Permission denied`
- 원인: Google Drive에서 scp 시 파일 권한 600으로 복사됨
- 해결: `chmod 644` + Jenkins 이미지 --no-cache 리빌드

### 2. Alloy가 삭제된 컨테이너를 계속 추적

- 증상: Server 1 Alloy가 삭제된 Jenkins/GitLab 컨테이너 ID를 5초마다 inspect 시도
- 원인: Docker socket discovery가 삭제된 컨테이너 포지션을 캐시
- 해결: `docker compose restart alloy`

### 3. Vite 프록시 HTML 반환

- 증상: 프론트엔드 "Failed to load tickets — Unexpected token '<'"
- 원인: 금요일부터 실행된 Vite 프로세스의 프록시 연결이 끊김
- 해결: Vite 프로세스 kill + 재시작

### 4. Flyway checksum 불일치

- 증상: `Migration checksum mismatch for migration version 2~6`
- 원인: GCP DB의 flyway_schema_history baseline이 V1이라 V2부터 재실행 시도 + 수동 INSERT 시 checksum=0
- 해결: 잘못된 레코드 삭제 + baseline을 V11로 변경

### 5. Connect가 여전히 playground-jenkins:8080 호출

- 증상: `dial tcp: lookup playground-jenkins on 127.0.0.11:53: server misbehaving`
- 원인: scp --recurse로 connect/ 디렉토리 전송 시 중첩 문제로 파일이 갱신 안 됨
- 해결: 개별 파일 직접 scp 후 Connect 재시작

### 6. Jenkins git clone 시 외부IP 접근 불가

- 증상: `fatal: could not read Username for 'http://34.47.74.0:29180': No such device or address`
- 원인: Docker 컨테이너 안에서 호스트의 외부IP(34.47.74.0)에 접근 불가 + 인증 정보 없음
- 해결: support_tool의 GitLab URL을 Docker 서비스명(`http://playground-gitlab:29180`)으로 변경, GITLAB_TOKEN 환경변수 설정

### 7. GitLab 프로젝트 미존재

- 증상: `remote: The project you were looking for could not be found`
- 원인: Server 2의 GitLab은 새로 배포한 것이라 이전 Server 1의 데이터가 없음
- 해결: GitLab API로 egov-sample 그룹 + portal-app 프로젝트 생성 (initialize_with_readme)

### 8. Connect basic_auth에서 Bloblang interpolation 미지원

- 증상: `basic_auth.username`/`password`에 `${! meta("...") }` 사용 시 문자열 그대로 전달되어 401
- 원인: Redpanda Connect의 `basic_auth` 필드는 정적 문자열만 지원, interpolation 미지원
- 해결: Bloblang mapping에서 `username:password`를 Base64 인코딩 후 `Authorization: Basic ...` 헤더로 직접 전달

### 9. Avro union type의 JSON 직렬화 형태

- 증상: `cannot add types object and string` — Bloblang에서 username + ":" 문자열 연결 실패
- 원인: Avro union `["null","string"]`이 JSON으로 직렬화되면 `{"string":"admin"}` 형태 (wrapped object)
- 해결: Bloblang에서 `this.username.type() == "object"` 분기 후 `.string`으로 내부 값 추출

### 10. 로컬 Spring Boot에서 GCP 내부IP 접근 불가

- 증상: `Jenkins not available: HTTP connect timed out` — JenkinsAdapter가 `10.178.0.3:29080`으로 health check
- 원인: support_tool에 Jenkins URL을 내부IP로 설정했으나 로컬 macOS에서 GCP VPC 내부IP에 접근 불가
- 해결: Jenkins URL을 외부IP(`34.47.74.0:29080`)로 변경. Connect(Server 1)에서도 외부IP로 접근 가능

## 추가 작업: GitLab 프로젝트 등록

샘플 프로젝트 2개를 GitLab(`http://34.47.74.0:29180`)에 독립 프로젝트로 등록:

| 프로젝트 | GitLab 경로 | 소스 |
|----------|------------|------|
| egov-sample | `root/egov-sample` | `docker/sample-apps/egov-sample/` |
| portal-app | `root/portal-app` | `docker/sample-apps/portal-app/` |

GitLab 인증: PAT `glpat-playground-jenkins-2026` (root, api/read_repository/write_repository)

## 최종 DB 상태

```
support_tool:
  GITLAB   | http://34.47.74.0:29180  | root  | glpat-playground-jenkins-2026
  JENKINS  | http://34.47.74.0:29080  | admin | admin
  NEXUS    | http://34.47.83.38:28881 | admin | admin123
  REGISTRY | http://34.47.83.38:25050 |       |
```
