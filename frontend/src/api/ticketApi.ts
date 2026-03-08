import { api } from './client';

export interface TicketSource {
  id?: number;
  sourceType: string;
  repoUrl?: string;
  branch?: string;
  artifactCoordinate?: string;
  imageName?: string;
}

export interface Ticket {
  id: number;
  name: string;
  description?: string;
  status: string;
  sources?: TicketSource[];
  createdAt: string;
  updatedAt: string;
}

export interface TicketListItem {
  id: number;
  name: string;
  status: string;
  createdAt: string;
  updatedAt: string;
}

export interface CreateTicketRequest {
  name: string;
  description?: string;
  sources: TicketSource[];
}

export const ticketApi = {
  list: () => api.get<TicketListItem[]>('/tickets'),
  get: (id: number) => api.get<Ticket>(`/tickets/${id}`),
  create: (data: CreateTicketRequest) => api.post<Ticket>('/tickets', data),
  update: (id: number, data: CreateTicketRequest) => api.put<Ticket>(`/tickets/${id}`, data),
  delete: (id: number) => api.delete(`/tickets/${id}`),
};
