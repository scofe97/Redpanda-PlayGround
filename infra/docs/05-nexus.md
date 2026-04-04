# Nexus 설정 및 아티팩트 업로드

Jenkins BUILD 파이프라인이 빌드한 WAR 파일을 Nexus에 저장하고, DEPLOY 파이프라인이 다운로드하여 배포하는 구조다.

## 접속 정보

| 항목 | 값 |
|------|-----|
| URL | `http://34.47.83.38:31280` |
| K8s 네임스페이스 | `rp-oss` |
| NodePort | 31280 |
| 계정 | `admin` / `playground1234!` |
| 인증 방식 | Basic Auth |

## 리포지토리 목록

| 이름 | 타입 | 포맷 | 용도 |
|------|------|------|------|
| `maven-releases` | hosted | maven2 | WAR/JAR 아티팩트 저장 |
| `maven-snapshots` | hosted | maven2 | 스냅샷 아티팩트 |
| `maven-central` | proxy | maven2 | Maven Central 프록시 |
| `maven-public` | group | maven2 | releases + snapshots + central 통합 |

### maven-releases 저장소 설정

| 설정 | 값 | 의미 |
|------|-----|------|
| writePolicy | `ALLOW_ONCE` | 같은 GAV 좌표로 덮어쓰기 불가 |
| versionPolicy | `RELEASE` | 릴리스 버전만 허용 |
| layoutPolicy | `STRICT` | Maven 표준 레이아웃 강제 |

## 초기 셋업 (`make setup-nexus`)

`infra/k8s/nexus/setup-nexus-k8s.sh`가 실행하는 작업:

1. Nexus Ready 대기
2. admin 비밀번호 변경 (`playground1234!`)
3. Community Edition EULA 수락
4. `maven-releases` hosted 저장소 생성
5. `maven-central` proxy 저장소 생성
6. egov-sample WAR 초기 업로드

### 업로드 curl 명령

WAR 파일과 POM 파일을 각각 PUT으로 업로드한다.

```bash
# WAR 업로드
curl -sf -o /dev/null -u "admin:playground1234!" \
    --upload-file "$TMPWAR" \
    "http://34.47.83.38:31280/repository/maven-releases/com/example/egov-sample/1.0.0/egov-sample-1.0.0.war"

# POM 업로드 (메타데이터)
curl -sf -o /dev/null -u "admin:playground1234!" \
    --upload-file "$SAMPLE_DIR/egov-sample/pom.xml" \
    "http://34.47.83.38:31280/repository/maven-releases/com/example/egov-sample/1.0.0/egov-sample-1.0.0.pom"
```

| curl 옵션 | 설명 |
|-----------|------|
| `-sf` | silent + fail (HTTP 에러 시 실패 반환) |
| `-o /dev/null` | 응답 본문 버림 |
| `-u` | Basic 인증 |
| `--upload-file` | HTTP PUT으로 파일 업로드 (curl이 자동으로 PUT 사용) |

### URL 패턴

```
{NEXUS_URL}/repository/maven-releases/{groupPath}/{artifactId}/{version}/{artifactId}-{version}.{ext}
```

- `groupPath`: `com.example` → `com/example` (`.`을 `/`로 치환)
- `ext`: `war`, `pom`, `jar` 등

### WAR 파일 생성

소스 위치 `infra/docker/shared/sample-apps/egov-sample/src/main/webapp`의 `WEB-INF/web.xml`을 zip으로 묶어 임시 WAR 파일을 생성한다.

```bash
cd "$SAMPLE_DIR/egov-sample/src/main/webapp" && zip -q "$TMPWAR" WEB-INF/web.xml
```

## 런타임 흐름 (Jenkins BUILD → DEPLOY)

Jenkins BUILD 파이프라인이 완료되면 `DagExecutionCoordinator`가 아티팩트 URL을 조합하여 후속 DEPLOY 잡에 전달한다.

### ARTIFACT_URL 조합 로직

`DagExecutionCoordinator.java`의 `handleBuildJobCompletion()`에서 수행한다.

1. **Nexus URL 결정**: 프리셋의 LIBRARY 카테고리 도구 URL 조회 → 없으면 configJson의 `NEXUS_URL` 폴백
2. **GAV 좌표 추출**: Job의 configJson에서 `GROUP_ID`, `ARTIFACT_ID`, `VERSION`, `PACKAGING`(기본 war)
3. **URL 조합**: `{nexusUrl}/repository/maven-releases/{groupPath}/{artifactId}/{version}/{artifactId}-{version}.{packaging}`
4. **contextJson 저장**: `ARTIFACT_URL_{jobId}` 키로 실행 컨텍스트에 저장
5. **DEPLOY 잡 사용**: 후속 DEPLOY 잡이 이 URL에서 아티팩트를 다운로드

### configJson 필수 키 (BUILD 잡)

| 키 | 예시 | 설명 |
|----|------|------|
| `GROUP_ID` | `com.example` | Maven groupId |
| `ARTIFACT_ID` | `egov-sample` | Maven artifactId |
| `VERSION` | `1.0.0` | 버전 |
| `PACKAGING` | `war` | 패키징 타입 (기본값: war) |
| `NEXUS_URL` | `http://34.47.83.38:31280` | 폴백용 (프리셋 LIBRARY 도구가 우선) |
