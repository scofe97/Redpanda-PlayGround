import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { nexusApi, NexusUploadRequest } from '../api/nexusApi';

export function useNexusUpload() {
  const [progress, setProgress] = useState(0);

  const mutation = useMutation({
    mutationFn: (req: NexusUploadRequest) =>
      nexusApi.upload(req, (pct) => setProgress(pct)),
    onSettled: () => setProgress(0),
  });

  return { ...mutation, progress };
}
