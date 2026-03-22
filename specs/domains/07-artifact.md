# 결과물 관리 도메인

## 개요

결과물 관리는 빌드/배포 과정에서 생성된 아티팩트(WAR, Docker 이미지)를 통합 조회하는 도메인이다. 저장소는 역할에 따라 Harbor(Container Registry), Nexus(Library), Minio(Storage) 세 가지로 나뉘며, 각 저장소를 `ArtifactRegistryAdapter` 인터페이스로 추상화하여 동일한 API 구조로 결과를 반환한다.

아티팩트는 자체 DB에 저장하지 않고 외부 저장소를 직접 조회하는 "뷰(View)" 방식을 택한다. 빌드 Job이 완료되면 산출물이 저장소에 자동 등록되고, 사용자가 직접 업로드하는 경로도 제공한다. Phase 4에서 구현 예정이다.

---

## 현재 상태 (2026-03-22 기준)

| 항목 | 상태 | 비고 |
|------|------|------|
| SupportTool 기반 Harbor/Nexus 연결 정보 관리 | 완료 | 08-middleware SupportTool CRUD |
| Nexus 컴포넌트 조회 | 부분구현 | SupportTool 소스 브라우징 경로로 제한적 구현 |
| GitLab 파일 목록 브라우징 | 부분구현 | 소스 브라우징 용도, 아티팩트 관리와 별개 |
| Harbor 이미지 통합 조회 | 미구현 | SupportTool에 Harbor 등록은 가능하나 조회 API 없음 |
| 아티팩트 직접 업로드 | 미구현 | |
| 메타데이터 수정 | 미구현 | |
| 빌드 결과 자동 등록 | 미구현 | |

---

## 요구사항

### REQ-07-001: 아티팩트 통합 조회 [P0] — 미구현

저장소 유형에 관계없이 아티팩트를 통합 조회하는 API를 제공한다. Harbor/Nexus/Minio의 결과를 공통 DTO로 정규화하여 반환한다.

**수용 기준**:
- [ ] 공통 DTO: `id`, `name`, `version/tag`, `type(IMAGE/JAR/WAR/TAR)`, `size`, `createdAt`, `repositoryType(HARBOR/NEXUS/MINIO)`
- [ ] Harbor: 리포지토리 목록 → 태그 목록 → 상세 (Harbor REST API v2)
- [ ] Nexus: 컴포넌트 검색 — group/artifact/version (Nexus REST API)
- [ ] Minio: 버킷 내 오브젝트 목록 (S3 호환 API)
- [ ] 필터: 저장소 유형, 이름 검색, 태그 검색

**구현 참조**: (미구현)

**Phase**: 4

---

### REQ-07-002: 아티팩트 직접 업로드 [P1] — 미구현

빌드 파이프라인을 거치지 않고 사용자가 아티팩트를 직접 업로드하는 기능을 제공한다.

**수용 기준**:
- [ ] Harbor: Docker 이미지 push (API를 통한 레이어 업로드 또는 tar 업로드)
- [ ] Nexus: 컴포넌트 업로드 (Maven/raw 리포지토리)
- [ ] Minio: 오브젝트 업로드 (multipart upload)
- [ ] 업로드 시 메타데이터 입력 (태그, 설명, 프로젝트 소속)
- [ ] 업로드 진행률 반환 (대용량 파일 대응)

**구현 참조**: (미구현)

**Phase**: 4

---

### REQ-07-003: 아티팩트 메타데이터 수정 [P1] — 미구현

아티팩트의 태그와 설명 등 메타데이터를 수정하는 기능을 제공한다.

**수용 기준**:
- [ ] 태그 추가/제거 (Harbor: 이미지 태그 retag, Nexus: 프로퍼티)
- [ ] 설명 수정
- [ ] 프로젝트 소속/라벨 변경
- [ ] 삭제는 P2 (삭제 정책과 연관하여 별도 검토)

**구현 참조**: (미구현)

**Phase**: 4

---

### REQ-07-004: ArtifactRegistryAdapter 인터페이스 [P0] — 미구현

저장소별 연동을 어댑터 인터페이스로 추상화한다. 저장소가 교체되더라도 상위 서비스 로직은 변경하지 않아도 된다.

**수용 기준**:
- [ ] `ArtifactRegistryAdapter` 인터페이스 정의: `listArtifacts()`, `getArtifact()`, `uploadArtifact()`, `updateMetadata()`, `deleteArtifact()`
- [ ] `HarborAdapter`, `NexusAdapter`, `MinioAdapter` 구현
- [ ] 미들웨어(08) SupportTool에서 저장소 URL과 인증 정보 조회
- [ ] 프리셋의 Container Registry/Library/Storage 역할에 어댑터 매핑

**구현 참조**: (미구현)

**Phase**: 4

---

### REQ-07-005: 빌드 결과 자동 등록 [P2] — 미구현

빌드 Job 완료 시 생성된 산출물을 아티팩트 관리에 자동 연계한다. 파이프라인 실행 이력과 아티팩트를 연결하여 "이 빌드에서 어떤 이미지가 생성되었는가"를 추적할 수 있게 한다.

**수용 기준**:
- [ ] 빌드 완료 이벤트 소비 → 아티팩트 메타데이터 자동 생성
- [ ] 빌드 Job output 정보(Registry URL, 태그, 다이제스트)를 아티팩트로 매핑
- [ ] 자동 등록된 아티팩트는 파이프라인 실행 이력(`executionId`)과 연결

**구현 참조**: (미구현)

**Phase**: 4

---

## 설계 결정

### 아티팩트는 "뷰"인가 "엔티티"인가

아티팩트 관리가 Harbor/Nexus/Minio의 데이터를 그대로 조회하는 "뷰"인지, 자체 DB에 메타데이터를 복제하는 "엔티티"인지에 따라 설계가 달라진다. 뷰 방식은 매번 외부 API를 호출하여 최신 상태를 반환하므로 데이터 불일치가 없지만, 조회 성능이 외부 시스템에 의존한다. 엔티티 방식은 자체 DB를 캐시로 사용하여 빠르지만 동기화 문제가 생긴다.

PoC에서는 뷰 방식으로 시작한다. 외부 API 호출 결과를 `ArtifactInfo` DTO로 정규화하여 반환하고, 자체 DB에는 저장하지 않는다. 조회 성능이 문제가 되면 캐시 레이어를 추가하는 것이 확장 경로다.

### Minio는 꼭 필요한가

Harbor(이미지)와 Nexus(라이브러리)로 대부분의 산출물을 커버할 수 있다. Minio는 이미지나 라이브러리로 분류하기 어려운 "기타 파일"을 저장하는 용도다. PoC에서는 Harbor + Nexus를 우선 구현하고, Minio는 어댑터 인터페이스만 정의해두는 것이 현실적이다.

---

## 도메인 간 관계

| 참조 도메인 | 관계 | 설명 |
|------------|------|------|
| 04-build-job | 산출물 생성 | BUILD Job 완료 시 아티팩트가 저장소에 등록되고 실행 컨텍스트에 URL 기록 |
| 06-deploy-job | 산출물 소비 | DEPLOY Job이 `ARTIFACT_URL`로 아티팩트를 참조하여 배포 |
| 08-middleware | SupportTool 연결 정보 | Harbor/Nexus 접속 URL과 인증 정보를 SupportTool에서 조회 |

---

## 미해결 사항

- **ArtifactRegistryAdapter 구현 (REQ-07-004)**: Harbor/Nexus 어댑터 구현이 없어 현재 아티팩트를 통합 조회하는 경로가 없다. SupportTool에 Harbor 등록은 가능하지만 조회 API가 아직 연결되지 않은 상태다.
- **Nexus 소스 브라우징 재활용 여부**: 현재 부분 구현된 Nexus 컴포넌트 조회 로직을 아티팩트 통합 조회로 승격할 수 있는지 검토가 필요하다.
- **빌드 결과 자동 등록 (REQ-07-005)**: 파이프라인 실행 이력과 아티팩트를 연결하는 연계가 없어 "이 빌드에서 어떤 아티팩트가 나왔는가"를 현재 추적할 수 없다.
