-- 미들웨어 프리셋: 역할별 도구 조합을 묶는 단위
-- 파이프라인이 프리셋을 참조하면, 프리셋 수정 시 개별 파이프라인 변경 불필요

CREATE TABLE middleware_preset (
    id          BIGSERIAL    PRIMARY KEY,
    name        VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(500),
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- 프리셋 항목: 카테고리 → 도구 매핑 (프리셋당 카테고리 하나씩)
CREATE TABLE preset_entry (
    id        BIGSERIAL  PRIMARY KEY,
    preset_id BIGINT     NOT NULL REFERENCES middleware_preset(id) ON DELETE CASCADE,
    category  VARCHAR(30) NOT NULL,
    tool_id   BIGINT     NOT NULL REFERENCES support_tool(id),
    UNIQUE (preset_id, category)
);

CREATE INDEX idx_preset_entry_preset_id ON preset_entry(preset_id);
