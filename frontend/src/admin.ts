import { api } from './api';

type Admin = { id: number; displayName: string; email: string; enabled: boolean; totpEnabled: boolean; role: 'ADMIN' | 'USER' };

const $ = <T extends HTMLElement>(selector: string): T => document.querySelector<T>(selector)!;
const element = (tag: string, className: string, value: string): HTMLElement => {
  const result = document.createElement(tag); result.className = className; result.textContent = value; return result;
};
const errorText = (error: unknown): string => error instanceof Error ? error.message : 'The request could not be completed.';

export function initAdminShell(): void {
  const trigger = $<HTMLButtonElement>('[data-account-menu]');
  const menu = $<HTMLElement>('[data-account-popover]');
  const close = (): void => { menu.hidePopover?.(); menu.classList.add('hidden'); trigger.setAttribute('aria-expanded', 'false'); };
  trigger.addEventListener('click', event => {
    event.stopPropagation();
    const open = menu.classList.toggle('hidden') === false;
    if (open) menu.showPopover?.(); else menu.hidePopover?.();
    trigger.setAttribute('aria-expanded', String(open));
  });
  document.addEventListener('pointerdown', event => {
    if (!menu.contains(event.target as Node) && !trigger.contains(event.target as Node)) close();
  }, true);
  document.addEventListener('keydown', event => { if (event.key === 'Escape') close(); });
  const nav = $<HTMLElement>('[data-admin-nav]');
  const navToggle = $<HTMLButtonElement>('[data-open-admin-nav]');
  const closeNav = (focusToggle = false): void => {
    nav.classList.remove('is-open');
    navToggle.setAttribute('aria-expanded', 'false');
    if (focusToggle) navToggle.focus();
  };
  navToggle.addEventListener('click', () => {
    const open = nav.classList.toggle('is-open');
    navToggle.setAttribute('aria-expanded', String(open));
  });
  $('[data-close-admin-nav]').addEventListener('click', () => closeNav(true));
  document.addEventListener('click', event => {
    if (nav.classList.contains('is-open') && !nav.contains(event.target as Node) && !navToggle.contains(event.target as Node)) closeNav();
  });
  document.addEventListener('keydown', event => { if (event.key === 'Escape' && nav.classList.contains('is-open')) closeNav(true); });
  const logout = async (allDevices: boolean): Promise<void> => {
    menu.querySelectorAll<HTMLButtonElement>('button').forEach(button => { button.disabled = true; });
    try { await api(`/api/auth/logout?allDevices=${allDevices}`, { method: 'POST' }); window.location.assign('/login'); }
    catch (error) { alert(errorText(error)); menu.querySelectorAll<HTMLButtonElement>('button').forEach(button => { button.disabled = false; }); }
  };
  $('[data-logout]').addEventListener('click', () => { void logout(false); });
  $('[data-logout-all]').addEventListener('click', () => { void logout(true); });
}

export function initAdminUsers(): void {
  const root = $<HTMLElement>('[data-admin-list]');
  const create = $<HTMLDialogElement>('[data-admin-create-modal]');
  const confirm = $<HTMLDialogElement>('[data-admin-confirm]');
  const selfId = Number(document.body.dataset.adminId);
  const showError = (error: unknown): void => { $('[data-admin-error]').textContent = errorText(error); };
  const ask = (title: string, copy: string, label: string): Promise<boolean> => new Promise(resolve => {
    $('[data-admin-confirm-title]').textContent = title;
    $('[data-admin-confirm-copy]').textContent = copy;
    const accept = $<HTMLButtonElement>('[data-admin-confirm-accept]');
    accept.textContent = label;
    let result = false;
    accept.onclick = () => { result = true; confirm.close(); };
    confirm.addEventListener('close', () => resolve(result), { once: true });
    confirm.showModal();
  });
  document.querySelectorAll('[data-admin-confirm-cancel]').forEach(button => button.addEventListener('click', () => confirm.close()));
  const load = async (): Promise<void> => {
    const admins = await api<Admin[]>('/api/admin/users');
    root.replaceChildren();
    for (const admin of admins) {
      const row = document.createElement('article'); row.className = 'admin-user-row';
      const copy = document.createElement('div'); copy.className = 'admin-user-copy';
      const name = document.createElement('strong'); name.textContent = admin.displayName;
      const detail = document.createElement('small');
      const email = element('span', '', admin.email); email.dataset.noTranslate = '';
      detail.append(email, ' · ', element('span', '', admin.role === 'ADMIN' ? 'Admin' : 'User'), ' · ',
        element('span', '', admin.enabled ? 'Enabled' : 'Disabled'), ' · ',
        element('span', '', admin.totpEnabled ? '2FA active' : 'Enrollment pending'));
      copy.append(name, detail); row.append(copy);
      if (admin.id !== selfId) {
        const actions = document.createElement('div'); actions.className = 'admin-user-actions';
        if (admin.enabled) {
          const disable = document.createElement('button'); disable.className = 'button button-ghost'; disable.textContent = 'Disable';
          disable.addEventListener('click', async () => {
            if (!await ask('Disable user?', `Disable ${admin.email} and revoke all sessions?`, 'Disable account')) return;
            try { await api(`/api/admin/users/${admin.id}`, { method: 'DELETE' }); await load(); } catch (error) { showError(error); }
          }); actions.append(disable);
        } else {
          const enable = document.createElement('button'); enable.className = 'button button-ghost'; enable.textContent = 'Enable';
          enable.addEventListener('click', async () => { try { await api(`/api/admin/users/${admin.id}/enable`, { method: 'POST' }); await load(); } catch (error) { showError(error); } });
          const remove = document.createElement('button'); remove.className = 'button button-danger'; remove.textContent = 'Delete';
          remove.addEventListener('click', async () => {
            if (!await ask('Delete user permanently?', `Delete ${admin.email} and their Dispatch mail accounts, cached messages, drafts, contacts and saved responses? This cannot be undone. Remote mailboxes will not be deleted.`, 'Delete account')) return;
            try { await api(`/api/admin/users/${admin.id}/permanent`, { method: 'DELETE' }); await load(); } catch (error) { showError(error); }
          }); actions.append(enable, remove);
        }
        row.append(actions);
      }
      root.append(row);
    }
  };
  $('[data-open-admin-create]').addEventListener('click', () => create.showModal());
  document.querySelectorAll('[data-close-admin-create]').forEach(button => button.addEventListener('click', () => create.close()));
  $<HTMLFormElement>('[data-admin-form]').addEventListener('submit', async event => {
    event.preventDefault();
    const form = event.currentTarget as HTMLFormElement;
    const submit = form.querySelector<HTMLButtonElement>('[type=submit]')!; submit.disabled = true;
    $('[data-admin-form-error]').textContent = '';
    try {
      await api('/api/admin/users', { method: 'POST', body: JSON.stringify(Object.fromEntries(new FormData(form))) });
      form.reset(); create.close(); await load();
    } catch (error) { $('[data-admin-form-error]').textContent = errorText(error); }
    finally { submit.disabled = false; }
  });
  void load().catch(showError);
}
