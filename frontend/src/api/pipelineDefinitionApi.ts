import { api } from './client';

export interface ParameterSchema {
  name: string;
  type: string;
  defaultValue?: string | null;
  required: boolean;
}

export interface PipelineDefinition {
  id: number;
  name: string;
  description?: string;
  status: string;
  failurePolicy?: string;
  createdAt: string;
  updatedAt: string;
  jobs?: PipelineJobResponse[];
}

export interface PipelineJobResponse {
  id: number;
  jobName: string;
  jobType: string;
  configJson?: string;
  presetId?: number;
  presetName?: string;
  parameterSchemas?: ParameterSchema[];
  executionOrder: number;
  dependsOnJobIds: number[];
}

/** DagGraph 컴포넌트가 사용하는 UI 내부 표현 (이름 기반 의존성) */
export interface PipelineJobLocal {
  id?: number;
  jobName: string;
  jobType: string;
  executionOrder: number;
  dependsOn: string[];
}

export interface PipelineJobMappingRequest {
  jobId: number;
  executionOrder: number;
  dependsOnJobIds?: number[];
}

export interface CreatePipelineRequest {
  name: string;
  description?: string;
}

export interface UpdateMappingsRequest {
  mappings: PipelineJobMappingRequest[];
}

export interface PipelineExecutionResponse {
  executionId: string;
  status: string;
  startedAt?: string;
  completedAt?: string;
  errorMessage?: string;
  parameters?: Record<string, string> | null;
  jobExecutions?: JobExecutionResponse[];
}

export interface JobExecutionResponse {
  id: number;
  jobOrder: number;
  jobName: string;
  jobType: string;
  status: string;
  log?: string;
  startedAt?: string;
  completedAt?: string;
}

export const pipelineDefinitionApi = {
  list: () => api.get<PipelineDefinition[]>('/pipelines'),
  get: (id: number) => api.get<PipelineDefinition>(`/pipelines/${id}`),
  create: (data: CreatePipelineRequest) => api.post<PipelineDefinition>('/pipelines', data),
  updateMappings: (id: number, data: UpdateMappingsRequest) => api.put<PipelineDefinition>(`/pipelines/${id}/mappings`, data),
  execute: (id: number, params?: Record<string, string>) =>
    api.post<PipelineExecutionResponse>(`/pipelines/${id}/execute`, params ? { params } : undefined),
  delete: (id: number) => api.delete(`/pipelines/${id}`),
  getExecutions: (id: number) => api.get<PipelineExecutionResponse[]>(`/pipelines/${id}/executions`),
  getExecution: (executionId: string) => api.get<PipelineExecutionResponse>(`/pipelines/executions/${executionId}`),
};
