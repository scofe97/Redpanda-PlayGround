import { api } from './client';

import type { ParameterSchema } from './pipelineDefinitionApi';

export interface Job {
  id: number;
  jobName: string;
  jobType: string;
  presetId?: number;
  presetName?: string;
  configJson?: string;
  jenkinsScript?: string;
  jenkinsStatus?: string;
  parameterSchemas?: ParameterSchema[];
  createdAt: string;
}

export interface CreateJobRequest {
  jobName: string;
  jobType: string;
  presetId?: number;
  configJson?: string;
  jenkinsScript?: string;
  parameterSchemaJson?: string;
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
};
