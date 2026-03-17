import { api } from './client';

export interface SupportTool {
  id: number;
  category: string;
  implementation: string;
  name: string;
  url: string;
  authType?: string;
  username?: string;
  hasCredential: boolean;
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface SupportToolRequest {
  category: string;
  implementation: string;
  name: string;
  url: string;
  authType?: string;
  username?: string;
  credential?: string;
  active: boolean;
}

export interface TestResult {
  reachable: boolean;
}

export const toolApi = {
  list: () => api.get<SupportTool[]>('/tools'),
  get: (id: number) => api.get<SupportTool>(`/tools/${id}`),
  create: (data: SupportToolRequest) => api.post<SupportTool>('/tools', data),
  update: (id: number, data: SupportToolRequest) => api.put<SupportTool>(`/tools/${id}`, data),
  delete: (id: number) => api.delete(`/tools/${id}`),
  test: (id: number) => api.post<TestResult>(`/tools/${id}/test`),
};
