package com.study.playground.pipeline.reconciler;

import com.study.playground.pipeline.adapter.JenkinsAdapter;
import com.study.playground.pipeline.domain.PipelineJob;
import com.study.playground.pipeline.mapper.JobMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Jenkins와 DB 간 드리프트를 주기적으로 보정하는 Reconciliation Loop.
 *
 * <p>ArgoCD/Flux의 GitOps 패턴을 차용한다. DB가 desired state, Jenkins가 actual state이며,
 * 주기적으로 두 상태를 비교하여 수렴시킨다.</p>
 *
 * <p>보정 동작:
 * <ul>
 *   <li>DB에 ACTIVE인데 Jenkins에 없음 → Jenkins에 재생성</li>
 *   <li>스크립트 해시 불일치 → Jenkins 업데이트</li>
 *   <li>Jenkins에만 있음 → 무시 (수동 관리 Job일 수 있음)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JenkinsReconciler {

    private final JenkinsAdapter jenkinsAdapter;
    private final JobMapper jobMapper;

    /**
     * 60초 주기로 desired state(DB)와 actual state(Jenkins)를 비교한다.
     */
    // TODO: Reconciler가 buildConfigXml의 parameters를 포함하지 않는 문제 해결 후 재활성화
    // @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void reconcile() {
        if (!jenkinsAdapter.isAvailable()) {
            log.debug("Jenkins 미연결 — reconciliation 건너뜀");
            return;
        }

        List<PipelineJob> activeJobs = jobMapper.findByJenkinsStatus("ACTIVE");
        if (activeJobs.isEmpty()) {
            return;
        }

        // 타입별로 그룹화하여 폴더별 Jenkins Job 목록 조회
        Map<String, Set<String>> jenkinsJobsByFolder = new HashMap<>();
        for (PipelineJob job : activeJobs) {
            String folder = job.getJobType().toFolderName();
            jenkinsJobsByFolder.computeIfAbsent(folder
                    , f -> jenkinsAdapter.listJobNamesInFolder(f));
        }

        int created = 0;

        for (PipelineJob job : activeJobs) {
            String folder = job.getJobType().toFolderName();
            String jenkinsJobName = "playground-job-%d".formatted(job.getId());
            String script = job.getJenkinsScript();

            if (script == null || script.isBlank()) {
                continue;
            }

            Set<String> folderJobs = jenkinsJobsByFolder.getOrDefault(folder, Set.of());
            if (!folderJobs.contains(jenkinsJobName)) {
                // DB에 ACTIVE인데 Jenkins 폴더에 없음 → 재생성
                try {
                    jenkinsAdapter.upsertPipelineJob(folder, jenkinsJobName, script);
                    created++;
                    log.info("Reconciler: Jenkins Job 재생성 완료: {}/{}", folder, jenkinsJobName);
                } catch (Exception e) {
                    log.warn("Reconciler: Jenkins Job 재생성 실패: {}/{}", folder, jenkinsJobName, e);
                }
            }
        }

        if (created > 0) {
            log.info("Reconciliation 완료: created={}", created);
        }
    }
}
