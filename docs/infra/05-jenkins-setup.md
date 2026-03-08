# Jenkins 설정 가이드

이 문서는 Redpanda Playground 프로젝트의 Jenkins CI/CD 환경 구성 과정과 해결한 이슈를 정리한다.

---

## 1. Jenkins 컨테이너 구성

### Dockerfile

```dockerfile
FROM jenkins/jenkins:lts-jdk17

ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8
ENV JAVA_OPTS="-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Djenkins.install.runSetupWizard=false"

RUN jenkins-plugin-cli --plugins \
    configuration-as-code \
    workflow-job workflow-cps workflow-basic-steps \
    workflow-durable-task-step workflow-step-api workflow-aggregator \
    pipeline-model-definition pipeline-stage-step pipeline-input-step
```

핵심 설정:
- **UTF-8 로케일**: `LANG=C.UTF-8` + `LC_ALL=C.UTF-8` — 한글 깨짐 방지
- **JVM 인코딩**: `JAVA_OPTS`에 `-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8`
- **셋업 위자드 스킵**: `-Djenkins.install.runSetupWizard=false` — CasC로 자동 설정하므로 위자드 불필요
- **CasC 플러그인**: `configuration-as-code` — `casc.yaml`로 Jenkins 설정을 코드로 관리

### docker-compose.infra.yml

```yaml
jenkins:
  build:
    context: ./jenkins
    dockerfile: Dockerfile
  environment:
    JAVA_OPTS: -Xmx1g -Xms512m -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8
    CASC_JENKINS_CONFIG: /var/jenkins_home/casc.yaml
  volumes:
    - jenkins-data:/var/jenkins_home
    - ./jenkins/casc.yaml:/var/jenkins_home/casc.yaml:ro
    - ./jenkins/disable-csrf.groovy:/var/jenkins_home/init.groovy.d/disable-csrf.groovy:ro
```

**주의**: docker-compose의 `JAVA_OPTS`가 Dockerfile의 `ENV JAVA_OPTS`를 **덮어쓴다.** 따라서 UTF-8 설정은 양쪽 모두에 명시해야 한다.

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

각 Job은 `post.always` 블록에서 `playground-connect:4197/webhook/jenkins`로 결과를 콜백한다. 이것이 Break-and-Resume 패턴의 핵심이다.

```bash
# Job 등록 명령
make setup-jenkins
# 또는
JENKINS_PASS=admin bash docker/scripts/setup-jenkins.sh
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
docker compose -f docker-compose.infra.yml stop jenkins
docker compose -f docker-compose.infra.yml rm -f jenkins
docker volume rm docker_jenkins-data
docker compose -f docker-compose.infra.yml up -d --build jenkins
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
| API Token | `docker/.env`의 `JENKINS_TOKEN` |

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
docker compose -f docker-compose.infra.yml stop jenkins
docker compose -f docker-compose.infra.yml rm -f jenkins
docker volume rm docker_jenkins-data
docker compose -f docker-compose.infra.yml up -d --build jenkins
```
