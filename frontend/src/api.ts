export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

let csrfToken: string | null = null;
let refreshPromise: Promise<boolean> | null = null;
const authChannel = typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel('dispatch-auth') : null;

async function ensureCsrf(): Promise<string> {
  if (csrfToken) return csrfToken;
  let response: Response;
  try {
    response = await fetch('/api/auth/csrf', { credentials: 'same-origin' });
  } catch {
    throw new ApiError(0, 'Dispatch could not reach the server. Check your connection and try again.');
  }
  if (!response.ok) throw new ApiError(response.status, 'Security token could not be initialized.');
  let body: { token?: string };
  try {
    body = await response.json() as { token?: string };
  } catch {
    throw new ApiError(response.status, 'Security token response was invalid. Refresh the page and try again.');
  }
  if (!body.token) throw new ApiError(response.status, 'Security token response was incomplete. Refresh the page and try again.');
  csrfToken = body.token;
  return csrfToken;
}

async function refresh(): Promise<boolean> {
  if (refreshPromise) return refreshPromise;
  refreshPromise = (async () => {
    try {
      const token = await ensureCsrf();
      const response = await fetch('/api/auth/refresh', {
        method: 'POST', credentials: 'same-origin', headers: { 'X-XSRF-TOKEN': token }
      });
      if (!response.ok) return false;
      authChannel?.postMessage({ type: 'refreshed' });
      return true;
    } catch {
      return false;
    } finally {
      refreshPromise = null;
    }
  })();
  return refreshPromise;
}

export async function api<T>(path: string, options: RequestInit = {}, retry = true, binary = false): Promise<T> {
  const method = (options.method ?? 'GET').toUpperCase();
  const headers = new Headers(options.headers);
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) headers.set('X-XSRF-TOKEN', await ensureCsrf());
  if (options.body && !(options.body instanceof FormData)) headers.set('Content-Type', 'application/json');
  let response: Response;
  try {
    response = await fetch(path, { ...options, method, headers, credentials: 'same-origin' });
  } catch {
    throw new ApiError(0, 'Dispatch could not reach the server. Check your connection and try again.');
  }
  const isAuthRoute = path.startsWith('/api/auth/');
  if (response.status === 401 && retry && !isAuthRoute && await refresh()) return api<T>(path, options, false, binary);
  if (response.status === 403 && retry && !isAuthRoute && !['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    csrfToken = null;
    await ensureCsrf();
    return api<T>(path, options, false, binary);
  }
  if (!response.ok) {
    let message = `Request failed (${response.status}).`;
    try {
      const problem = await response.json() as { detail?: string; errors?: string[] };
      message = problem.errors?.join(' ') || problem.detail || message;
    } catch { /* response had no JSON body */ }
    throw new ApiError(response.status, message);
  }
  if (binary) return response.blob() as Promise<T>;
  if (response.status === 204 || response.headers.get('content-length') === '0') return undefined as T;
  const contentType = response.headers.get('content-type') ?? '';
  return contentType.includes('json') ? response.json() as Promise<T> : response.text() as Promise<T>;
}

export function resetCsrf(): void {
  csrfToken = null;
}
