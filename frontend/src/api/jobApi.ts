import { api } from './client';

export interface Job {
  id: number;
  jobName: string;
  jobType: string;
  presetId?: number;
  presetName?: string;
  configJson?: string;
  jenkinsScript?: string;
  jenkinsStatus?: string;
  createdAt: string;
}

export interface CreateJobRequest {
  jobName: string;
  jobType: string;
  presetId?: number;
  configJson?: string;
  jenkinsScript?: string;
}

export interface UpdateJobRequest extends CreateJobRequest {}

export const jobApi = {
  list: () => api.get<Job[]>('/jobs'),
  get: (id: number) => api.get<Job>(`/jobs/${id}`),
  create: (data: CreateJobRequest) => api.post<Job>('/jobs', data),
  update: (id: number, data: UpdateJobRequest) => api.put<Job>(`/jobs/${id}`, data),
  delete: (id: number) => api.delete(`/jobs/${id}`),
  execute: (id: number) => api.post<void>(`/jobs/${id}/execute`),
  retryProvision: (id: number) => api.post<void>(`/jobs/${id}/retry-provision`),
};
