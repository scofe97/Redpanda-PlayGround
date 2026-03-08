package com.study.playground.adapter;

import com.study.playground.adapter.dto.GitLabBranch;
import com.study.playground.adapter.dto.GitLabProject;
import com.study.playground.adapter.dto.NexusAsset;
import com.study.playground.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sources")
@RequiredArgsConstructor
public class SourceController {

    private final GitLabAdapter gitLabAdapter;
    private final NexusAdapter nexusAdapter;
    private final RegistryAdapter registryAdapter;

    @GetMapping("/git/repos")
    public ApiResponse<List<GitLabProject>> getGitRepos() {
        return ApiResponse.success(gitLabAdapter.getProjects());
    }

    @GetMapping("/git/repos/{id}/branches")
    public ApiResponse<List<GitLabBranch>> getRepoBranches(@PathVariable Long id) {
        return ApiResponse.success(gitLabAdapter.getBranches(id));
    }

    @GetMapping("/nexus/artifacts")
    public ApiResponse<List<NexusAsset>> searchNexusArtifacts(
            @RequestParam String groupId,
            @RequestParam String artifactId) {
        return ApiResponse.success(nexusAdapter.searchComponents("maven-releases", groupId, artifactId, ""));
    }

    @GetMapping("/registry/images")
    public ApiResponse<List<String>> listRegistryImages() {
        return ApiResponse.success(registryAdapter.listRepositories());
    }

    @GetMapping("/registry/images/{repo:.+}/tags")
    public ApiResponse<List<String>> listImageTags(@PathVariable String repo) {
        return ApiResponse.success(registryAdapter.getTags(repo));
    }
}
