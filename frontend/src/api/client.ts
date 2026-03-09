const BASE_URL = '/api';

interface ApiErrorBody {
  code: string;
  message: string;
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
      const body: ApiErrorBody = await res.json();
      if (body.code) {
        errorMessage = body.message;
        errorCode = body.code;
      }
    } catch {
      // response is not JSON
    }
    throw new ApiError(errorCode, errorMessage, res.status);
  }

  return res.json() as Promise<T>;
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
