// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

vi.mock('qrcode', () => ({
  default: { toCanvas: vi.fn().mockResolvedValue(undefined) }
}));

import { enhanceAuthenticatorFields, initBootstrap, initLogin } from './auth';
import { resetCsrf } from './api';

afterEach(() => vi.unstubAllGlobals());

it('shows six centered authenticator slots while keeping one autofill input', () => {
  document.body.innerHTML = '<form><label>Authenticator code<input name="code" autocomplete="one-time-code" inputmode="numeric"></label></form>';
  enhanceAuthenticatorFields();
  const input = document.querySelector<HTMLInputElement>('[name=code]')!;
  input.value = '12a34567';
  input.dispatchEvent(new Event('input'));
  expect(input.value).toBe('123456');
  expect([...document.querySelectorAll('.auth-code-digit')].map(slot => slot.textContent)).toEqual(['1','2','3','4','5','6']);
  expect(document.querySelectorAll('input[name=code]')).toHaveLength(1);
});

it('moves the active underline to the next code digit and clears it on blur', () => {
  document.body.innerHTML = '<form><label>Authenticator code<input name="code" autocomplete="one-time-code" inputmode="numeric"></label></form>';
  enhanceAuthenticatorFields();
  const input = document.querySelector<HTMLInputElement>('[name=code]')!;
  const activeIndex = () => [...document.querySelectorAll('.auth-code-digit')].findIndex(slot => slot.classList.contains('is-current'));
  input.focus();
  expect(activeIndex()).toBe(0);
  input.value = '12';
  input.dispatchEvent(new Event('input'));
  expect(activeIndex()).toBe(2);
  input.blur();
  expect(activeIndex()).toBe(-1);
});

describe('bootstrap form', () => {
  beforeEach(() => {
    resetCsrf();
    document.body.innerHTML = `
      <div data-auth-error></div>
      <form data-bootstrap-form>
        <input name="displayName" type="text">
        <input name="email" type="email">
        <input name="password" type="password">
        <button type="submit">Create</button>
      </form>
      <section data-totp-setup class="hidden">
        <canvas data-qr></canvas><code data-totp-secret></code>
        <form data-totp-form><input name="code"><button type="submit">Verify</button></form>
      </section>
      <section data-recovery-codes class="hidden"><pre data-recovery-list></pre></section>
      <button data-download-recovery></button>
      <button data-enter-mail></button>`;
  });

  it('captures values before disabling controls and never sends a bootstrap token', async () => {
    const requests: Array<{ path: string; body?: string }> = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      requests.push({ path, body: init?.body?.toString() });
      if (path === '/api/auth/csrf') {
        return new Response(JSON.stringify({ token: 'csrf' }), {
          status: 200, headers: { 'Content-Type': 'application/json' }
        });
      }
      return new Response(JSON.stringify({
        challengeId: 'challenge', totpSecret: 'secret', otpauthUri: 'otpauth://totp/Dispatch'
      }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }));

    const displayName = document.querySelector<HTMLInputElement>('input[name="displayName"]')!;
    const email = document.querySelector<HTMLInputElement>('input[name="email"]')!;
    const password = document.querySelector<HTMLInputElement>('input[name="password"]')!;
    displayName.value = 'Test Administrator';
    email.value = 'admin@example.test';
    password.value = 'a-very-long-test-password';

    initBootstrap();
    document.querySelector<HTMLFormElement>('[data-bootstrap-form]')!
      .dispatchEvent(new SubmitEvent('submit', { bubbles: true, cancelable: true }));

    await vi.waitFor(() => expect(requests.some(request => request.path === '/api/auth/bootstrap')).toBe(true));
    const request = requests.find(candidate => candidate.path === '/api/auth/bootstrap')!;
    expect(JSON.parse(request.body!)).toEqual({
      displayName: 'Test Administrator',
      email: 'admin@example.test',
      password: 'a-very-long-test-password'
    });
    expect(request.body).not.toContain('bootstrapToken');
  });

  it('downloads newly generated recovery codes as a text file', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path === '/api/auth/csrf') {
        return new Response(JSON.stringify({ token: 'csrf' }), {
          status: 200, headers: { 'Content-Type': 'application/json' }
        });
      }
      if (path === '/api/auth/bootstrap') {
        return new Response(JSON.stringify({
          challengeId: 'challenge', totpSecret: 'secret', otpauthUri: 'otpauth://totp/Dispatch'
        }), { status: 200, headers: { 'Content-Type': 'application/json' } });
      }
      return new Response(JSON.stringify({
        authenticated: true, recoveryCodes: ['AAAA-BBBB-CCCC-DDDD', '1111-2222-3333-4444']
      }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }));
    const createObjectUrl = vi.fn(() => 'blob:recovery-codes');
    const revokeObjectUrl = vi.fn();
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: createObjectUrl });
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: revokeObjectUrl });
    let downloadedAs = '';
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      downloadedAs = this.download;
    });

    document.querySelector<HTMLInputElement>('input[name="displayName"]')!.value = 'Test Administrator';
    document.querySelector<HTMLInputElement>('input[name="email"]')!.value = 'admin@example.test';
    document.querySelector<HTMLInputElement>('input[name="password"]')!.value = 'a-very-long-test-password';
    initBootstrap();
    document.querySelector<HTMLFormElement>('[data-bootstrap-form]')!
      .dispatchEvent(new SubmitEvent('submit', { bubbles: true, cancelable: true }));
    await vi.waitFor(() => expect(document.querySelector('[data-totp-setup]')?.classList.contains('hidden')).toBe(false));

    document.querySelector<HTMLInputElement>('input[name="code"]')!.value = '123456';
    document.querySelector<HTMLFormElement>('[data-totp-form]')!
      .dispatchEvent(new SubmitEvent('submit', { bubbles: true, cancelable: true }));
    await vi.waitFor(() => expect(document.querySelector('[data-recovery-codes]')?.classList.contains('hidden')).toBe(false));
    document.querySelector<HTMLButtonElement>('[data-download-recovery]')!.click();

    expect(createObjectUrl).toHaveBeenCalledOnce();
    expect(downloadedAs).toBe('dispatch-recovery-codes.txt');
    expect(revokeObjectUrl).toHaveBeenCalledWith('blob:recovery-codes');
  });
});

describe('login form', () => {
  beforeEach(() => {
    resetCsrf();
    document.body.innerHTML = `
      <div data-login-resume></div>
      <div class="hidden" data-login-content>
      <div class="hidden" data-auth-error></div>
      <form data-login-form>
        <input name="email" type="email"><input name="password" type="password">
        <button type="submit">Continue</button>
      </form>
      <form class="hidden" data-totp-form>
        <input name="code"><button type="submit">Verify</button>
      </form>
      <button data-back-login></button>
      </div>`;
  });

  it('shows the login form only after refresh-session restoration is unavailable', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input) === '/api/auth/csrf') {
        return new Response(JSON.stringify({ token: 'csrf' }), {
          status: 200, headers: { 'Content-Type': 'application/json' }
        });
      }
      return new Response(JSON.stringify({ detail: 'Refresh cookie is missing.' }), {
        status: 401, headers: { 'Content-Type': 'application/problem+json' }
      });
    }));

    initLogin();

    await vi.waitFor(() => expect(document.querySelector('[data-login-content]')?.classList.contains('hidden')).toBe(false));
    expect(document.querySelector('[data-login-resume]')?.classList.contains('hidden')).toBe(true);
  });

  it('submits the authenticator code and shows server errors inline', async () => {
    const requests: Array<{ path: string; body?: string }> = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      requests.push({ path, body: init?.body?.toString() });
      if (path === '/api/auth/csrf') {
        return new Response(JSON.stringify({ token: 'csrf' }), {
          status: 200, headers: { 'Content-Type': 'application/json' }
        });
      }
      if (path === '/api/auth/login') {
        return new Response(JSON.stringify({ challengeId: 'challenge', setupRequired: false }), {
          status: 200, headers: { 'Content-Type': 'application/json' }
        });
      }
      return new Response(JSON.stringify({ detail: 'Verification code is invalid.' }), {
        status: 401, headers: { 'Content-Type': 'application/problem+json' }
      });
    }));

    document.querySelector<HTMLInputElement>('input[name="email"]')!.value = 'user@example.test';
    document.querySelector<HTMLInputElement>('input[name="password"]')!.value = 'a-very-long-test-password';
    initLogin();
    document.querySelector<HTMLFormElement>('[data-login-form]')!
      .dispatchEvent(new SubmitEvent('submit', { bubbles: true, cancelable: true }));
    await vi.waitFor(() => expect(document.querySelector('[data-totp-form]')?.classList.contains('hidden')).toBe(false));

    document.querySelector<HTMLInputElement>('input[name="code"]')!.value = '123456';
    document.querySelector<HTMLFormElement>('[data-totp-form]')!
      .dispatchEvent(new SubmitEvent('submit', { bubbles: true, cancelable: true }));

    await vi.waitFor(() => expect(document.querySelector('[data-auth-error]')?.textContent)
      .toBe('Verification code is invalid.'));
    const verifyRequest = requests.find(request => request.path === '/api/auth/verify')!;
    expect(JSON.parse(verifyRequest.body!)).toEqual({ challengeId: 'challenge', code: '123456' });
    expect(document.querySelector<HTMLInputElement>('input[name="code"]')?.getAttribute('aria-invalid')).toBe('true');
    expect(document.querySelector<HTMLButtonElement>('[data-totp-form] button')?.disabled).toBe(false);
  });

  it('shows first-login setup only after password verification and requires completion before entering mail', async () => {
    document.body.innerHTML = new DOMParser().parseFromString(readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), '../../src/main/resources/templates/login.html'), 'utf8'), 'text/html').body.innerHTML;
    const requests: Array<{ path: string; body?: string }> = [];
    let rejectCode = true;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input); requests.push({path, body: init?.body?.toString()});
      const ok = (body: unknown) => new Response(JSON.stringify(body), {status:200,headers:{'Content-Type':'application/json'}});
      if (path === '/api/auth/csrf') return ok({token:'csrf'});
      if (path === '/api/auth/login') return ok({challengeId:'setup-challenge',setupRequired:true,passwordChangeRequired:true,totpSecret:'setup-secret',otpauthUri:'otpauth://totp/Dispatch'});
      if (path === '/api/auth/verify' && !rejectCode) return ok({authenticated:true,recoveryCodes:['RECOVERY-CODE']});
      return new Response(JSON.stringify({detail:'Verification code is invalid.'}), {status:401});
    }));
    initLogin();
    const login = document.querySelector<HTMLFormElement>('[data-login-form]')!;
    login.querySelector<HTMLInputElement>('[name=email]')!.value = 'new@example.test';
    login.querySelector<HTMLInputElement>('[name=password]')!.value = 'temporary-password';
    login.dispatchEvent(new SubmitEvent('submit',{bubbles:true,cancelable:true}));
    await vi.waitFor(() => expect(document.querySelector('[data-login-setup]')!.classList.contains('hidden')).toBe(false));
    expect(document.querySelector('[data-totp-secret]')!.textContent).toBe('setup-secret');
    const form = document.querySelector<HTMLFormElement>('[data-totp-form]')!;
    const password = form.querySelector<HTMLInputElement>('[name=newPassword]')!;
    const confirm = form.querySelector<HTMLInputElement>('[name=confirmPassword]')!;
    expect(password.disabled).toBe(false); expect(password.required).toBe(true);
    password.value = 'permanent-password'; confirm.value = 'mismatch';
    form.dispatchEvent(new SubmitEvent('submit',{bubbles:true,cancelable:true}));
    expect(requests.some(request => request.path === '/api/auth/verify')).toBe(false);
    confirm.value = password.value; form.querySelector<HTMLInputElement>('[name=code]')!.value = '123456';
    form.dispatchEvent(new SubmitEvent('submit',{bubbles:true,cancelable:true}));
    await vi.waitFor(() => expect(document.querySelector('[data-auth-error]')!.textContent).toContain('Verification code is invalid'));
    expect(requests.some(request => request.path === '/api/auth/me')).toBe(false);
    expect(document.querySelector('[data-recovery-codes]')!.classList.contains('hidden')).toBe(true);
    rejectCode = false;
    form.dispatchEvent(new SubmitEvent('submit',{bubbles:true,cancelable:true}));
    await vi.waitFor(() => expect(document.querySelector('[data-recovery-codes]')!.classList.contains('hidden')).toBe(false));
    expect(JSON.parse(requests.filter(request => request.path === '/api/auth/verify').at(-1)!.body!)).toEqual({challengeId:'setup-challenge',code:'123456',newPassword:'permanent-password'});
    expect(document.querySelector('[data-recovery-list]')!.textContent).toBe('RECOVERY-CODE');
    expect(document.querySelector('[data-totp-secret]')!.textContent).toBe('');
  });

  it('lets a user with a recovery code enroll a new authenticator before signing in', async () => {
    document.body.innerHTML = new DOMParser().parseFromString(readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), '../../src/main/resources/templates/login.html'), 'utf8'), 'text/html').body.innerHTML;
    const requests: Array<{ path: string; body?: string }> = [];
    const ok = (body: unknown) => new Response(JSON.stringify(body), {status: 200, headers: {'Content-Type': 'application/json'}});
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input); requests.push({path, body: init?.body?.toString()});
      if (path === '/api/auth/csrf') return ok({token: 'csrf'});
      if (path === '/api/auth/refresh') return new Response('', {status: 401});
      if (path === '/api/auth/login') return ok({challengeId: 'recover-challenge', setupRequired: false});
      if (path === '/api/auth/recover-authenticator') return ok({challengeId: 'recover-challenge', setupRequired: true, totpSecret: 'new-secret', otpauthUri: 'otpauth://totp/Dispatch'});
      if (path === '/api/auth/verify') return ok({authenticated: true, recoveryCodes: ['NEW-RECOVERY-CODE']});
      return new Response('', {status: 404});
    }));
    initLogin();
    const login = document.querySelector<HTMLFormElement>('[data-login-form]')!;
    login.querySelector<HTMLInputElement>('[name=email]')!.value = 'alex@example.test';
    login.querySelector<HTMLInputElement>('[name=password]')!.value = 'a-very-long-password';
    login.dispatchEvent(new SubmitEvent('submit', {bubbles: true, cancelable: true}));
    await vi.waitFor(() => expect(document.querySelector('[data-totp-form]')?.classList.contains('hidden')).toBe(false));
    document.querySelector<HTMLButtonElement>('[data-start-authenticator-recovery]')!.click();
    expect(document.querySelector('[data-recovery-form]')?.classList.contains('hidden')).toBe(false);
    const recovery = document.querySelector<HTMLFormElement>('[data-recovery-form]')!;
    recovery.querySelector<HTMLInputElement>('[name=recoveryCode]')!.value = 'AAAA-BBBB-CCCC-DDDD';
    recovery.dispatchEvent(new SubmitEvent('submit', {bubbles: true, cancelable: true}));
    await vi.waitFor(() => expect(document.querySelector('[data-totp-secret]')?.textContent).toBe('new-secret'));
    expect(JSON.parse(requests.find(request => request.path === '/api/auth/recover-authenticator')!.body!))
      .toEqual({challengeId: 'recover-challenge', recoveryCode: 'AAAA-BBBB-CCCC-DDDD'});
    const verify = document.querySelector<HTMLFormElement>('[data-totp-form]')!;
    verify.querySelector<HTMLInputElement>('[name=code]')!.value = '123456';
    verify.dispatchEvent(new SubmitEvent('submit', {bubbles: true, cancelable: true}));
    await vi.waitFor(() => expect(document.querySelector('[data-recovery-list]')?.textContent).toBe('NEW-RECOVERY-CODE'));
    expect(document.querySelector('[data-totp-secret]')?.textContent).toBe('');
  });
});
