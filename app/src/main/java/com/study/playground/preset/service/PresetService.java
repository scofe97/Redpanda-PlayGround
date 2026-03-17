package com.study.playground.preset.service;

import com.study.playground.common.dto.CommonErrorCode;
import com.study.playground.common.exception.BusinessException;
import com.study.playground.preset.domain.MiddlewarePreset;
import com.study.playground.preset.domain.PresetEntry;
import com.study.playground.preset.dto.PresetRequest;
import com.study.playground.preset.dto.PresetResponse;
import com.study.playground.preset.mapper.MiddlewarePresetMapper;
import com.study.playground.preset.mapper.PresetEntryMapper;
import com.study.playground.supporttool.domain.SupportTool;
import com.study.playground.supporttool.domain.ToolCategory;
import com.study.playground.supporttool.mapper.SupportToolMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PresetService {

    private final MiddlewarePresetMapper presetMapper;
    private final PresetEntryMapper entryMapper;
    private final SupportToolMapper supportToolMapper;

    @Transactional(readOnly = true)
    public List<PresetResponse> findAll() {
        return presetMapper.findAll().stream()
                .map(PresetResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public PresetResponse findById(Long id) {
        return PresetResponse.from(getPresetOrThrow(id));
    }

    /**
     * 프리셋을 생성한다.
     * 각 엔트리의 category와 toolId 유효성을 검증한 뒤, 프리셋 → 엔트리 순서로 저장한다.
     */
    @Transactional
    public PresetResponse create(PresetRequest request) {
        var preset = new MiddlewarePreset();
        preset.setName(request.getName());
        preset.setDescription(request.getDescription());
        presetMapper.insert(preset);

        saveEntries(preset.getId(), request.getEntries());

        return findById(preset.getId());
    }

    /**
     * 프리셋을 수정한다. 엔트리는 delete-then-insert 방식으로 교체한다.
     * UC-5의 핵심: 프리셋의 도구를 교체하면 다음 파이프라인 실행부터 새 도구를 사용한다.
     */
    @Transactional
    public PresetResponse update(Long id, PresetRequest request) {
        var preset = getPresetOrThrow(id);
        preset.setName(request.getName());
        preset.setDescription(request.getDescription());
        presetMapper.update(preset);

        entryMapper.deleteByPresetId(id);
        saveEntries(id, request.getEntries());

        return findById(id);
    }

    @Transactional
    public void delete(Long id) {
        getPresetOrThrow(id);
        // preset_entry는 ON DELETE CASCADE로 자동 삭제
        presetMapper.deleteById(id);
    }

    /**
     * 프리셋에서 지정된 카테고리의 도구를 해석한다.
     * 파이프라인 실행 시 이 메서드를 호출하여 어떤 도구를 사용할지 결정한다.
     */
    @Transactional(readOnly = true)
    public SupportTool resolveToolByCategory(Long presetId, ToolCategory category) {
        var preset = getPresetOrThrow(presetId);
        var entry = preset.getEntries().stream()
                .filter(e -> e.getCategory() == category)
                .findFirst()
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND,
                        "프리셋 '%s'에 %s 카테고리가 없습니다".formatted(preset.getName(), category.name())));

        return getToolOrThrow(entry.getToolId(),
                "프리셋에 매핑된 도구를 찾을 수 없습니다: toolId=" + entry.getToolId());
    }

    /**
     * 프리셋의 도구 매핑을 카테고리 → SupportTool Map으로 반환한다.
     */
    @Transactional(readOnly = true)
    public Map<ToolCategory, SupportTool> resolveAllTools(Long presetId) {
        var preset = getPresetOrThrow(presetId);
        return preset.getEntries().stream()
                .collect(Collectors.toMap(
                        PresetEntry::getCategory
                        , entry -> getToolOrThrow(entry.getToolId(),
                                "도구를 찾을 수 없습니다: toolId=" + entry.getToolId())
                ));
    }

    // ── private helpers ──────────────────────────────────────────────

    private MiddlewarePreset getPresetOrThrow(Long id) {
        var preset = presetMapper.findById(id);
        if (preset == null) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND,
                    "프리셋을 찾을 수 없습니다: " + id);
        }
        return preset;
    }

    private SupportTool getToolOrThrow(Long toolId, String message) {
        var tool = supportToolMapper.findById(toolId);
        if (tool == null) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, message);
        }
        return tool;
    }

    private void saveEntries(Long presetId, List<PresetRequest.EntryRequest> entryRequests) {
        for (var req : entryRequests) {
            var category = parseCategory(req.getCategory());
            var tool = getToolOrThrow(req.getToolId(), "도구를 찾을 수 없습니다: " + req.getToolId());

            if (tool.getCategory() != category) {
                throw new BusinessException(CommonErrorCode.INVALID_INPUT,
                        "도구 '%s'의 카테고리(%s)가 요청한 카테고리(%s)와 일치하지 않습니다"
                                .formatted(tool.getName(), tool.getCategory(), category));
            }

            var entry = new PresetEntry();
            entry.setPresetId(presetId);
            entry.setCategory(category);
            entry.setToolId(req.getToolId());
            entryMapper.insert(entry);
        }
    }

    private ToolCategory parseCategory(String category) {
        try {
            return ToolCategory.valueOf(category);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT,
                    "유효하지 않은 카테고리입니다: " + category);
        }
    }
}
