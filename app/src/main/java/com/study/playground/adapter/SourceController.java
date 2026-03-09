package com.study.playground.adapter;

import com.study.playground.adapter.dto.GitLabBranch;
import com.study.playground.adapter.dto.GitLabProject;
import com.study.playground.adapter.dto.NexusAsset;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<List<GitLabProject>> getGitRepos() {
        return ResponseEntity.ok(gitLabAdapter.getProjects());
    }

    @GetMapping("/git/repos/{id}/branches")
    public ResponseEntity<List<GitLabBranch>> getRepoBranches(@PathVariable Long id) {
        return ResponseEntity.ok(gitLabAdapter.getBranches(id));
    }

    @GetMapping("/nexus/artifacts")
    public ResponseEntity<List<NexusAsset>> searchNexusArtifacts(
            @RequestParam String groupId,
            @RequestParam String artifactId) {
        return ResponseEntity.ok(nexusAdapter.searchComponents("maven-releases", groupId, artifactId, ""));
    }

    @GetMapping("/registry/images")
    public ResponseEntity<List<String>> listRegistryImages() {
        return ResponseEntity.ok(registryAdapter.listRepositories());
    }

    @GetMapping("/registry/images/{repo:.+}/tags")
    public ResponseEntity<List<String>> listImageTags(@PathVariable String repo) {
        return ResponseEntity.ok(registryAdapter.getTags(repo));
    }
}
