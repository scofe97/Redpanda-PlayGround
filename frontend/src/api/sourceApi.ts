import { api } from './client';

export interface GitLabProject {
  id: number;
  name: string;
  name_with_namespace: string;
  web_url: string;
  default_branch: string;
}

export interface GitLabBranch {
  name: string;
  merged: boolean;
  isProtected: boolean;
}

export interface NexusMavenInfo {
  groupId: string;
  artifactId: string;
  version: string;
  extension: string;
}

export interface NexusAsset {
  downloadUrl: string;
  path: string;
  repository: string;
  maven2: NexusMavenInfo;
}

export const sourceApi = {
  getGitRepos: () => api.get<GitLabProject[]>('/sources/git/repos'),
  getGitBranches: (repoId: number) =>
    api.get<GitLabBranch[]>(`/sources/git/repos/${repoId}/branches`),
  searchNexusArtifacts: (groupId: string, artifactId: string) =>
    api.get<NexusAsset[]>('/sources/nexus/artifacts', { groupId, artifactId }),
  getRegistryImages: () => api.get<string[]>('/sources/registry/images'),
  getRegistryTags: (repo: string) =>
    api.get<string[]>(`/sources/registry/images/${repo}/tags`),
};
