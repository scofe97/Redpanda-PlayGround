import { api } from './client';

export interface PipelineStep {
  id: number;
  stepOrder: number;
  stepType: string;
  stepName: string;
  status: string;
  log?: string;
  startedAt?: string;
  completedAt?: string;
}

export interface PipelineExecution {
  executionId: string;
  ticketId: number;
  status: string;
  steps: PipelineStep[];
  startedAt?: string;
  completedAt?: string;
  errorMessage?: string;
  trackingUrl?: string;
}

export const pipelineApi = {
  start: (ticketId: number) => api.post<PipelineExecution>(`/tickets/${ticketId}/pipeline/start`),
  getLatest: (ticketId: number) => api.get<PipelineExecution>(`/tickets/${ticketId}/pipeline`),
  getHistory: (ticketId: number) => api.get<PipelineExecution[]>(`/tickets/${ticketId}/pipeline/history`),
};
