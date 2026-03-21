import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { pipelineDefinitionApi, CreatePipelineRequest, UpdateMappingsRequest } from '../api/pipelineDefinitionApi';
import { presetApi, PresetRequest } from '../api/presetApi';

export function usePipelineDefinitionList() {
  return useQuery({
    queryKey: ['pipeline-definitions'],
    queryFn: () => pipelineDefinitionApi.list(),
  });
}

export function usePipelineDefinition(id: number) {
  return useQuery({
    queryKey: ['pipeline-definition', id],
    queryFn: () => pipelineDefinitionApi.get(id),
    enabled: id > 0,
  });
}

export function usePresetList() {
  return useQuery({
    queryKey: ['presets'],
    queryFn: () => presetApi.list(),
  });
}

export function usePreset(id: number) {
  return useQuery({
    queryKey: ['preset', id],
    queryFn: () => presetApi.get(id),
    enabled: id > 0,
  });
}

export function useCreatePreset() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: PresetRequest) => presetApi.create(data),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['presets'] }),
  });
}

export function useUpdatePreset() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: PresetRequest }) => presetApi.update(id, data),
    onSuccess: (_, { id }) => {
      queryClient.invalidateQueries({ queryKey: ['preset', id] });
      queryClient.invalidateQueries({ queryKey: ['presets'] });
    },
  });
}

export function useDeletePreset() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => presetApi.delete(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['presets'] }),
  });
}

export function useCreatePipelineDefinition() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: CreatePipelineRequest) => pipelineDefinitionApi.create(data),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['pipeline-definitions'] }),
  });
}

export function useUpdatePipelineMappings() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: UpdateMappingsRequest }) => pipelineDefinitionApi.updateMappings(id, data),
    onSuccess: (_, { id }) => {
      queryClient.invalidateQueries({ queryKey: ['pipeline-definition', id] });
      queryClient.invalidateQueries({ queryKey: ['pipeline-definitions'] });
    },
  });
}

export function useExecutePipeline() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, params }: { id: number; params?: Record<string, string> }) =>
      pipelineDefinitionApi.execute(id, params),
    onSuccess: (_, { id }) => {
      queryClient.invalidateQueries({ queryKey: ['pipeline-definition', id] });
      queryClient.invalidateQueries({ queryKey: ['pipeline-executions', id] });
    },
  });
}

export function useDeletePipelineDefinition() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => pipelineDefinitionApi.delete(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['pipeline-definitions'] }),
  });
}

export function usePipelineExecutions(pipelineId: number) {
  return useQuery({
    queryKey: ['pipeline-executions', pipelineId],
    queryFn: () => pipelineDefinitionApi.getExecutions(pipelineId),
    enabled: pipelineId > 0,
    refetchInterval: 5000,
  });
}
