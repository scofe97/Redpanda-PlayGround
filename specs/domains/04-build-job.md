# 04. 빌드 Job 도메인 스펙

## 개요

빌드 Job은 소스 코드를 컴파일하고 아티팩트(JAR/Docker Image)를 생성하는 CI 단위이다. Jenkins Pipeline으로 실행되며, 빌드 결과물의 GAV 좌표를 실행 컨텍스트(`context_json`)에 저장하여 후속 DEPLOY Job이 이를 참조할 수 있게 한다.

현재 Jenkins 트리거와 기본 BUILD/CLONE 스텝이 구현되어 있다. 빌드→배포 데이터 흐름(`ARTIFACT_URL`)은 Phase 3에서 완료되었다. 반입(IMPORT) 경로는 설계 결정에 따라 제외되었으며, CI 추상화(CiAdapter)는 향후 과제로 남아 있다.

---

## 현재 상태 (2026-03-22)

| 항목 | 상태 | 비고 |
|------|------|------|
| Jenkins 빌드 트리거 | 완료 | Kafka → Connect → Jenkins HTTP |
| BUILD 스텝 실행 | 완료 | PipelineEngine 내 고정 스텝 |
| Clone 스텝 | 완료 | Git 소스 클론 |
| Jenkins BUILD 파이프라인 (Nexus curl) | 완료 | Rev.18. configXml + Jenkinsfile |
| 빌드→배포 데이터 흐름 (ARTIFACT_URL) | 완료 | Phase 3. context_json(V36) 저장 |
| 반입(IMPORT) 경로 | 미구현 | 설계 결정: PoC 범위에서 제외 |
| CI 추상화 (CiAdapter) | 미구현 | 향후 과제 |

---

## 요구사항

### REQ-04-001: 빌드/반입 선택 [P0] — 부분 구현

빌드 Job 생성 시 빌드 수행(BUILD)과 반입(IMPORT) 중 선택할 수 있게 한다.

**수용 기준**
- [x] BUILD 경로: Git URL, 브랜치/태그, Base Image, Build Script 설정
- [ ] IMPORT 경로: 기존 산출물 참조 (Registry URL + 이미지 태그, 또는 Nexus GAV 좌표)
- [ ] IMPORT 시 산출물 존재 여부 검증
- [ ] 빌드 Job config에 `buildMode: BUILD | IMPORT` 필드 추가

> 설계 결정 참조: IMPORT는 PoC 범위에서 제외. DEPLOY Job만 있는 파이프라인으로 대체 가능.

---

### REQ-04-002: CI 어댑터 인터페이스 [P0] — 미구현

빌드 실행을 CI 미들웨어에 위임하는 어댑터 인터페이스를 정의한다.

```
CiAdapter
├── triggerBuild(config) → buildId
├── getBuildStatus(buildId) → status
├── getBuildArtifact(buildId) → artifactInfo
└── cancelBuild(buildId) → void
```

**수용 기준**
- [ ] `CiAdapter` 인터페이스 정의
- [ ] 기존 Jenkins 연동을 `JenkinsCiAdapter`로 리팩토링
- [ ] 프리셋에서 CI 도구를 해석하여 적절한 어댑터 선택
- [ ] 어댑터 등록은 Spring Bean 기반 (`ToolCategory.CI_CD_TOOL` → 구현체 매핑)

---

### REQ-04-003: 빌드 설정 구조 [P1] — 미구현

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

**수용 기준**
- [ ] `${PARAM}` 형태의 파이프라인 파라미터 참조 지원
- [ ] `outputType`: `DOCKER_IMAGE / JAR / WAR / TAR`
- [ ] IMPORT 모드일 때는 `source/build` 대신 `import.artifactRef` 사용

---

### REQ-04-004: 빌드→배포 데이터 흐름 [P0] — 완료

BUILD 완료 시 GAV 좌표와 프리셋 기반 Nexus URL을 조합하여 `ARTIFACT_URL_{jobId}`를 자동 구성하고 `context_json`(V36 스키마)에 저장한다. 후속 DEPLOY Job이 이 키를 참조하여 배포 대상을 결정한다.

**수용 기준**
- [x] BUILD 완료 이벤트에 산출물 정보 포함 (GAV 좌표, Nexus URL)
- [x] 파이프라인 실행 컨텍스트 `context_json`에 `ARTIFACT_URL_{jobId}` 저장
- [x] DEPLOY Job이 `ARTIFACT_URL_{jobId}`를 참조하여 배포 대상 결정
- [x] Jenkins BUILD 파이프라인 Nexus curl 업로드 로직 (Rev.18)

---

### REQ-04-005: Jenkins BUILD 파이프라인 [P0] — 완료

Jenkins Job의 `configXml`과 `Jenkinsfile`이 Nexus 업로드를 포함한 전체 빌드 흐름을 수행한다.

**수용 기준**
- [x] `JenkinsAdapter.buildConfigXml()` 구현 (Nexus curl 업로드 포함, Rev.18)
- [x] `Jenkinsfile`에서 빌드 → Nexus 업로드 → 완료 이벤트 발행 흐름 처리
- [ ] `buildConfigXml()`의 동적 파라미터 정의 수정 — 현재 `EXECUTION_ID/STEP_ORDER`만 `parameterDefinitions`에 정의되어 `configJson` 키가 Jenkins에 전달되지 않는 문제 미해결

---

### REQ-04-006: 빌드 로그 수집 [P2] — 미구현

CI 미들웨어의 빌드 로그를 수집하여 사용자에게 제공한다.

**수용 기준**
- [ ] `CiAdapter`에 `getBuildLog(buildId)` 메서드 추가
- [ ] 로그 전달 방식: SSE 또는 폴링 (실시간 스트리밍은 P3)
- [ ] 빌드 완료 후 전체 로그를 파이프라인 실행 이력에 첨부

---

## 설계 결정

### 반입(IMPORT) 모드 제외

PoC 목표는 "이벤트 기반 파이프라인 오케스트레이션" 검증이지, 반입 흐름 구현이 아니다. 기존 아티팩트를 사용해야 하는 시나리오는 DEPLOY Job만 있는 파이프라인(빌드 스텝 없이 Nexus URL을 직접 지정)으로 대체할 수 있으므로 별도 IMPORT Job 타입을 만들지 않는다.

### Clone 스텝을 빌드 Job에 흡수

Jenkins가 SCM 설정을 내부적으로 처리하므로, Clone과 Build를 Playground 엔진 레벨에서 별도 스텝으로 분리할 실익이 없다. "빌드 Job = 소스 취득 + 빌드 + 산출물 저장"을 단일 단위로 취급한다.

### CiAdapter 비동기 계약

`triggerBuild()` 는 `buildId`를 즉시 반환하고, 완료는 Kafka 이벤트로 수신한다. 내부 비동기 메커니즘(Kafka→Connect→HTTP vs. REST API 폴링 등)은 어댑터 구현이 결정하고, 인터페이스는 공통 계약만 노출한다.

---

## 도메인 간 관계

```
Pipeline (03-pipeline)
  └─ Job[type=BUILD]
       └─ config_json → BuildJobConfig
            └─ source.gitUrl, build.script, output.registry
       └─ 실행 결과 → context_json[ARTIFACT_URL_{jobId}]
            └─ → Job[type=DEPLOY] 참조 (05 제외, 06-deploy-job)

JenkinsAdapter (CiAdapter 구현체)
  └─ triggerBuild() → Kafka → Connect → Jenkins HTTP
  └─ 완료 이벤트 ← Jenkins Webhook → Kafka
```

- 파이프라인 도메인: `03-pipeline.md`
- 배포 Job 도메인: `06-deploy-job.md`

---

## 미해결 사항

- `buildConfigXml()`의 동적 파라미터 정의 수정 미완 — `configJson` 키가 Jenkins에 전달되지 않는 버그 (E2E 테스트에서 DEPLOY 아티팩트 다운로드 실패 원인)
- CI 추상화(CiAdapter) 인터페이스 정의 및 JenkinsCiAdapter 리팩토링 미착수
- ARTIFACT_URL 스키마 버전(V36) 변경 시 하위 호환 정책 미결정
