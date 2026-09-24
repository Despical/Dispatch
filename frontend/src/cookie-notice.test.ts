// @vitest-environment jsdom
import { beforeEach, expect, it, vi } from 'vitest';
import { initCookieNotice } from './cookie-notice';

beforeEach(() => {
  document.body.replaceChildren();
  localStorage.clear();
  sessionStorage.clear();
});

it('loads analytics only after consent and remembers acceptance across visits', () => {
  const accepted = vi.fn();
  window.addEventListener('dispatch:analytics-accepted', accepted, { once: true });
  initCookieNotice();
  const notice = document.querySelector<HTMLElement>('.cookie-notice')!;
  expect(notice.tagName).toBe('SECTION');
  expect(notice.hasAttribute('role')).toBe(false);
  expect(notice.textContent).toContain('Google Analytics measures visits to public pages');
  expect(notice.querySelector('a')?.getAttribute('href')).toBe('/privacy-policy#cookies');
  notice.querySelector<HTMLButtonElement>('.cookie-notice-accept')!.click();
  expect(accepted).toHaveBeenCalledOnce();
  expect(localStorage.getItem('dispatch.analytics-consent.v1')).toBe('accepted');
  expect(document.querySelector('.cookie-notice')).toBeNull();
  initCookieNotice();
  expect(document.querySelector('.cookie-notice')).toBeNull();
});

it('keeps analytics off after Essential only and lets visitors change the choice', () => {
  const settings = document.createElement('button');
  settings.dataset.cookieSettings = '';
  document.body.append(settings);
  initCookieNotice();
  document.querySelector<HTMLButtonElement>('.cookie-notice-later')!.click();
  expect(localStorage.getItem('dispatch.analytics-consent.v1')).toBe('essential');
  initCookieNotice();
  expect(document.querySelector('.cookie-notice')).toBeNull();
  settings.click();
  expect(document.querySelector('.cookie-notice')).not.toBeNull();
});

it('asks again when only the older essential-cookie notice was accepted', () => {
  localStorage.setItem('dispatch.cookie-notice.accepted', 'yes');
  initCookieNotice();
  expect(document.querySelector('.cookie-notice')).not.toBeNull();
});
