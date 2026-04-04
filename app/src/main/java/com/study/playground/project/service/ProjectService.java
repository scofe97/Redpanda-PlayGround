package com.study.playground.project.service;

import com.study.playground.common.dto.CommonErrorCode;
import com.study.playground.common.exception.BusinessException;
import com.study.playground.project.domain.Project;
import com.study.playground.project.dto.ProjectRequest;
import com.study.playground.project.dto.ProjectResponse;
import com.study.playground.project.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;

    @Transactional(readOnly = true)
    public List<ProjectResponse> findAll() {
        return projectRepository.findAllByOrderByName().stream()
                .map(ProjectResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse findById(Long id) {
        return ProjectResponse.from(getProjectOrThrow(id));
    }

    @Transactional
    public ProjectResponse create(ProjectRequest request) {
        var project = new Project();
        project.setName(request.getName());
        project.setDescription(request.getDescription());
        projectRepository.save(project);
        return ProjectResponse.from(project);
    }

    @Transactional
    public ProjectResponse update(Long id, ProjectRequest request) {
        var project = getProjectOrThrow(id);
        project.setName(request.getName());
        project.setDescription(request.getDescription());
        projectRepository.save(project);
        return ProjectResponse.from(project);
    }

    @Transactional
    public void delete(Long id) {
        getProjectOrThrow(id);
        projectRepository.deleteById(id);
    }

    // ── private helpers ──────────────────────────────────────────────

    private Project getProjectOrThrow(Long id) {
        return projectRepository.findById(id)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND,
                        "프로젝트를 찾을 수 없습니다: " + id));
    }
}
