-- preset "default"(id=3)에 Jenkins(CI_CD_TOOL)과 Nexus(LIBRARY) 연결
-- K8s 전환 후 Nexus URL 업데이트 (Docker 28881 → K8s 31280)
UPDATE support_tool SET url = 'http://34.47.83.38:31280' WHERE id = 3 AND url != 'http://34.47.83.38:31280';

INSERT INTO middleware_preset (id, name, description)
VALUES (3, 'default', '기본 프리셋')
ON CONFLICT (id) DO NOTHING;

INSERT INTO preset_entry (preset_id, category, tool_id)
VALUES (3, 'CI_CD_TOOL', 1)
ON CONFLICT (preset_id, category) DO NOTHING;

INSERT INTO preset_entry (preset_id, category, tool_id)
VALUES (3, 'LIBRARY', 3)
ON CONFLICT (preset_id, category) DO NOTHING;
