// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, resetCsrf } from './api';

describe('API write requests', () => {
  beforeEach(() => resetCsrf());
  afterEach(() => vi.unstubAllGlobals());

  it('refreshes an expired session before returning a binary attachment', async () => {
    let downloads = 0;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input) === '/api/auth/csrf') return Response.json({ token: 'token' });
      if (String(input) === '/api/auth/refresh') return new Response(null, { status: 204 });
      downloads++;
      if (downloads === 1) return new Response(null, { status: 401 });
      return new Response('attachment contents', { headers: { 'Content-Type': 'application/octet-stream' } });
    }));
    const file = await api<Blob>('/api/mail/attachments/7', {}, true, true);
    expect(await file.text()).toBe('attachment contents');
    expect(downloads).toBe(2);
  });

  it('keeps a zero-byte attachment downloadable', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { headers: { 'Content-Length': '0', 'Content-Type': 'application/octet-stream' } })));
    const file = await api<Blob>('/api/mail/attachments/7', {}, true, true);
    expect(file.size).toBe(0);
  });

  it('refreshes a stale CSRF token once and retries the write', async () => {
    const writeTokens: string[] = [];
    let csrfRequests = 0;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === '/api/auth/csrf') {
        csrfRequests++;
        return new Response(JSON.stringify({ token: csrfRequests === 1 ? 'stale-token' : 'fresh-token' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      }
      writeTokens.push(new Headers(init?.headers).get('X-XSRF-TOKEN') ?? '');
      return writeTokens.length === 1
        ? new Response(JSON.stringify({ detail: 'Forbidden' }), { status: 403, headers: { 'Content-Type': 'application/problem+json' } })
        : new Response(null, { status: 204 });
    }));

    await api<void>('/api/mail/messages/7/pinned', {
      method: 'PATCH',
      body: JSON.stringify({ value: true })
    });

    expect(csrfRequests).toBe(2);
    expect(writeTokens).toEqual(['stale-token', 'fresh-token']);
  });
});
