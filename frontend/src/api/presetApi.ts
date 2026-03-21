import { api } from './client';

export interface PresetEntry {
  id: number;
  category: string;
  toolId: number;
  toolName: string;
  toolUrl: string;
  toolImplementation: string;
}

export interface MiddlewarePreset {
  id: number;
  name: string;
  description?: string;
  entries: PresetEntry[];
  createdAt: string;
  updatedAt?: string;
}

export interface PresetRequest {
  name: string;
  description?: string;
  entries: { category: string; toolId: number }[];
}

export const presetApi = {
  list: () => api.get<MiddlewarePreset[]>('/presets'),
  get: (id: number) => api.get<MiddlewarePreset>(`/presets/${id}`),
  create: (data: PresetRequest) => api.post<MiddlewarePreset>('/presets', data),
  update: (id: number, data: PresetRequest) => api.put<MiddlewarePreset>(`/presets/${id}`, data),
  delete: (id: number) => api.delete(`/presets/${id}`),
};
