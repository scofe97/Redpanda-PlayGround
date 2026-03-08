import { useQuery } from '@tanstack/react-query';
import { sourceApi } from '../api/sourceApi';

export function useGitRepos() {
  return useQuery({
    queryKey: ['sources', 'git', 'repos'],
    queryFn: () => sourceApi.getGitRepos(),
  });
}

export function useGitBranches(repoId: number) {
  return useQuery({
    queryKey: ['sources', 'git', 'branches', repoId],
    queryFn: () => sourceApi.getGitBranches(repoId),
    enabled: repoId > 0,
  });
}

export function useNexusArtifacts(groupId: string, artifactId: string, enabled: boolean) {
  return useQuery({
    queryKey: ['sources', 'nexus', 'artifacts', groupId, artifactId],
    queryFn: () => sourceApi.searchNexusArtifacts(groupId, artifactId),
    enabled,
  });
}

