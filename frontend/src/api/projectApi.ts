import { api } from './client';

export interface Project {
  id: number;
  name: string;
  description?: string;
  createdAt: string;
  updatedAt?: string;
}

export interface ProjectRequest {
  name: string;
  description?: string;
}

export const projectApi = {
  list: () => api.get<Project[]>('/projects'),
  get: (id: number) => api.get<Project>(`/projects/${id}`),
  create: (data: ProjectRequest) => api.post<Project>('/projects', data),
  update: (id: number, data: ProjectRequest) => api.put<Project>(`/projects/${id}`, data),
  delete: (id: number) => api.delete(`/projects/${id}`),
};
