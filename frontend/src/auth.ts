import QRCode from 'qrcode';
import { api, ApiError } from './api';

type BootstrapResponse = { challengeId: string; totpSecret: string; otpauthUri: string };
type ChallengeResponse = { challengeId: string; setupRequired: boolean; totpSecret?: string; otpauthUri?: string; passwordChangeRequired?: boolean };
type AuthResponse = { authenticated: boolean; recoveryCodes: string[] };

function formData(form: HTMLFormElement): Record<string, string> {
  return Object.fromEntries(new FormData(form).entries()) as Record<string, string>;
}

function setError(message: string): void {
  const box = document.querySelector<HTMLElement>('[data-auth-error]');
  if (!box) return;
  box.textContent = message;
  box.classList.toggle('hidden', !message);
  if (message) {
    box.scrollIntoView?.({ block: 'nearest' });
  }
}

function busy(form: HTMLFormElement, value: boolean): void {
  form.querySelectorAll<HTMLButtonElement | HTMLInputElement>('button,input').forEach(control => control.disabled = value);
  const submit = form.querySelector<HTMLButtonElement>('button[type="submit"]');
  if (!submit) return;
  if (value) {
    submit.dataset.idleLabel = submit.textContent ?? '';
    submit.textContent = form.dataset.busyLabel ?? 'Please wait…';
  } else if (submit.dataset.idleLabel !== undefined) {
    submit.textContent = submit.dataset.idleLabel;
    delete submit.dataset.idleLabel;
  }
}

function message(error: unknown): string {
  return error instanceof ApiError ? error.message : 'Something went wrong. Try again.';
}

async function copySetupKey(value: string, button: HTMLButtonElement): Promise<void> {
  try {
    await navigator.clipboard.writeText(value);
  } catch {
    const input = document.createElement('textarea');
    input.value = value; input.style.position = 'fixed'; input.style.opacity = '0';
    document.body.append(input); input.select(); document.execCommand('copy'); input.remove();
  }
  button.title = 'Copied'; button.setAttribute('aria-label', 'Copied');
  window.setTimeout(() => { button.title = 'Copy setup key'; button.setAttribute('aria-label', 'Copy setup key'); }, 1600);
}

export function enhanceAuthenticatorFields(root: ParentNode = document): void {
  root.querySelectorAll<HTMLInputElement>('input[autocomplete="one-time-code"][inputmode="numeric"]')
    .forEach(input => {
      if (input.parentElement?.classList.contains('auth-code-field')) return;
      const field = document.createElement('span');
      field.className = 'auth-code-field';
      const slots = document.createElement('span');
      slots.className = 'auth-code-slots';
      slots.setAttribute('aria-hidden', 'true');
      const digits = Array.from({ length: 6 }, () => {
        const digit = document.createElement('span');
        digit.className = 'auth-code-digit';
        slots.append(digit);
        return digit;
      });
      input.before(field);
      field.append(slots, input);
      input.maxLength = 6;
      input.pattern = '[0-9]{6}';
      const render = () => {
        const value = input.value.replace(/\D/g, '').slice(0, 6);
        if (input.value !== value) input.value = value;
        const current = document.activeElement === input ? Math.min(value.length, 5) : -1;
        digits.forEach((digit, index) => {
          digit.textContent = value[index] ?? '';
          digit.classList.toggle('is-current', index === current);
        });
      };
      input.addEventListener('input', render);
      input.addEventListener('change', render);
      input.addEventListener('focus', render);
      input.addEventListener('blur', render);
      input.form?.addEventListener('reset', () => queueMicrotask(render));
      render();
    });
}

export function initLogin(): void {
  const login = document.querySelector<HTMLFormElement>('[data-login-form]');
  const totp = document.querySelector<HTMLFormElement>('[data-totp-form]');
  if (!login || !totp) return;
  enhanceAuthenticatorFields();
  const resume = document.querySelector<HTMLElement>('[data-login-resume]');
  const content = document.querySelector<HTMLElement>('[data-login-content]');
  void (async () => {
    try {
      const result = await api<AuthResponse>('/api/auth/refresh', { method: 'POST' });
      if (result.authenticated) {
        await api<{ id: number; email: string }>('/api/auth/me');
        window.location.replace('/mail');
        return;
      }
    } catch { /* no valid refresh session; show the login form */ }
    resume?.classList.add('hidden');
    content?.classList.remove('hidden');
  })();
  let challengeId = '';
  let changePassword = false;
  let recoveryCodes: string[] = [];
  const setup = document.querySelector<HTMLElement>('[data-login-setup]');
  const passwordFields = document.querySelector<HTMLElement>('[data-new-password-fields]');
  const recoveryForm = document.querySelector<HTMLFormElement>('[data-recovery-form]');
  const recoveryStart = document.querySelector<HTMLButtonElement>('[data-start-authenticator-recovery]');
  const passwordRecovery = document.querySelector<HTMLFormElement>('[data-password-recovery-form]');
  const success = document.querySelector<HTMLElement>('[data-auth-success]');
  document.querySelector<HTMLButtonElement>('[data-start-password-recovery]')?.addEventListener('click', () => {
    setError(''); success?.classList.add('hidden');
    passwordRecovery?.reset();
    const emailInput = passwordRecovery?.querySelector<HTMLInputElement>('[name=email]');
    if (emailInput) emailInput.value = login.querySelector<HTMLInputElement>('[name=email]')?.value ?? '';
    login.classList.add('hidden'); passwordRecovery?.classList.remove('hidden');
    const title = document.querySelector('[id=auth-title]'); if (title) title.textContent = 'Recover your account';
    document.querySelector('[data-login-intro]')?.classList.add('hidden');
    passwordRecovery?.querySelector<HTMLInputElement>('[name=email]')?.focus();
  });
  document.querySelector('[data-back-password-login]')?.addEventListener('click', () => {
    setError(''); passwordRecovery?.classList.add('hidden'); login.classList.remove('hidden');
    const title = document.querySelector('[id=auth-title]'); if (title) title.textContent = 'Sign in to Dispatch';
    document.querySelector('[data-login-intro]')?.classList.remove('hidden');
  });
  passwordRecovery?.addEventListener('submit', async event => {
    event.preventDefault(); setError('');
    const values = formData(passwordRecovery);
    if (values.newPassword !== values.confirmPassword) { setError('The new passwords do not match.'); return; }
    busy(passwordRecovery, true);
    try {
      await api('/api/auth/recover-password', { method: 'POST', body: JSON.stringify({
        email: values.email, authenticatorCode: values.authenticatorCode,
        recoveryCode: values.recoveryCode, newPassword: values.newPassword
      }) });
      passwordRecovery.reset(); passwordRecovery.classList.add('hidden'); login.classList.remove('hidden');
      login.querySelector<HTMLInputElement>('[name=email]')!.value = values.email;
      const title = document.querySelector('[id=auth-title]'); if (title) title.textContent = 'Sign in to Dispatch';
      document.querySelector('[data-login-intro]')?.classList.remove('hidden');
      if (success) { success.textContent = 'Password reset. Sign in with your new password.'; success.classList.remove('hidden'); }
    } catch (error) { setError(message(error)); }
    finally { busy(passwordRecovery, false); }
  });
  const clearSetup = () => {
    const secret = document.querySelector('[data-totp-secret]'); if (secret) secret.textContent = '';
    const canvas = document.querySelector<HTMLCanvasElement>('[data-qr]'); if (canvas) canvas.width = canvas.width;
    setup?.classList.add('hidden');
  };
  document.querySelector<HTMLButtonElement>('[data-copy-totp]')?.addEventListener('click', event => {
    void copySetupKey(document.querySelector('[data-totp-secret]')?.textContent ?? '', event.currentTarget as HTMLButtonElement);
  });
  login.addEventListener('submit', async event => {
    event.preventDefault(); setError('');
    login.querySelectorAll('input').forEach(input => input.removeAttribute('aria-invalid'));
    const payload = formData(login);
    busy(login, true);
    try {
      const result = await api<ChallengeResponse>('/api/auth/login', { method: 'POST', body: JSON.stringify(payload) });
      challengeId = result.challengeId;
      clearSetup(); totp.reset(); changePassword = Boolean(result.passwordChangeRequired);
      recoveryForm?.classList.add('hidden');
      recoveryStart?.classList.toggle('hidden', result.setupRequired);
      const title = document.querySelector('[id=auth-title]'); if (title) title.textContent = result.setupRequired ? 'Connect your authenticator' : 'Sign in to Dispatch';
      document.querySelector('[data-login-intro]')?.classList.toggle('hidden', result.setupRequired);
      document.querySelector('.auth-card')?.classList.toggle('auth-card-wide', result.setupRequired);
      totp.querySelector('.auth-step-heading')?.classList.toggle('hidden', result.setupRequired);
      if (result.setupRequired) {
        if (!result.totpSecret || !result.otpauthUri || !setup) throw new ApiError(400, 'Setup could not be loaded. Sign in again.');
        setup.querySelector('[data-totp-secret]')!.textContent = result.totpSecret;
        await QRCode.toCanvas(setup.querySelector<HTMLCanvasElement>('[data-qr]')!, result.otpauthUri, { width: 190, margin: 1 });
        setup.classList.remove('hidden');
      }
      passwordFields?.classList.toggle('hidden', !changePassword);
      passwordFields?.querySelectorAll('input').forEach(input => { input.disabled = !changePassword; input.required = changePassword; });
      totp.querySelector<HTMLButtonElement>('button[type=submit]')!.textContent = result.setupRequired ? 'Finish secure setup' : 'Verify and sign in';
      const loginPassword = login.querySelector<HTMLInputElement>('[name=password]'); if (loginPassword) loginPassword.value = '';
      login.classList.add('hidden'); totp.classList.remove('hidden');
      totp.querySelector<HTMLInputElement>(changePassword ? '[name=newPassword]' : '[name=code]')?.focus();
    } catch (error) {
      setError(message(error));
      const email = login.querySelector<HTMLInputElement>('input[name="email"]');
      email?.setAttribute('aria-invalid', 'true');
      email?.focus();
    } finally { busy(login, false); }
  });
  recoveryStart?.addEventListener('click', () => {
    setError(''); totp.classList.add('hidden'); recoveryForm?.classList.remove('hidden');
    recoveryForm?.querySelector<HTMLInputElement>('input[name=recoveryCode]')?.focus();
  });
  document.querySelector('[data-back-authenticator-code]')?.addEventListener('click', () => {
    setError(''); recoveryForm?.classList.add('hidden'); totp.classList.remove('hidden');
    totp.querySelector<HTMLInputElement>('input[name=code]')?.focus();
  });
  recoveryForm?.addEventListener('submit', async event => {
    event.preventDefault(); setError('');
    const input = recoveryForm.querySelector<HTMLInputElement>('input[name=recoveryCode]')!;
    busy(recoveryForm, true);
    try {
      const result = await api<ChallengeResponse>('/api/auth/recover-authenticator', {
        method: 'POST', body: JSON.stringify({ challengeId, recoveryCode: input.value.trim() })
      });
      if (!result.totpSecret || !result.otpauthUri) throw new ApiError(400, 'Setup could not be loaded. Sign in again.');
      clearSetup();
      setup!.querySelector('[data-totp-secret]')!.textContent = result.totpSecret;
      await QRCode.toCanvas(setup!.querySelector<HTMLCanvasElement>('[data-qr]')!, result.otpauthUri, { width: 190, margin: 1 });
      setup!.classList.remove('hidden');
      totp.querySelector('.auth-step-heading')?.classList.add('hidden');
      recoveryStart?.classList.add('hidden');
      document.querySelector('.auth-card')?.classList.add('auth-card-wide');
      const title = document.querySelector('[id=auth-title]'); if (title) title.textContent = 'Connect your new authenticator';
      document.querySelector('[data-login-intro]')?.classList.add('hidden');
      totp.querySelector<HTMLButtonElement>('button[type=submit]')!.textContent = 'Finish secure setup';
      input.value = '';
      recoveryForm.classList.add('hidden'); totp.classList.remove('hidden');
      totp.querySelector<HTMLInputElement>('input[name=code]')?.focus();
    } catch (error) {
      setError(message(error)); input.select();
    } finally { busy(recoveryForm, false); }
  });
  totp.addEventListener('submit', async event => {
    event.preventDefault(); setError('');
    const codeInput = totp.querySelector<HTMLInputElement>('input[name="code"]')!;
    codeInput.removeAttribute('aria-invalid');
    const code = codeInput.value.trim();
    const password = totp.querySelector<HTMLInputElement>('[name=newPassword]')?.value;
    if (changePassword && password !== totp.querySelector<HTMLInputElement>('[name=confirmPassword]')?.value) {
      setError('The new passwords do not match.'); return;
    }
    busy(totp, true);
    try {
      const result = await api<AuthResponse>('/api/auth/verify', { method: 'POST', body: JSON.stringify({ challengeId, code, ...(changePassword ? { newPassword: password } : {}) }) });
      if (!result.authenticated) throw new ApiError(401, 'Sign-in could not be completed. Try again.');
      clearSetup(); totp.reset();
      if (result.recoveryCodes?.length) {
        recoveryCodes = result.recoveryCodes;
        totp.classList.add('hidden');
        document.querySelector('[data-recovery-list]')!.textContent = recoveryCodes.join('\n');
        document.querySelector('[data-recovery-codes]')!.classList.remove('hidden');
        return;
      }
      await api<{ id: number; email: string }>('/api/auth/me');
      window.location.replace('/mail');
    } catch (error) {
      setError(message(error));
      codeInput.setAttribute('aria-invalid', 'true');
      codeInput.select();
      busy(totp, false);
      passwordFields?.querySelectorAll('input').forEach(input => { input.disabled = !changePassword; });
    }
  });
  document.querySelector('[data-back-login]')?.addEventListener('click', () => {
    challengeId = ''; clearSetup(); totp.reset(); busy(totp, false); totp.classList.add('hidden'); recoveryForm?.classList.add('hidden'); login.classList.remove('hidden'); setError('');
    const title = document.querySelector('[id=auth-title]'); if (title) title.textContent = 'Sign in to Dispatch';
    document.querySelector('[data-login-intro]')?.classList.remove('hidden');
    document.querySelector('.auth-card')?.classList.remove('auth-card-wide');
  });
  document.querySelector('[data-download-recovery]')?.addEventListener('click', () => downloadRecoveryCodes(recoveryCodes));
  document.querySelector('[data-enter-mail]')?.addEventListener('click', async () => {
    try { await api('/api/auth/me'); window.location.replace('/mail'); } catch (error) { setError(message(error)); }
  });
}

function downloadRecoveryCodes(codes: string[]): void {
  if (!codes.length) return;
  const url = URL.createObjectURL(new Blob([['Dispatch recovery codes', 'Store these codes offline. Each code works once.', '', ...codes, ''].join('\n')], { type: 'text/plain;charset=utf-8' }));
  const link = document.createElement('a'); link.href = url; link.download = 'dispatch-recovery-codes.txt';
  document.body.append(link); link.click(); link.remove(); URL.revokeObjectURL(url);
}

export function initBootstrap(): void {
  const bootstrap = document.querySelector<HTMLFormElement>('[data-bootstrap-form]');
  const totpForm = document.querySelector<HTMLFormElement>('[data-totp-form]');
  const setup = document.querySelector<HTMLElement>('[data-totp-setup]');
  if (!bootstrap || !totpForm || !setup) return;
  enhanceAuthenticatorFields();
  let challengeId = '';
  let recoveryCodes: string[] = [];
  document.querySelector<HTMLButtonElement>('[data-copy-totp]')?.addEventListener('click', event => {
    const value = document.querySelector('[data-totp-secret]')?.textContent ?? '';
    void copySetupKey(value, event.currentTarget as HTMLButtonElement);
  });
  bootstrap.addEventListener('submit', async event => {
    event.preventDefault(); setError('');
    const payload = formData(bootstrap);
    busy(bootstrap, true);
    try {
      const result = await api<BootstrapResponse>('/api/auth/bootstrap', { method: 'POST', body: JSON.stringify(payload) });
      challengeId = result.challengeId;
      document.querySelector('[data-totp-secret]')!.textContent = result.totpSecret;
      await QRCode.toCanvas(document.querySelector<HTMLCanvasElement>('[data-qr]')!, result.otpauthUri, { width: 220, margin: 1 });
      bootstrap.classList.add('hidden'); setup.classList.remove('hidden');
    } catch (error) { setError(message(error)); busy(bootstrap, false); }
  });
  totpForm.addEventListener('submit', async event => {
    event.preventDefault(); setError('');
    const codeInput = totpForm.querySelector<HTMLInputElement>('input[name="code"]')!;
    codeInput.removeAttribute('aria-invalid');
    const code = codeInput.value.trim();
    busy(totpForm, true);
    try {
      const result = await api<AuthResponse>('/api/auth/verify', { method: 'POST', body: JSON.stringify({ challengeId, code }) });
      recoveryCodes = result.recoveryCodes;
      setup.classList.add('hidden');
      const recovery = document.querySelector<HTMLElement>('[data-recovery-codes]')!;
      recovery.classList.remove('hidden');
      document.querySelector('[data-recovery-list]')!.textContent = recoveryCodes.join('\n');
    } catch (error) {
      setError(message(error));
      codeInput.setAttribute('aria-invalid', 'true');
      codeInput.select();
      busy(totpForm, false);
    }
  });
  document.querySelector('[data-download-recovery]')?.addEventListener('click', () => {
    if (!recoveryCodes.length) return;
    const content = ['Dispatch recovery codes', 'Store these codes offline. Each code works once.', '', ...recoveryCodes, ''].join('\n');
    const url = URL.createObjectURL(new Blob([content], { type: 'text/plain;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = 'dispatch-recovery-codes.txt';
    document.body.append(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  });
  document.querySelector('[data-enter-mail]')?.addEventListener('click', async () => {
    setError('');
    try {
      await api<{ id: number; email: string }>('/api/auth/me');
      window.location.replace('/mail');
    } catch (error) {
      setError(message(error));
    }
  });
}
