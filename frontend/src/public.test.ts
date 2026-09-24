// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { resetCsrf } from './api';
import { initPublicPage } from './public';

beforeEach(() => {
  resetCsrf();
  document.body.innerHTML = '<a class="public-header-signin" href="/login">Sign in</a><a class="public-header-profile hidden" href="/mail" data-public-profile><span data-public-initials></span><span data-public-name></span><span data-public-role></span></a>';
});
afterEach(() => vi.unstubAllGlobals());

it('restores the signed-in profile in the public header after an expired access cookie', async () => {
  document.querySelector('.public-header-signin')?.classList.add('hidden');
  let profileRequests = 0;
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    if (path === '/api/auth/me') {
      profileRequests++;
      if (profileRequests === 1) return new Response('', { status: 401 });
      return new Response(JSON.stringify({ displayName: 'Alex Morgan', role: 'ADMIN' }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }
    if (path === '/api/auth/csrf') return new Response(JSON.stringify({ token: 'csrf' }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    if (path === '/api/auth/refresh') return new Response(JSON.stringify({ authenticated: true }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    return new Response('', { status: 404 });
  }));

  initPublicPage();
  await vi.waitFor(() => expect(document.querySelector('[data-public-profile]')?.classList.contains('hidden')).toBe(false));
  expect(document.querySelector('.public-header-signin')?.classList.contains('hidden')).toBe(true);
  expect(document.querySelector('[data-public-initials]')?.textContent).toBe('AM');
  expect(document.querySelector('[data-public-name]')?.textContent).toBe('Alex Morgan');
  expect(document.querySelector('[data-public-role]')?.textContent).toBe('Administrator');
});

it('does not request a profile when the server rendered a signed-out header', () => {
  const fetch = vi.fn();
  vi.stubGlobal('fetch', fetch);
  initPublicPage();
  expect(fetch).not.toHaveBeenCalled();
});
