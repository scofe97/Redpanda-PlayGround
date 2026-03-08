import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { ticketApi, CreateTicketRequest } from '../api/ticketApi';

export function useTicketList() {
  return useQuery({
    queryKey: ['tickets'],
    queryFn: () => ticketApi.list(),
  });
}

export function useTicket(id: number) {
  return useQuery({
    queryKey: ['ticket', id],
    queryFn: () => ticketApi.get(id),
    enabled: id > 0,
  });
}

export function useCreateTicket() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: CreateTicketRequest) => ticketApi.create(data),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['tickets'] }),
  });
}

export function useDeleteTicket() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => ticketApi.delete(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['tickets'] }),
  });
}
