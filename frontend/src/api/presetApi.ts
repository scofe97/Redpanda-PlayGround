import { api } from './client';

export interface MiddlewarePreset {
  id: number;
  name: string;
  description?: string;
}

export const presetApi = {
  list: () => api.get<MiddlewarePreset[]>('/presets'),
};
