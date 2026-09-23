// @vitest-environment jsdom
import { beforeEach, expect, it } from 'vitest';
import { initCookieNotice } from './cookie-notice';

beforeEach(() => {
  document.body.replaceChildren();
  localStorage.clear();
  sessionStorage.clear();
});

it('explains essential cookies and remembers acceptance across visits', () => {
  initCookieNotice();
  const notice = document.querySelector<HTMLElement>('.cookie-notice')!;
  expect(notice.getAttribute('role')).toBe('dialog');
  expect(notice.textContent).toContain('Signing in requires them');
  expect(notice.querySelector('a')?.getAttribute('href')).toBe('/privacy-policy#cookies');
  notice.querySelector<HTMLButtonElement>('.cookie-notice-accept')!.click();
  expect(document.querySelector('.cookie-notice')).toBeNull();
  initCookieNotice();
  expect(document.querySelector('.cookie-notice')).toBeNull();
});

it('lets visitors dismiss the notice for the current browsing session', () => {
  initCookieNotice();
  document.querySelector<HTMLButtonElement>('.cookie-notice-later')!.click();
  initCookieNotice();
  expect(document.querySelector('.cookie-notice')).toBeNull();
  sessionStorage.clear();
  initCookieNotice();
  expect(document.querySelector('.cookie-notice')).not.toBeNull();
});
