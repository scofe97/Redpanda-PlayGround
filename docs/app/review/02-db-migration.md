# Step 2: DB 마이그레이션 (V13, V14)

## V13 — support_tool 확장

- `category`, `implementation` 컬럼 추가
- 기존 데이터 매핑 (JENKINS→CI_CD_TOOL, GITLAB→VCS, NEXUS→LIBRARY, REGISTRY→CONTAINER_REGISTRY)
- nullable 추가 → 데이터 채우기 → NOT NULL 전환 순서
- `tool_type` 컬럼은 잔류 (ToolType 제거 시 DROP 예정)

## V14 — 프리셋 테이블

- `middleware_preset`: 프리셋 헤더 (name UNIQUE)
- `preset_entry`: 카테고리 → 도구 매핑
  - `ON DELETE CASCADE`: 프리셋 삭제 시 엔트리 자동 삭제
  - `UNIQUE (preset_id, category)`: 프리셋당 카테고리 중복 방지
  - `tool_id FK`: 존재하지 않는 도구 참조 차단
