# Step 5: SupportToolService + ToolRegistry + ConnectorManager

## 도메인 관계 정리

이 프로젝트에는 4개 도메인이 있다. 각각의 역할과 관계를 정리한다.

```
┌─────────────────────────────────────────────────────────────┐
│ Preset (프리셋)                                              │
│ "팀A는 CI에 Jenkins, VCS에 GitLab을 쓴다"는 조합 정의        │
│                                                              │
│   preset_entry ──(tool_id FK)──→ SupportTool                │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ SupportTool (개발지원도구)                                    │
│ Jenkins, GitLab 등 실제 도구의 접속 정보 (URL, 인증)          │
│                                                              │
│   도구 등록 시 ──→ ConnectorManager.createConnectors()       │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ Connector (커넥터)                                           │
│ 외부 도구 ↔ Redpanda 토픽 사이의 이벤트 브릿지               │
│                                                              │
│ 두 종류:                                                     │
│   - Webhook (INBOUND): 외부 도구의 이벤트를 토픽으로 수신    │
│     예) Jenkins 빌드 완료 → Redpanda 토픽                    │
│   - Command (OUTBOUND): 토픽의 메시지로 외부 도구에 명령     │
│     예) Redpanda 토픽 → Jenkins 빌드 트리거                  │
│                                                              │
│ ConnectStreamsClient가 Redpanda Connect(/streams/) API를      │
│ 호출하여 YAML 기반 스트림을 등록/삭제한다.                    │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ Pipeline (파이프라인) — Phase 2 예정                          │
│ 프리셋을 참조하여 실제 빌드/배포를 실행하는 단위              │
└─────────────────────────────────────────────────────────────┘
```

### 왜 CONTAINER_REGISTRY와 STORAGE는 커넥터를 스킵하는가

커넥터는 **이벤트 기반 도구**를 위한 것이다. Jenkins(빌드 이벤트), GitLab(webhook), Nexus(아티팩트 이벤트)는 이벤트를 발생시키거나 명령을 받을 수 있다.

반면 Container Registry(Harbor, ECR)와 Object Storage(MinIO, S3)는 **HTTP API로 직접 호출**하는 도구다. 이미지 push/pull이나 파일 업로드에 이벤트 스트림을 끼울 이유가 없다.

## 이중 구조 (V1/V2) — ToolType 제거 대상

### HEALTH_PATHS

| V1 (ToolType 기반) | V2 (ToolImplementation 기반) |
|---|---|
| REGISTRY → `/v2/` | HARBOR → `/api/v2.0/ping` |
| | DOCKER_REGISTRY → `/v2/` |
| | ECR → (없음, AWS SDK) |

V1에서는 Harbor와 Docker Registry를 구분할 수 없었다.

### deriveToolType()

새 implementation을 기존 ToolType에 억지로 매핑하는 어댑터. ARGOCD, MINIO 등 신규 카테고리는 매핑할 ToolType이 없어서 null 반환.

### 제거 대상 전체 목록

| 파일 | 제거 항목 |
|---|---|
| SupportToolService | `HEALTH_PATHS` V1, `deriveToolType()`, `resolveHealthPath()` V1 fallback |
| ToolRegistry | `getActiveTool(ToolType)` |
| ConnectorManager | `COMMAND_TEMPLATES` V1, `createConnectors()` ToolType fallback |
| SupportTool | `toolType` 필드 |
| SupportToolRequest | `toolType` 필드 |
| SupportToolResponse | `toolType` 필드 |
| SupportToolMapper | `findActiveByToolType()` |
| SupportToolMapper.xml | `findActiveByToolType` 쿼리, SELECT/INSERT/UPDATE의 `tool_type` |
| DB | V15 마이그레이션으로 `tool_type` 컬럼 DROP |

## ToolType 제거 완료

리뷰 후 하위 호환 코드를 전량 제거했다. 빌드 검증 완료.

- `ToolType.java` enum 삭제
- `V15__drop_tool_type_column.sql` 추가
- 백엔드 12개 파일에서 ToolType 참조 제거
- 어댑터 4개: `ToolType.XXX` → `ToolCategory.XXX`로 전환
- PipelineCommandProducer: 동일 전환
- 프론트엔드 3개 파일: `toolType` → `category` + `implementation`
- 검증: `compileJava` BUILD SUCCESSFUL, `tsc --noEmit` 에러 없음

## 퀴즈 회고

- Q1: V1은 REGISTRY 하나로 뭉쳐서 health path를 구분 못 함 → V2에서 구현체별 분리로 해결
- Q2: deriveToolType()의 null 반환은 ToolType 체계로 신규 카테고리를 표현 불가능하다는 증거
- Q3: Registry/Storage는 이벤트 기반이 아닌 HTTP API 직접 호출 도구 → 커넥터 불필요
- Q4: ToolType 제거 시 프론트엔드도 category+implementation으로 동시 수정 필요
