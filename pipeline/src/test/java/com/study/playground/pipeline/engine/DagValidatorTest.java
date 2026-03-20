package com.study.playground.pipeline.engine;

import com.study.playground.pipeline.domain.PipelineJob;
import com.study.playground.pipeline.domain.PipelineJobType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DagValidatorTest {

    private DagValidator validator;

    @BeforeEach
    void setUp() {
        validator = new DagValidator();
    }

    private PipelineJob createJob(Long id, String name, PipelineJobType type, List<Long> dependsOn) {
        var job = new PipelineJob();
        job.setId(id);
        job.setJobName(name);
        job.setJobType(type);
        job.setExecutionOrder(id.intValue());
        job.setDependsOnJobIds(dependsOn);
        return job;
    }

    // ── 유효한 DAG ──────────────────────────────────────────────────

    @Test
    @DisplayName("빈 Job 목록은 유효하다")
    void 빈목록_유효() {
        validator.validate(List.of());
    }

    @Test
    @DisplayName("단일 루트 Job은 유효하다")
    void 단일루트_유효() {
        var job = createJob(1L, "Build", PipelineJobType.BUILD, List.of());
        validator.validate(List.of(job));
    }

    @Test
    @DisplayName("선형 DAG (A→B→C)는 유효하다")
    void 선형DAG_유효() {
        var a = createJob(1L, "Clone", PipelineJobType.BUILD, List.of());
        var b = createJob(2L, "Build", PipelineJobType.BUILD, List.of(1L));
        var c = createJob(3L, "Deploy", PipelineJobType.DEPLOY, List.of(2L));

        validator.validate(List.of(a, b, c));
    }

    @Test
    @DisplayName("다이아몬드 DAG (A→B,C→D)는 유효하다")
    void 다이아몬드DAG_유효() {
        //   A
        //  / \
        // B   C
        //  \ /
        //   D
        var a = createJob(1L, "A", PipelineJobType.BUILD, List.of());
        var b = createJob(2L, "B", PipelineJobType.BUILD, List.of(1L));
        var c = createJob(3L, "C", PipelineJobType.DEPLOY, List.of(1L));
        var d = createJob(4L, "D", PipelineJobType.DEPLOY, List.of(2L, 3L));

        validator.validate(List.of(a, b, c, d));
    }

    @Test
    @DisplayName("E2E 시나리오: 6 Job × 3 Round DAG는 유효하다")
    void E2E_6Job_3Round_유효() {
        // Round 1: Job1, Job2 (루트, 병렬)
        // Round 2: Job3→Job4→Job5 (순차, Job1,2에 의존)
        // Round 3: Job6, Job7 (Job5에 의존, 병렬)
        var job1 = createJob(1L, "BUILD git-A", PipelineJobType.BUILD, List.of());
        var job2 = createJob(2L, "BUILD git-B", PipelineJobType.BUILD, List.of());
        var job3 = createJob(3L, "DOWNLOAD", PipelineJobType.ARTIFACT_DOWNLOAD, List.of(1L, 2L));
        var job4 = createJob(4L, "PULL", PipelineJobType.IMAGE_PULL, List.of(3L));
        var job5 = createJob(5L, "DEPLOY-staging", PipelineJobType.DEPLOY, List.of(4L));
        var job6 = createJob(6L, "DEPLOY prod-A", PipelineJobType.DEPLOY, List.of(5L));
        var job7 = createJob(7L, "DEPLOY prod-B", PipelineJobType.DEPLOY, List.of(5L));

        validator.validate(List.of(job1, job2, job3, job4, job5, job6, job7));
    }

    // ── 순환 탐지 ──────────────────────────────────────────────────

    @Test
    @DisplayName("자기 자신에 의존하는 Job은 순환으로 거부된다")
    void 자기참조_순환_거부() {
        var a = createJob(1L, "Self-dep", PipelineJobType.BUILD, List.of(1L));

        assertThatThrownBy(() -> validator.validate(List.of(a)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("순환");
    }

    @Test
    @DisplayName("A→B→A 순환은 거부된다")
    void 양방향순환_거부() {
        var a = createJob(1L, "A", PipelineJobType.BUILD, List.of(2L));
        var b = createJob(2L, "B", PipelineJobType.BUILD, List.of(1L));

        assertThatThrownBy(() -> validator.validate(List.of(a, b)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("루트 Job이 없습니다");
    }

    @Test
    @DisplayName("A→B→C→A 3노드 순환은 거부된다")
    void 삼각순환_거부() {
        var a = createJob(1L, "A", PipelineJobType.BUILD, List.of(3L));
        var b = createJob(2L, "B", PipelineJobType.BUILD, List.of(1L));
        var c = createJob(3L, "C", PipelineJobType.BUILD, List.of(2L));

        assertThatThrownBy(() -> validator.validate(List.of(a, b, c)))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── 연결성 검증 ──────────────────────────────────────────────────

    @Test
    @DisplayName("단절 그래프는 거부된다")
    void 단절그래프_거부() {
        // A→B (연결됨), C (고립)
        var a = createJob(1L, "A", PipelineJobType.BUILD, List.of());
        var b = createJob(2L, "B", PipelineJobType.BUILD, List.of(1L));
        var c = createJob(3L, "C", PipelineJobType.BUILD, List.of());

        assertThatThrownBy(() -> validator.validate(List.of(a, b, c)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("단절");
    }

    // ── 존재하지 않는 의존성 ──────────────────────────────────────────

    @Test
    @DisplayName("존재하지 않는 Job ID에 의존하면 거부된다")
    void 미존재_의존성_거부() {
        var a = createJob(1L, "A", PipelineJobType.BUILD, List.of(999L));

        assertThatThrownBy(() -> validator.validate(List.of(a)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("존재하지 않는 Job ID");
    }

    // ── 위상 정렬 ──────────────────────────────────────────────────

    @Test
    @DisplayName("위상 정렬: 선형 DAG는 순서대로 반환된다")
    void 위상정렬_선형() {
        var a = createJob(1L, "A", PipelineJobType.BUILD, List.of());
        var b = createJob(2L, "B", PipelineJobType.BUILD, List.of(1L));
        var c = createJob(3L, "C", PipelineJobType.DEPLOY, List.of(2L));

        List<Long> sorted = validator.topologicalSort(List.of(a, b, c));
        assertThat(sorted).containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("위상 정렬: 다이아몬드 DAG에서 D는 B,C 뒤에 온다")
    void 위상정렬_다이아몬드() {
        var a = createJob(1L, "A", PipelineJobType.BUILD, List.of());
        var b = createJob(2L, "B", PipelineJobType.BUILD, List.of(1L));
        var c = createJob(3L, "C", PipelineJobType.DEPLOY, List.of(1L));
        var d = createJob(4L, "D", PipelineJobType.DEPLOY, List.of(2L, 3L));

        List<Long> sorted = validator.topologicalSort(List.of(a, b, c, d));

        // A는 첫 번째, D는 마지막, B/C는 중간 (순서 무관)
        assertThat(sorted.get(0)).isEqualTo(1L);
        assertThat(sorted.get(3)).isEqualTo(4L);
        assertThat(sorted.subList(1, 3)).containsExactlyInAnyOrder(2L, 3L);
    }
}
