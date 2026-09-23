// @vitest-environment jsdom
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { initAdminUsers } from './admin';

const { mockedApi } = vi.hoisted(() => ({ mockedApi: vi.fn() }));
vi.mock('./api', () => ({ api: mockedApi }));

const template = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), '../../src/main/resources/templates/admin-users.html'), 'utf8');
const $ = <T extends HTMLElement>(selector: string): T => document.querySelector<T>(selector)!;
const ready = async () => { await vi.waitFor(() => expect($('[data-admin-list]').textContent).not.toContain('Loading users')); };

describe('admin user management page', () => {
  beforeEach(() => {
    document.body.innerHTML = new DOMParser().parseFromString(template, 'text/html').body.innerHTML;
    document.body.dataset.adminId = '42';
    Object.defineProperties(HTMLDialogElement.prototype, {
      showModal: { configurable: true, value: function(this: HTMLDialogElement) { this.open = true; } },
      close: { configurable: true, value: function(this: HTMLDialogElement) { this.open = false; this.dispatchEvent(new Event('close')); } }
    });
    mockedApi.mockReset();
  });

  it('keeps the signed-in administrator free of Disable and offers it for others', async () => {
    mockedApi.mockResolvedValue([
      { id: 42, email: 'me@example.test', displayName: 'Me', role: 'ADMIN', enabled: true, totpEnabled: true },
      { id: 43, email: 'other@example.test', displayName: 'Other', role: 'USER', enabled: true, totpEnabled: true }
    ]);
    initAdminUsers(); await ready();
    const rows = [...document.querySelectorAll<HTMLElement>('.admin-user-row')];
    expect(rows[0].querySelector('button')).toBeNull();
    expect(rows[1].querySelector('button')?.textContent).toBe('Disable');
  });

  it('enables a disabled user without removing their account', async () => {
    let enabled = false;
    mockedApi.mockImplementation(async (path: string) => {
      if (path === '/api/admin/users') return [{ id: 7, email: 'user@example.test', displayName: 'User', role: 'USER', enabled, totpEnabled: false }];
      if (path === '/api/admin/users/7/enable') { enabled = true; return; }
      throw new Error(`Unexpected call: ${path}`);
    });
    initAdminUsers(); await ready();
    expect([...document.querySelectorAll('.admin-user-actions button')].map(button => button.textContent)).toEqual(['Enable', 'Delete']);
    $<HTMLButtonElement>('.admin-user-actions button').click();
    await vi.waitFor(() => expect($('[data-admin-list]').textContent).toContain('Enabled'));
    expect(mockedApi).toHaveBeenCalledWith('/api/admin/users/7/enable', { method: 'POST' });
  });

  it('defaults to User but submits the selected role without exposing 2FA setup', async () => {
    mockedApi.mockImplementation(async (path: string, options?: RequestInit) => {
      if (path === '/api/admin/users') return options?.method === 'POST' ? { id: 2, totpEnabled: false } : [];
      throw new Error(`Unexpected call: ${path}`);
    });
    initAdminUsers(); await ready();
    $<HTMLButtonElement>('[data-open-admin-create]').click();
    const form = $<HTMLFormElement>('[data-admin-form]');
    expect((form.elements.namedItem('role') as RadioNodeList).value).toBe('USER');
    (form.elements.namedItem('role') as RadioNodeList).value = 'ADMIN';
    (form.elements.namedItem('displayName') as HTMLInputElement).value = 'New admin';
    (form.elements.namedItem('email') as HTMLInputElement).value = 'new@example.test';
    (form.elements.namedItem('password') as HTMLInputElement).value = 'temporary-password';
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await vi.waitFor(() => expect($<HTMLDialogElement>('[data-admin-create-modal]').open).toBe(false));
    const request = mockedApi.mock.calls.find(([path, options]) => path === '/api/admin/users' && options?.method === 'POST');
    expect(JSON.parse(String(request?.[1].body)).role).toBe('ADMIN');
    expect(document.querySelector('[data-admin-secret]')).toBeNull();
  });
});
