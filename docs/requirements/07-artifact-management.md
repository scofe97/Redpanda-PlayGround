# 결과물 관리

## TPS 원본 — 마인드맵에서 무엇을 하는가

TPS에서 결과물(출력물) 관리는 빌드 산출물을 저장소에서 관리하는 기능이다. 저장소는 역할에 따라 세 가지로 나뉜다:

| 저장소 유형 | 구현체 | 포맷 | 배포 연계 |
|-----------|--------|------|----------|
| Container Registry | Harbor | .tar (Docker 이미지) | K8S 배포에 사용 |
| Library | Nexus | .jar/.war/.tar | VM/K8S 배포에 사용 |
| Storage | Minio | .jar/.war/.tar | VM 배포에 사용 |

기본 기능은 조회, 업로드, 수정 세 가지다. 빌드 Job이 완료되면 산출물이 자동으로 해당 저장소에 등록되고, 사용자가 직접 업로드할 수도 있다.

---

## PoC 목표 — Playground에서 무엇을 검증하는가

1. **통합 아티팩트 조회**: Harbor/Nexus/Minio의 산출물을 통합된 인터페이스로 조회한다. 저장소 유형에 관계없이 동일한 API 구조로 결과를 반환한다
2. **직접 업로드**: 빌드 파이프라인을 거치지 않고 사용자가 아티팩트를 직접 업로드할 수 있는 경로를 제공한다
3. **메타데이터 수정**: 태그 변경, 설명 추가 등 아티팩트의 메타데이터를 수정하는 기능을 구현한다

---

## 현재 구현 상태

| 항목 | 상태 | 비고 |
|------|------|------|
| SupportTool CRUD | 구현됨 | Harbor/Nexus/Registry 연결 정보 관리 |
| 소스 브라우징 | 부분구현 | GitLab 파일 목록, Nexus 컴포넌트 조회 |
| Harbor 이미지 조회 | 미구현 | SupportTool에 Harbor 등록은 가능 |
| 직접 업로드 | 미구현 | |
| 메타데이터 수정 | 미구현 | |

---

## 요구사항

### REQ-07-001: 아티팩트 통합 조회 [P0]

저장소 유형에 관계없이 아티팩트를 통합 조회하는 API를 제공한다.

- 조회 결과 공통 DTO: id, name, version/tag, type(IMAGE/JAR/WAR/TAR), size, createdAt, repositoryType(HARBOR/NEXUS/MINIO)
- Harbor: 리포지토리 목록 → 태그 목록 → 상세 (Harbor REST API v2)
- Nexus: 컴포넌트 검색 (group/artifact/version) (Nexus REST API)
- Minio: 버킷 내 오브젝트 목록 (S3 호환 API)
- 필터: 저장소 유형, 이름 검색, 태그 검색

### REQ-07-002: 아티팩트 업로드 [P1]

사용자가 아티팩트를 직접 업로드하는 기능을 제공한다.

- Harbor: Docker 이미지 push (API를 통한 레이어 업로드 또는 tar 업로드)
- Nexus: 컴포넌트 업로드 (Maven/raw 리포지토리)
- Minio: 오브젝트 업로드 (multipart upload)
- 업로드 시 메타데이터 입력 (태그, 설명, 프로젝트 소속)
- 업로드 진행률 반환 (대용량 파일 대응)

### REQ-07-003: 아티팩트 메타데이터 수정 [P1]

아티팩트의 메타데이터를 수정하는 기능을 제공한다.

- 태그 추가/제거 (Harbor: 이미지 태그 retag, Nexus: 프로퍼티)
- 설명 수정
- 프로젝트 소속/라벨 변경
- 삭제는 P2 (삭제 정책과 연관)

### REQ-07-004: 아티팩트 어댑터 [P0]

저장소별 연동을 어댑터 인터페이스로 추상화한다.

```
ArtifactRegistryAdapter
├── listArtifacts(filter) → List<ArtifactInfo>
├── getArtifact(id) → ArtifactDetail
├── uploadArtifact(file, metadata) → ArtifactInfo
├── updateMetadata(id, metadata) → void
└── deleteArtifact(id) → void
```

- HarborAdapter, NexusAdapter, MinioAdapter 구현
- 미들웨어(08) 도메인의 SupportTool에서 연결 정보(URL, 인증) 조회
- 프리셋의 Container Registry/Library/Storage 역할에 매핑

### REQ-07-005: 빌드 결과 자동 등록 [P2]

빌드 Job 완료 시 산출물이 아티팩트 관리에 자동 등록되도록 연계한다.

- 빌드 완료 이벤트 → 아티팩트 메타데이터 자동 생성
- 빌드 Job의 output 정보(Registry URL, 태그, 다이제스트)를 아티팩트로 매핑
- 자동 등록된 아티팩트는 파이프라인 실행 이력과 연결

---

## 설계 고민 포인트

### 아티팩트는 "뷰"인가 "엔티티"인가

아티팩트 관리가 Harbor/Nexus/Minio의 데이터를 그대로 조회하는 "뷰"인지, 자체 DB에 메타데이터를 복제하는 "엔티티"인지에 따라 설계가 달라진다.

- **뷰 방식**: 매번 외부 API를 호출하여 최신 상태를 반환. 데이터 불일치가 없지만 조회 성능이 외부 시스템에 의존한다
- **엔티티 방식**: 자체 DB에 아티팩트 메타데이터를 캐시. 빠르지만 동기화 문제가 생긴다

PoC에서는 뷰 방식으로 시작한다. 외부 API 호출 결과를 ArtifactInfo DTO로 정규화하여 반환하고, 자체 DB에는 저장하지 않는다. 업로드/수정 시에는 외부 API를 직접 호출한다. 조회 성능이 문제가 되면 캐시 레이어를 추가하는 것이 확장 경로다.

### Minio는 꼭 필요한가

Harbor(이미지)와 Nexus(라이브러리)로 대부분의 산출물을 커버할 수 있다. Minio는 빌드 산출물 중 이미지나 라이브러리로 분류하기 어려운 "기타 파일"을 저장하는 용도다. PoC에서는 Harbor + Nexus만 우선 구현하고, Minio는 인터페이스만 정의해두는 것이 현실적이다.
