const BASE_URL = '/api';

interface ApiResponse<T> {
  data: T | null;
  error: { code: string; message: string } | null;
}

export class ApiError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly status: number,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE_URL}${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });

  if (res.status === 204) return undefined as T;

  if (!res.ok) {
    let errorMessage = `HTTP ${res.status}`;
    let errorCode = 'UNKNOWN';
    try {
      const body: ApiResponse<unknown> = await res.json();
      if (body.error) {
        errorMessage = body.error.message;
        errorCode = body.error.code;
      }
    } catch {
      // response is not JSON
    }
    throw new ApiError(errorCode, errorMessage, res.status);
  }

  const body: ApiResponse<T> = await res.json();
  if (body.error) throw new ApiError(body.error.code, body.error.message, res.status);
  return body.data as T;
}

export const api = {
  get: <T>(path: string, params?: Record<string, string>) => {
    if (params) {
      const query = new URLSearchParams(params).toString();
      return request<T>(`${path}?${query}`);
    }
    return request<T>(path);
  },
  post: <T>(path: string, data?: unknown) =>
    request<T>(path, { method: 'POST', body: data ? JSON.stringify(data) : undefined }),
  put: <T>(path: string, data: unknown) =>
    request<T>(path, { method: 'PUT', body: JSON.stringify(data) }),
  delete: (path: string) =>
    request<void>(path, { method: 'DELETE' }),
};
