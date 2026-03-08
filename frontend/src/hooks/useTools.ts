import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { toolApi, SupportToolRequest } from '../api/toolApi';

export function useToolList() {
  return useQuery({
    queryKey: ['tools'],
    queryFn: () => toolApi.list(),
  });
}

export function useTool(id: number) {
  return useQuery({
    queryKey: ['tool', id],
    queryFn: () => toolApi.get(id),
    enabled: id > 0,
  });
}

export function useCreateTool() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: SupportToolRequest) => toolApi.create(data),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['tools'] }),
  });
}

export function useUpdateTool() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: SupportToolRequest }) => toolApi.update(id, data),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['tools'] }),
  });
}

export function useDeleteTool() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => toolApi.delete(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['tools'] }),
  });
}

export function useTestTool() {
  return useMutation({
    mutationFn: (id: number) => toolApi.test(id),
  });
}
