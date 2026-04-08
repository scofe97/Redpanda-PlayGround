package com.study.playground.operator.purpose.service;

import com.study.playground.common.dto.CommonErrorCode;
import com.study.playground.common.exception.BusinessException;
import com.study.playground.operator.purpose.domain.Purpose;
import com.study.playground.operator.purpose.domain.PurposeEntry;
import com.study.playground.operator.purpose.dto.PurposeRequest;
import com.study.playground.operator.purpose.dto.PurposeResponse;
import com.study.playground.operator.purpose.repository.PurposeRepository;
import com.study.playground.operator.supporttool.domain.SupportTool;
import com.study.playground.operator.supporttool.domain.ToolCategory;
import com.study.playground.operator.supporttool.repository.SupportToolRepository;
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
public class PurposeService {

    private final PurposeRepository purposeRepository;
    private final SupportToolRepository supportToolRepository;

    @Transactional(readOnly = true)
    public List<PurposeResponse> findAll() {
        var purposes = purposeRepository.findAllByOrderByName();
        var toolInfoMap = buildToolInfoMap(purposes);
        return purposes.stream()
                .map(p -> PurposeResponse.from(p, toolInfoMap))
                .toList();
    }

    @Transactional(readOnly = true)
    public PurposeResponse findById(Long id) {
        var purpose = getPurposeOrThrow(id);
        var toolInfoMap = buildToolInfoMap(List.of(purpose));
        return PurposeResponse.from(purpose, toolInfoMap);
    }

    /**
     * 목적을 생성한다.
     * 각 엔트리의 category와 toolId 유효성을 검증한 뒤, 목적 → 엔트리 순서로 저장한다.
     */
    @Transactional
    public PurposeResponse create(PurposeRequest request) {
        var purpose = new Purpose();
        purpose.setName(request.getName());
        purpose.setDescription(request.getDescription());
        purpose.setProjectId(request.getProjectId());

        addEntries(purpose, request.getEntries());

        purposeRepository.save(purpose);

        return findById(purpose.getId());
    }

    /**
     * 목적을 수정한다. 엔트리는 orphanRemoval로 자동 삭제 후 재추가한다.
     * UC-5의 핵심: 목적의 도구를 교체하면 다음 파이프라인 실행부터 새 도구를 사용한다.
     */
    @Transactional
    public PurposeResponse update(Long id, PurposeRequest request) {
        var purpose = getPurposeOrThrow(id);
        purpose.setName(request.getName());
        purpose.setDescription(request.getDescription());
        purpose.setProjectId(request.getProjectId());

        purpose.getEntries().clear();
        addEntries(purpose, request.getEntries());

        purposeRepository.save(purpose);

        return findById(id);
    }

    @Transactional
    public void delete(Long id) {
        getPurposeOrThrow(id);
        // purpose_entry는 CascadeType.ALL + orphanRemoval로 자동 삭제
        purposeRepository.deleteById(id);
    }

    /**
     * 목적에서 지정된 카테고리의 도구를 해석한다.
     * 파이프라인 실행 시 이 메서드를 호출하여 어떤 도구를 사용할지 결정한다.
     */
    @Transactional(readOnly = true)
    public SupportTool resolveToolByCategory(Long purposeId, ToolCategory category) {
        var purpose = getPurposeOrThrow(purposeId);
        var entry = purpose.getEntries().stream()
                .filter(e -> e.getCategory() == category)
                .findFirst()
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND,
                        "목적 '%s'에 %s 카테고리가 없습니다".formatted(purpose.getName(), category.name())));

        return getToolOrThrow(entry.getToolId(),
                "목적에 매핑된 도구를 찾을 수 없습니다: toolId=" + entry.getToolId());
    }

    /**
     * 목적의 도구 매핑을 카테고리 → SupportTool Map으로 반환한다.
     */
    @Transactional(readOnly = true)
    public Map<ToolCategory, SupportTool> resolveAllTools(Long purposeId) {
        var purpose = getPurposeOrThrow(purposeId);
        return purpose.getEntries().stream()
                .collect(Collectors.toMap(
                        PurposeEntry::getCategory
                        , entry -> getToolOrThrow(entry.getToolId(),
                                "도구를 찾을 수 없습니다: toolId=" + entry.getToolId())
                ));
    }

    // ── private helpers ──────────────────────────────────────────────

    private Purpose getPurposeOrThrow(Long id) {
        return purposeRepository.findWithEntriesById(id)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND,
                        "목적을 찾을 수 없습니다: " + id));
    }

    private SupportTool getToolOrThrow(Long toolId, String message) {
        return supportToolRepository.findById(toolId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, message));
    }

    private void addEntries(Purpose purpose, List<PurposeRequest.EntryRequest> entryRequests) {
        for (var req : entryRequests) {
            var category = parseCategory(req.getCategory());
            var tool = getToolOrThrow(req.getToolId(), "도구를 찾을 수 없습니다: " + req.getToolId());

            if (tool.getCategory() != category) {
                throw new BusinessException(CommonErrorCode.INVALID_INPUT,
                        "도구 '%s'의 카테고리(%s)가 요청한 카테고리(%s)와 일치하지 않습니다"
                                .formatted(tool.getName(), tool.getCategory(), category));
            }

            var entry = new PurposeEntry();
            entry.setPurpose(purpose);
            entry.setCategory(category);
            entry.setToolId(req.getToolId());
            purpose.getEntries().add(entry);
        }
    }

    /**
     * 목적 목록에서 참조하는 모든 toolId를 수집하여 한번에 조회한 뒤,
     * purpose 패키지 전용 ToolInfo로 변환한다.
     */
    private Map<Long, PurposeResponse.ToolInfo> buildToolInfoMap(List<Purpose> purposes) {
        var toolIds = purposes.stream()
                .flatMap(p -> p.getEntries().stream())
                .map(PurposeEntry::getToolId)
                .distinct()
                .toList();
        if (toolIds.isEmpty()) return Map.of();
        return supportToolRepository.findAllById(toolIds).stream()
                .collect(Collectors.toMap(
                        SupportTool::getId
                        , t -> new PurposeResponse.ToolInfo(
                                t.getName(), t.getUrl()
                                , t.getImplementation() != null ? t.getImplementation().name() : null)
                ));
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
