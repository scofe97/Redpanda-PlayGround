# Step 2: DB 마이그레이션 리뷰

## V17: pipeline_definition 테이블

```sql
CREATE TABLE pipeline_definition (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(200)  NOT NULL,
    description TEXT,
    preset_id   BIGINT        REFERENCES middleware_preset(id),
    status      VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP     NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_pipeline_definition_status ON pipeline_definition(status);
```

**양호한 점:**
- `preset_id` FK로 미들웨어 프리셋 참조 무결성 보장
- `status` 인덱스: `findAll`이 `WHERE status = 'ACTIVE'`를 사용하므로 적절
- `description TEXT`: 길이 제한 없는 설명 필드

**이슈:**
- 없음. 깔끔한 설계.

## V18: pipeline_job + pipeline_job_dependency 테이블

```sql
CREATE TABLE pipeline_job (
    id                       BIGSERIAL PRIMARY KEY,
    pipeline_definition_id   BIGINT       NOT NULL REFERENCES pipeline_definition(id) ON DELETE CASCADE,
    job_name                 VARCHAR(200) NOT NULL,
    job_type                 VARCHAR(30)  NOT NULL,
    execution_order          INTEGER      NOT NULL,
    config_json              TEXT,
    created_at               TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_pipeline_job_definition_id ON pipeline_job(pipeline_definition_id);

CREATE TABLE pipeline_job_dependency (
    job_id             BIGINT NOT NULL REFERENCES pipeline_job(id) ON DELETE CASCADE,
    depends_on_job_id  BIGINT NOT NULL REFERENCES pipeline_job(id) ON DELETE CASCADE,
    PRIMARY KEY (job_id, depends_on_job_id)
);
```

**양호한 점:**
- `ON DELETE CASCADE`: 정의 삭제 시 Job이 함께 삭제됨. 의존성 테이블도 CASCADE.
- 복합 PK `(job_id, depends_on_job_id)`: 중복 의존성 방지
- `definition_id` 인덱스: `findByDefinitionId` 쿼리 최적화

**이슈:**

| # | 심각도 | 내용 |
|---|--------|------|
| DB-1 | MINOR | `pipeline_job_dependency`에 `depends_on_job_id` 단독 인덱스가 없다. 역방향 조회("이 Job에 의존하는 Job 찾기")가 필요할 때 full scan이 발생한다. 현재는 이 쿼리가 없지만, DAG 확장 시 필요할 수 있다. |
| DB-2 | MINOR | `job_type VARCHAR(30)`: enum 문자열 저장이므로 적절하지만, CHECK 제약조건이 없어 잘못된 값이 들어갈 수 있다. 어플리케이션 레벨 검증에 의존. |

## V19: 기존 테이블 확장

```sql
ALTER TABLE pipeline_step ADD COLUMN job_id BIGINT REFERENCES pipeline_job(id);
ALTER TABLE pipeline_execution ADD COLUMN pipeline_definition_id BIGINT REFERENCES pipeline_definition(id);
```

**양호한 점:**
- 두 컬럼 모두 nullable: 기존 데이터에 영향 없음 (하위 호환)
- FK 참조로 무결성 보장

**이슈:**

| # | 심각도 | 내용 |
|---|--------|------|
| DB-3 | **MAJOR** | `pipeline_step.job_id`에 인덱스가 없다. `DagExecutionCoordinator`에서 `jobId → stepOrder` 매핑을 빈번하게 조회한다. 현재는 `findByExecutionId`로 전체 로드 후 메모리 매핑하지만, 직접 조회가 필요해지면 성능 문제가 된다. |
| DB-4 | **MAJOR** | `pipeline_execution.pipeline_definition_id`에 인덱스가 없다. `PipelineDefinitionService.delete()`에서 `findByPipelineDefinitionId(id)`를 호출하는데, 인덱스 없이 full scan이 발생한다. |

## 기존 데이터 영향 분석

| 기존 레코드 | Phase 2 후 | 영향 |
|------------|-----------|------|
| `pipeline_execution` (ticket 기반) | `pipeline_definition_id = NULL` | PipelineEngine이 null 체크로 순차 모드 분기 → 영향 없음 |
| `pipeline_step` (ticket 기반) | `job_id = NULL` | DAG coordinator는 `jobId != null`인 step만 처리 → 영향 없음 |
| 외래키 참조 | `preset_id`, `job_id`, `definition_id` 모두 nullable | 기존 INSERT 쿼리에 새 컬럼 미지정 시 NULL → 정상 |

**결론**: 하위 호환 전략이 올바르게 적용되었다. nullable 컬럼 + null 분기로 기존 흐름을 보호한다.

## CASCADE 전략 검증

```
pipeline_definition 삭제 시:
  → pipeline_job CASCADE 삭제
    → pipeline_job_dependency CASCADE 삭제
  → pipeline_execution.pipeline_definition_id는 CASCADE 아님 (FK만 설정)
  → pipeline_step.job_id는 CASCADE 아님
```

**주의**: `PipelineDefinitionService.delete()`에서 실행 중 체크 후 수동으로 dependency → job → definition 순서로 삭제한다. `ON DELETE CASCADE`가 있지만 명시적 삭제를 선택한 이유는 삭제 순서를 코드에서 제어하기 위함이다. CASCADE와 명시적 삭제가 중복되지만 안전한 방향이다.

## 퀴즈

**Q**: V19에서 `pipeline_step.job_id`에 `ON DELETE SET NULL`이 아닌 단순 FK를 건 이유는?

> Job 정의가 삭제되어도 실행 기록(Step)은 보존해야 한다. `SET NULL`은 Job 삭제 시 Step의 job_id를 NULL로 바꾸는데, 이미 기존 티켓 기반 Step도 job_id=NULL이므로 구분이 불가능해진다. 현재는 Job 삭제 전 definition을 통으로 삭제하므로 실행 이력의 step은 orphan FK가 된다. 장기적으로 `ON DELETE SET NULL`이 더 안전할 수 있다.
