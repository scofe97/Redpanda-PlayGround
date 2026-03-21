# Step 6: 프리셋 도메인 (엔티티 + Mapper + Service)

## 엔티티 구조

- **MiddlewarePreset** (Aggregate Root): id, name, description, entries(자식 컬렉션)
- **PresetEntry** (자식): category → toolId 매핑 + JOIN으로 채워지는 읽기 전용 필드(toolName, toolUrl, toolImplementation)

Preset이 Entry를 소유한다. Entry는 Preset 없이 존재 불가 (ON DELETE CASCADE).

## MyBatis collection 매핑

`<collection>` 태그로 preset → preset_entry → support_tool 3테이블 LEFT JOIN 한 방 조회. LEFT JOIN인 이유는 엔트리가 없는 프리셋도 조회되어야 하기 때문이다.

## PresetService 핵심 로직

1. **saveEntries()**: 각 엔트리마다 도구 존재 확인 + 카테고리 불일치 검증 후 저장
2. **update — delete-then-insert**: 기존 엔트리 전부 삭제 후 새로 삽입. 비교 로직 불필요, entry.id가 매번 바뀌지만 외부 참조 없으므로 합리적
3. **resolveToolByCategory()**: 프리셋에서 해당 카테고리의 toolId를 찾아 SupportTool 전체(인증 정보 포함)를 반환. JOIN의 표시용 필드 3개로는 부족하기 때문
4. **resolveAllTools()**: 프리셋의 모든 매핑을 `Map<ToolCategory, SupportTool>`로 반환

## 퀴즈 회고

- Q1: LEFT JOIN — 엔트리 없는 프리셋도 조회되어야 함 (INNER JOIN이면 누락)
- Q2: delete-then-insert — 비교 로직 불필요, 엔트리 수 최대 6개로 I/O 부담 없음. 단, entry.id 변경됨
- Q3: 카테고리 불일치 검증 없으면 CI_CD_TOOL에 Nexus(LIBRARY) 매핑 가능 → 프리셋 의미 깨짐
- Q4: resolveToolByCategory()는 SupportTool 전체(인증 정보 포함)가 필요하므로 재조회. JOIN의 3개 표시용 필드로는 부족
