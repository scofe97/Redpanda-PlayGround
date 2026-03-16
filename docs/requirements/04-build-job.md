# 빌드 Job

## TPS 원본 — 마인드맵에서 무엇을 하는가

TPS에서 빌드 Job은 소스 코드를 빌드 산출물(이미지, JAR, WAR 등)로 변환하는 작업이다. 빌드에는 두 가지 경로가 있다:

1. **빌드 수행**: Git 저장소에서 소스를 클론하고, Base Image와 Build Script를 사용하여 빌드
2. **반입**: 이미 빌드된 산출물(이미지, JAR)을 외부에서 가져와 등록만 수행

빌드 결과물은 Container Registry(Harbor), Library(Nexus), Storage(Minio)에 저장되며, 이것이 배포 Job의 입력이 된다. CI 미들웨어(Jenkins 등)에 실제 빌드 작업을 위임한다.

---

## PoC 목표 — Playground에서 무엇을 검증하는가

1. **빌드/반입 분기**: 소스를 직접 빌드하는 경로와, 기존 산출물을 반입하는 경로 두 가지를 구분하여 처리한다
2. **CI 추상화**: Jenkins에 한정하지 않고, CiAdapter 인터페이스를 통해 CI 미들웨어를 교체할 수 있는 구조를 만든다
3. **빌드→배포 연계**: 빌드 결과물이 배포 Job의 입력으로 자연스럽게 흐르는 파이프라인 내부 데이터 흐름을 구현한다

---

## 현재 구현 상태

| 항목 | 상태 | 비고 |
|------|------|------|
| Jenkins 빌드 트리거 | 구현됨 | Connect를 통한 Kafka→HTTP 위임 |
| Build 스텝 실행 | 구현됨 | PipelineEngine 내 고정 스텝 |
| Clone 스텝 | 구현됨 | Git 소스 클론 |
| 반입 경로 | 미구현 | 빌드 수행 경로만 존재 |
| CI 추상화 | 미구현 | Jenkins 하드코딩 |

---

## 요구사항

### REQ-04-001: 빌드/반입 선택 [P0]

빌드 Job 생성 시 빌드 수행(BUILD)과 반입(IMPORT) 중 선택할 수 있게 한다.

- **BUILD**: Git URL, 브랜치/태그, Base Image, Build Script 설정
- **IMPORT**: 기존 산출물 참조 (Registry URL + 이미지 태그, 또는 Nexus GAV 좌표)
- IMPORT 시 산출물 존재 여부 검증 (CI 미들웨어에 조회 요청)
- 빌드 Job config에 `buildMode: BUILD | IMPORT` 필드 추가

### REQ-04-002: CI 어댑터 인터페이스 [P0]

빌드 실행을 CI 미들웨어에 위임하는 어댑터 인터페이스를 정의한다. Jenkins 이외의 CI 도구로 교체할 수 있어야 한다.

```
CiAdapter
├── triggerBuild(config) → buildId
├── getBuildStatus(buildId) → status
├── getBuildArtifact(buildId) → artifactInfo
└── cancelBuild(buildId) → void
```

- 기존 Jenkins 연동을 JenkinsCiAdapter로 리팩토링
- 프리셋에서 CI 도구를 해석하여 적절한 어댑터 선택
- 어댑터 등록은 Spring Bean 기반 (ToolCategory.CI_CD_TOOL → 구현체 매핑)

### REQ-04-003: 빌드 설정 구조 [P1]

빌드 Job의 config JSON 스키마를 정의한다.

```json
{
  "buildMode": "BUILD",
  "source": {
    "gitUrl": "https://gitlab.com/team-a/app.git",
    "branch": "main",
    "credentialId": "gitlab-token"
  },
  "build": {
    "baseImage": "eclipse-temurin:21-jdk",
    "script": "./gradlew bootJar",
    "outputType": "DOCKER_IMAGE"
  },
  "output": {
    "registry": "harbor.example.com",
    "repository": "team-a/app",
    "tag": "${IMAGE_TAG}"
  }
}
```

- `${PARAM}` 형태의 파이프라인 파라미터 참조 지원
- outputType: DOCKER_IMAGE / JAR / WAR / TAR
- IMPORT 모드일 때는 source/build 대신 `import.artifactRef` 사용

### REQ-04-004: 빌드→배포 데이터 흐름 [P1]

빌드 Job 완료 시 결과물 정보를 다음 Job(배포)에 전달하는 메커니즘을 구현한다.

- 빌드 완료 이벤트에 산출물 정보 포함 (이미지 URL, 다이제스트, 크기)
- 파이프라인 실행 컨텍스트에 `buildOutput` 저장
- 배포 Job이 `buildOutput`을 참조하여 배포 대상 결정
- IMPORT 모드에서도 동일한 buildOutput 형태로 정규화

### REQ-04-005: 빌드 로그 수집 [P2]

CI 미들웨어의 빌드 로그를 수집하여 사용자에게 제공한다.

- CiAdapter에 `getBuildLog(buildId)` 메서드 추가
- 로그는 SSE 또는 폴링으로 전달 (실시간 스트리밍은 P2)
- 빌드 완료 후 전체 로그 저장 (파이프라인 실행 이력에 첨부)

---

## 설계 고민 포인트

### CiAdapter의 비동기 실행 모델

빌드는 장시간 작업이므로 CiAdapter.triggerBuild()는 비동기여야 한다. 현재 Jenkins 연동이 이미 Kafka를 경유하는 비동기 모델(commands 토픽 → Connect → Jenkins HTTP → webhook 콜백)을 사용하고 있다. 이 모델을 CiAdapter 인터페이스 뒤에 숨기되, 어댑터마다 비동기 구현 방식이 다를 수 있음을 고려해야 한다.

- Jenkins: Kafka → Connect → HTTP (현재 방식)
- GitHub Actions: REST API → webhook 콜백
- GoCD: REST API → 폴링

어댑터 인터페이스는 `triggerBuild()` → `buildId` 반환 + 완료 이벤트 발행이라는 공통 계약만 정의하고, 내부 비동기 메커니즘은 어댑터가 결정한다.

### Clone과 Build의 분리 vs 통합

현재 Clone과 Build가 별도 스텝이다. CI 미들웨어에 빌드를 위임하면, CI 도구가 Git 클론부터 빌드까지 한 번에 처리하는 것이 일반적이다(Jenkins Job이 SCM 설정을 가지고 있으므로). PoC에서는 Clone 스텝을 빌드 Job에 흡수하여 "빌드 Job = 소스 취득 + 빌드 + 산출물 저장"의 단일 단위로 만드는 것이 자연스럽다.
