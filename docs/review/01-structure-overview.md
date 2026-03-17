# Step 1: 전체 구조 오버뷰

## 핵심 개념

Phase 1은 기존 개발지원도구(support_tool)에 **역할 기반 프리셋** 개념을 도입한 것이다.

기존에는 `ToolType`이 도구명과 역할을 동시에 표현했다(JENKINS, GITLAB, NEXUS, REGISTRY). Jenkins를 GoCD로 교체하려면 enum 자체를 바꿔야 했다. 이를 2차원으로 분리했다:

- **ToolCategory** — 역할 (CI_CD_TOOL, VCS, CONTAINER_REGISTRY, LIBRARY, STORAGE, CLUSTER_APPLICATION)
- **ToolImplementation** — 구현체 (JENKINS, GOCD, GITLAB, HARBOR, ...)

프리셋은 "이 팀은 CI에 Jenkins, VCS에 GitLab, Registry에 Harbor를 쓴다"는 조합을 하나의 이름으로 묶는다. 파이프라인이 프리셋을 참조하면, 프리셋의 도구만 교체하면 모든 파이프라인에 반영된다.

## 변경 파일 맵

```
신규 13개 / 수정 7개

com.study.playground
├── supporttool/                         (수정)
│   ├── domain/
│   │   ├── SupportTool.java             ← category, implementation 필드 추가
│   │   ├── ToolCategory.java            ← 6개 역할 enum
│   │   └── ToolImplementation.java      ← 15개 구현체 enum
│   ├── mapper/
│   │   ├── SupportToolMapper.java       ← findActiveByCategory 등 추가
│   │   └── SupportToolMapper.xml        ← 신규 쿼리 + 기존 SELECT에 컬럼 추가
│   ├── dto/
│   │   ├── SupportToolRequest.java      ← category, implementation 필드 추가
│   │   └── SupportToolResponse.java     ← 동일
│   └── service/
│       ├── SupportToolService.java      ← HEALTH_PATHS_V2, deriveToolType()
│       └── ToolRegistry.java            ← getActiveTool(ToolCategory) 오버로드
├── preset/                              (신규)
│   ├── domain/
│   │   ├── MiddlewarePreset.java        ← Aggregate Root (이름 + entries)
│   │   └── PresetEntry.java             ← category → toolId 매핑
│   ├── mapper/ + xml/                   ← CRUD + JOIN 조회
│   ├── dto/
│   │   ├── PresetRequest.java           ← class (validation)
│   │   └── PresetResponse.java          ← record (정적 팩토리)
│   ├── service/PresetService.java       ← CRUD + resolveToolByCategory()
│   └── api/PresetController.java        ← /api/presets CRUD
└── connector/
    └── ConnectorManager.java            ← SKIP_CATEGORIES, COMMAND_TEMPLATES_V2
```

## 카테고리 ↔ 구현체 매핑

| ToolCategory | ToolImplementation |
|---|---|
| CI_CD_TOOL | JENKINS, GOCD, GITHUB_ACTIONS |
| VCS | GITLAB, GITHUB, BITBUCKET |
| CONTAINER_REGISTRY | HARBOR, DOCKER_REGISTRY, ECR |
| LIBRARY | NEXUS, ARTIFACTORY |
| STORAGE | MINIO, S3 |
| CLUSTER_APPLICATION | ARGOCD, FLUX |

## 요청 흐름

```
POST /api/presets
  → PresetController.create()
    → PresetService.create()
      → presetMapper.insert(preset)          -- 프리셋 헤더 저장
      → saveEntries()                        -- 각 entry 검증 + 저장
        → supportToolMapper.findById(toolId) -- 도구 존재 확인
        → tool.category == 요청 category?    -- 카테고리 불일치 검증
        → entryMapper.insert(entry)          -- entry 저장
      → findById(preset.getId())             -- JOIN으로 완성된 응답 반환
```

## 프리셋 예시

```
프리셋 "팀A 표준"
├── CI_CD_TOOL         → Jenkins (id=1)
├── VCS                → GitLab (id=2)
├── LIBRARY            → Nexus (id=3)
└── CONTAINER_REGISTRY → Harbor (id=4)
```

도구 교체는 DB 레벨 조작이다. `PUT /api/presets/{id}`로 엔트리의 toolId를 바꾸면 되고, 코드 변경은 필요 없다.

## Swagger 테스트

프론트엔드 연동은 아직 없다. SpringDoc이 설정되어 있어 `http://localhost:8080/swagger-ui.html`에서 API 테스트 가능하다.

## 후속 작업 (Step 5 이후)

ToolType은 하위 호환용으로 잔류 중이나, 완전 제거 예정이다:
- `ToolType` enum, `SupportTool.toolType` 필드, `deriveToolType()`, `HEALTH_PATHS` V1, `COMMAND_TEMPLATES` V1, DB `tool_type` 컬럼
