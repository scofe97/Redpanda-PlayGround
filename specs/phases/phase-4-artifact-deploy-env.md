# Phase 4: 결과물 + 배포환경 관리

> 아티팩트 저장소 통합 조회와 배포 대상 환경 관리를 구현하여, Deploy Job이 "어디에 무엇을 배포할지"를 명시적으로 참조하는 구조를 완성한다.

## 목표

- Harbor/Nexus/Minio를 어댑터 인터페이스로 추상화하여 통합 조회 API를 제공한다
- VM 서버와 K8S 클러스터를 배포 목적지 엔티티로 등록·관리한다
- Deploy Job config에 `environmentId`를 추가하여, 파이프라인 실행 시 하드코딩 없이 환경 정보를 조회하는 구조로 전환한다

## 사전 조건

- Phase 3 완료 (충족): DAG 엔진, 파이프라인 실행 이력, 프리셋 관리 모두 동작 중
- GitLab/Nexus/Harbor SupportTool 등록 기능 구현됨 (연결 정보 조회에 사용)

## 범위

### 도메인 — 결과물 관리 (REQ-07)

| REQ | 내용 | 우선순위 |
|-----|------|---------|
| REQ-07-004 | ArtifactRegistryAdapter 인터페이스 + HarborAdapter/NexusAdapter 구현 | P0 |
| REQ-07-001 | 아티팩트 통합 조회 API (필터: 저장소 유형, 이름, 태그) | P0 |
| REQ-07-002 | 직접 업로드 API (Nexus raw, Harbor tar) | P1 |
| REQ-07-003 | 아티팩트 메타데이터 수정 (태그, 설명) | P1 |
| REQ-07-005 | 빌드 완료 이벤트 → 아티팩트 자동 등록 | P2 |

MinioAdapter는 인터페이스만 정의하고 구현은 이번 Phase에서 제외한다. Harbor + Nexus로 대부분의 산출물 유형을 커버할 수 있기 때문이다.

### 도메인 — 배포 환경 (REQ-09)

| REQ | 내용 | 우선순위 |
|-----|------|---------|
| REQ-09-001 | VM 서버 엔티티 (host, sshPort, credentialId, tags) | P0 |
| REQ-09-002 | K8S 클러스터 엔티티 (apiServerUrl, credentialId, defaultNamespace) | P0 |
| REQ-09-003 | 환경 CRUD + 통합 목록 조회 + 연결 테스트 | P0 |
| REQ-09-004 | 배포 Job config에 environmentId 연결 | P1 |
| REQ-09-005 | Credential 엔티티 (AES-256 암호화, 조회 시 마스킹) | P2 |

## 구현 계획

### 1단계: 아티팩트 어댑터 인터페이스 설계

`ArtifactRegistryAdapter` 인터페이스를 정의한다. 메서드는 `listArtifacts(filter)`, `getArtifact(id)`, `uploadArtifact(file, metadata)`, `updateMetadata(id, metadata)`, `deleteArtifact(id)` 다섯 가지다. 반환 타입은 저장소 유형에 무관한 공통 DTO `ArtifactInfo`와 `ArtifactDetail`로 정규화한다.

SupportTool 도메인에서 연결 정보(URL, 인증 토큰)를 조회하는 `ArtifactRegistryResolver`를 별도로 구현한다. 어댑터가 직접 DB를 참조하지 않도록 의존 방향을 단방향으로 유지한다.

### 2단계: HarborAdapter / NexusAdapter 구현

HarborAdapter는 Harbor REST API v2를 사용한다. 리포지토리 목록 → 태그 목록 → 상세 순서로 조회하며, ArtifactInfo로 정규화하여 반환한다.

NexusAdapter는 Nexus REST API의 컴포넌트 검색 엔드포인트를 사용한다. group/artifact/version 필터를 지원하고, 결과를 ArtifactInfo로 매핑한다.

MinioAdapter는 인터페이스를 구현하는 빈 클래스만 작성하고, 모든 메서드에서 `UnsupportedOperationException`을 던지는 스텁으로 남긴다.

### 3단계: 아티팩트 조회 API + UI

`GET /api/artifacts?type=IMAGE|JAR|WAR|TAR&name=&tag=` 엔드포인트를 구현한다. 내부에서는 저장소 유형 필터에 따라 해당 어댑터만 호출하거나, 전체 어댑터를 병렬 호출하여 결과를 합산한다.

프론트엔드에는 아티팩트 목록 페이지(`/artifacts`)를 추가한다. 저장소 유형 탭 + 이름 검색으로 필터링한다. 각 행에서 메타데이터 수정 폼을 인라인으로 열 수 있다.

### 4단계: 배포 환경 엔티티 설계

`DeployEnvironment` 엔티티를 단일 테이블로 설계한다. `environmentType: VM | K8S` 필드로 유형을 구분하고, 유형별 설정은 `config JSON` 컬럼에 저장한다. 저장 시 유형별 필수 필드(`host`, `sshPort` 또는 `apiServerUrl`, `defaultNamespace`)를 서비스 레이어에서 검증한다.

`credentialId`는 이번 Phase에서 평문 참조(환경 변수 키 이름)로 임시 구현하고, Phase N+1에서 Credential 엔티티로 분리한다.

### 5단계: 환경 CRUD API + UI

`POST /api/environments`, `GET /api/environments`, `PUT /api/environments/{id}`, `DELETE /api/environments/{id}` 를 구현한다. 목록 조회는 `type` 필터와 `tags` 필터를 지원한다.

연결 테스트 엔드포인트 `POST /api/environments/{id}/test`를 추가한다. VM의 경우 SSH 포트 open 확인, K8S의 경우 API 서버 `/healthz` 호출로 응답 코드만 확인한다.

프론트엔드에는 환경 관리 페이지(`/environments`)를 추가한다.

### 6단계: Deploy Job에서 배포 환경 참조

`pipeline_job` 테이블의 `config` JSON에 `environmentId` 필드를 추가한다. 파이프라인 실행 시 Deploy Job의 `config`를 파싱하여 `environmentId`로 환경 엔티티를 조회하고, 접속 정보를 Jenkins Job 파라미터로 전달한다.

기존 하드코딩된 접속 정보는 이 단계에서 제거한다.

## 수용 기준

- [ ] Nexus에서 컴포넌트 목록을 조회할 수 있고, ArtifactInfo DTO로 정규화된 응답이 반환된다
- [ ] Harbor에서 이미지 목록(리포지토리 + 태그)을 조회할 수 있다
- [ ] 아티팩트 메타데이터(태그, 설명)를 수정하면 실제 Harbor/Nexus에 반영된다
- [ ] VM 서버와 K8S 클러스터를 생성/수정/삭제할 수 있다
- [ ] 환경 연결 테스트가 성공/실패 여부를 반환한다
- [ ] Deploy Job config에 `environmentId`를 설정하면, 파이프라인 실행 시 해당 환경 정보가 사용된다
- [ ] 환경 없이 생성된 기존 Deploy Job은 이전 방식대로 동작한다 (하위 호환)

## 리스크

| 리스크 | 설명 | 완화 방안 |
|--------|------|----------|
| Harbor/Nexus API 버전 불일치 | 설치된 버전에 따라 API 경로가 다를 수 있다 | SupportTool에 버전 필드를 추가하고 어댑터에서 버전 분기 처리 |
| 인증 방식 다양성 | Harbor는 Bearer Token, Nexus는 Basic Auth, Minio는 AWS Signature | 어댑터별 인증 로직을 캡슐화하고 외부로 노출하지 않음 |
| 아티팩트 조회 성능 | 외부 API를 매번 호출하므로 응답 속도가 외부 시스템에 의존한다 | PoC 범위에서는 허용. 문제가 되면 짧은 TTL의 인메모리 캐시 추가 |
| config JSON 스키마 관리 | 유형별 config 필드가 다르고 타입 검증이 어렵다 | 유형별 Java record를 만들고 서비스 레이어에서 역직렬화+검증 |

## 제외 항목

- MinioAdapter 실제 구현 (인터페이스 + 스텁만)
- REQ-07-005 빌드 결과 자동 등록 (P2, 이벤트 연동 복잡도 높음)
- REQ-09-005 Credential 엔티티 + AES 암호화 (P2, 별도 Phase에서 처리)
- 아티팩트 삭제 기능 (삭제 정책과 연관되어 복잡도가 높다)
