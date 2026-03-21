import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { jobApi, CreateJobRequest, UpdateJobRequest } from '../api/jobApi';

export function useJobList() {
  return useQuery({
    queryKey: ['jobs'],
    queryFn: () => jobApi.list(),
  });
}

export function useJob(id: number) {
  return useQuery({
    queryKey: ['job', id],
    queryFn: () => jobApi.get(id),
    enabled: id > 0,
  });
}

export function useCreateJob() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: CreateJobRequest) => jobApi.create(data),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['jobs'] }),
  });
}

export function useUpdateJob() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: UpdateJobRequest }) => jobApi.update(id, data),
    onSuccess: (_, { id }) => {
      queryClient.invalidateQueries({ queryKey: ['jobs'] });
      queryClient.invalidateQueries({ queryKey: ['job', id] });
    },
  });
}

export function useDeleteJob() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => jobApi.delete(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['jobs'] }),
  });
}

export function useExecuteJob() {
  return useMutation({
    mutationFn: ({ id, params }: { id: number; params?: Record<string, string> }) =>
      jobApi.execute(id, params),
  });
}

export function useRetryJenkinsProvision() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => jobApi.retryProvision(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['jobs'] }),
  });
}
