import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { pipelineApi } from '../api/pipelineApi';

export function usePipelineLatest(ticketId: number) {
  return useQuery({
    queryKey: ['pipeline', ticketId],
    queryFn: () => pipelineApi.getLatest(ticketId),
    enabled: ticketId > 0,
    retry: false,
  });
}

export function usePipelineHistory(ticketId: number) {
  return useQuery({
    queryKey: ['pipeline-history', ticketId],
    queryFn: () => pipelineApi.getHistory(ticketId),
    enabled: ticketId > 0,
  });
}

export function useStartPipeline() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (ticketId: number) => pipelineApi.start(ticketId),
    onSuccess: (_, ticketId) => {
      queryClient.invalidateQueries({ queryKey: ['pipeline', ticketId] });
      queryClient.invalidateQueries({ queryKey: ['ticket', ticketId] });
    },
  });
}
