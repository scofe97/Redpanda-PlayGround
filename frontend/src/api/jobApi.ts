import { api } from './client';

import type { ParameterSchema, PipelineExecutionResponse } from './pipelineDefinitionApi';

export interface Job {
  id: number;
  jobName: string;
  jobType: string;
  purposeId?: number;
  purposeName?: string;
  configJson?: string;
  jenkinsScript?: string;
  jenkinsStatus?: string;
  parameterSchemas?: ParameterSchema[];
  deployMode?: 'IMPORT' | 'BUILD_REQUIRED';
  requiredBuildJobId?: number;
  requiredBuildJobName?: string;
  createdAt: string;
}

export interface CreateJobRequest {
  jobName: string;
  jobType: string;
  purposeId?: number;
  configJson?: string;
  jenkinsScript?: string;
  parameterSchemaJson?: string;
  deployMode?: 'IMPORT' | 'BUILD_REQUIRED';
  requiredBuildJobId?: number;
}

export interface UpdateJobRequest extends CreateJobRequest {}

export const jobApi = {
  list: () => api.get<Job[]>('/jobs'),
  get: (id: number) => api.get<Job>(`/jobs/${id}`),
  create: (data: CreateJobRequest) => api.post<Job>('/jobs', data),
  update: (id: number, data: UpdateJobRequest) => api.put<Job>(`/jobs/${id}`, data),
  delete: (id: number) => api.delete(`/jobs/${id}`),
  execute: (id: number, params?: Record<string, string>) =>
    api.post<void>(`/jobs/${id}/execute`, params ? { params } : undefined),
  retryProvision: (id: number) => api.post<void>(`/jobs/${id}/retry-provision`),
  getExecutions: (id: number) => api.get<PipelineExecutionResponse[]>(`/jobs/${id}/executions`),
};
