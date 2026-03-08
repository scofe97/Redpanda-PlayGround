import { api } from './client';

export interface SupportTool {
  id: number;
  toolType: string;
  name: string;
  url: string;
  username?: string;
  credential?: string;
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface SupportToolRequest {
  toolType: string;
  name: string;
  url: string;
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
