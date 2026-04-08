-- pipeline_job_execution.job_id: DagExecutionCoordinator에서 jobId 기반 조회에 사용
CREATE INDEX idx_job_execution_job_id ON pipeline_job_execution(job_id);

-- pipeline_execution.pipeline_definition_id: PipelineDefinitionService.delete()에서 사용
CREATE INDEX idx_execution_definition_id ON pipeline_execution(pipeline_definition_id);

-- pipeline_job_dependency.depends_on_job_id: 역방향 조회("이 Job에 의존하는 Job 찾기")용
CREATE INDEX idx_job_dependency_depends_on ON pipeline_job_dependency(depends_on_job_id);
