# Phase 0: 미들웨어 프리셋

> 파이프라인이 사용하는 외부 도구를 런타임에 등록·조합하는 기반을 구축한 단계이다.

## 목표

Jenkins, GitLab, Nexus, Harbor 같은 외부 도구의 연결 정보를 애플리케이션 재시작 없이 교체할 수 있어야 한다. `application.yml`에 고정된 URL/인증 정보 대신 DB에 저장하고, 파이프라인이 도구 구현체가 아닌 역할(CI_CD_TOOL, VCS 등)만 참조하도록 설계한다.

## 범위

- `supporttool` 도메인: 외부 도구 등록/조회/수정/삭제
- `preset` 도메인: 역할별 도구 조합을 묶는 미들웨어 프리셋
- `connector` 도메인: Redpanda Connect 스트림 동적 관리
- GoCD 제거 (Jenkins 단일 CI/CD 도구로 정리)

## 기간

2026-03-17 완료

## 구현 항목

- [x] SupportTool CRUD (URL, 인증 정보를 DB에 영속화)
- [x] `ToolRegistry` — 활성 도구를 런타임에 조회하는 레지스트리
- [x] 6카테고리 분류: `CI_CD_TOOL`, `VCS`, `LIBRARY`, `CONTAINER_REGISTRY`, `STORAGE`, `CLUSTER_APP`
- [x] `ToolCategory` + `ToolImplementation` 분리 — 같은 역할의 다른 구현체(Jenkins vs GoCD) 지원 (V13)
- [x] `auth_type` 통합 — `BASIC` / `PRIVATE_TOKEN` / `NONE` 세 가지 인증 방식 (V12)
- [x] `MiddlewarePreset` CRUD — 역할별 도구 조합을 프리셋 단위로 관리
- [x] 동적 Connect 커넥터 관리 — Connect 스트림을 DB에 영속화하고 런타임에 등록/삭제
- [x] GoCD 관련 코드 제거

## 해결한 기술 과제

**auth_type 컬럼 추가 (V12)**
도구마다 인증 방식이 다르다. Jenkins는 BASIC(username/password), GitLab은 PRIVATE_TOKEN, 공개 도구는 NONE을 사용한다. `support_tool` 테이블에 `auth_type` 컬럼을 추가하고, `ToolAuthenticator`가 타입별로 Authorization 헤더를 생성하도록 분기했다.

**category + implementation 분리 (V13)**
초기 설계에서는 `category` 컬럼 하나로 도구를 식별했다. 하지만 같은 역할(CI_CD_TOOL)에 Jenkins와 GoCD가 공존할 수 있어야 하므로, `category`(역할)와 `tool_implementation`(구현체 식별자)을 분리했다. 파이프라인은 category만 알면 되고, 실제 구현체는 프리셋이 결정한다.

**프리셋 교체 시 파급 범위 최소화**
`MiddlewarePreset`을 교체하면 해당 프리셋을 참조하는 모든 파이프라인이 자동으로 새 도구를 사용한다. 개별 파이프라인마다 Jenkins URL을 수정하는 대신, 프리셋 한 곳만 바꾸면 된다. TPS 실무에서 팀마다 다른 Jenkins 인스턴스를 사용하는 상황을 이 구조로 수용한다.

## Flyway 마이그레이션

| 버전 | 내용 |
|------|------|
| V12 | `support_tool` 테이블에 `auth_type` 컬럼 추가 (`BASIC`/`PRIVATE_TOKEN`/`NONE`) |
| V13 | `category` 단일 컬럼을 `tool_category` + `tool_implementation`으로 분리 |
| V14 | `middleware_preset` + `preset_tool_mapping` 테이블 생성 |
| V15 | Connect 스트림 영속화를 위한 `connect_stream` 테이블 생성 |

## 회고

**잘한 것**

도구 정보를 DB에 저장하기로 결정한 것이 이후 모든 Phase의 기반이 되었다. `application.yml`에 URL을 박아 두었다면 Phase 2(K8s 이관) 때 설정 변경 → 재빌드 → 재배포 사이클이 필요했을 것이다. DB 저장 덕분에 포트 번호가 바뀌어도 API 한 번으로 업데이트가 끝난다.

`ToolCategory`와 `ToolImplementation`을 분리한 것도 미래를 대비한 좋은 결정이었다. Phase 3에서 실행 컨텍스트가 `LIBRARY` 카테고리 도구의 URL을 조회할 때, category 기반 추상화가 자연스럽게 활용되었다.

**개선할 것**

필수 카테고리 제약이 없다. 파이프라인 실행 시 `CI_CD_TOOL` 카테고리 도구가 반드시 있어야 하는데, 프리셋 생성 시점에는 검증하지 않아서 실행 직전에야 에러가 발생한다. 프리셋 저장 시 필수 카테고리 존재 여부를 검증하는 로직이 있었다면 더 명확한 피드백이 가능했다.

Connect 스트림 동적 관리가 설계 의도대로 구현되었지만, 실제 Connect YAML과 DB 레코드 간의 동기화가 완벽하지 않다. Connect 재시작 시 DB 기반으로 스트림을 재등록하는 복구 로직이 없어 수동 개입이 필요한 상황이 생길 수 있다.
