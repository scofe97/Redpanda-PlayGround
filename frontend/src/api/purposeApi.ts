import { api } from './client';

export interface PurposeEntry {
  id: number;
  category: string;
  toolId: number;
  toolName: string;
  toolUrl: string;
  toolImplementation: string;
}

export interface Purpose {
  id: number;
  name: string;
  description?: string;
  projectId?: number;
  entries: PurposeEntry[];
  createdAt: string;
  updatedAt?: string;
}

export interface PurposeRequest {
  name: string;
  description?: string;
  entries: { category: string; toolId: number }[];
}

export const purposeApi = {
  list: () => api.get<Purpose[]>('/purposes'),
  get: (id: number) => api.get<Purpose>(`/purposes/${id}`),
  create: (data: PurposeRequest) => api.post<Purpose>('/purposes', data),
  update: (id: number, data: PurposeRequest) => api.put<Purpose>(`/purposes/${id}`, data),
  delete: (id: number) => api.delete(`/purposes/${id}`),
};
