import { api } from './client';

export interface NexusUploadRequest {
  repository: string;
  groupId: string;
  artifactId: string;
  version: string;
  packaging: string;
  file: File;
}

export interface NexusUploadResult {
  success: boolean;
  deployedUrl: string;
}

export const nexusApi = {
  upload: (req: NexusUploadRequest, onProgress?: (pct: number) => void) => {
    const formData = new FormData();
    formData.append('repository', req.repository);
    formData.append('groupId', req.groupId);
    formData.append('artifactId', req.artifactId);
    formData.append('version', req.version);
    formData.append('packaging', req.packaging);
    formData.append('file', req.file);
    return api.upload<NexusUploadResult>('/sources/nexus/upload', formData, onProgress);
  },
};
