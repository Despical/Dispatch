import { api } from './api';

type Session = { current: boolean; ip: string | null; userAgent: string | null; startedAt: string; lastSeenAt: string };
type ActivityEvent = { type: string; outcome: string; ip: string | null; time: string };
type Activity = { sessions: Session[]; events: { content: ActivityEvent[]; page: number; totalPages: number; totalElements: number } };

export function deviceLabel(agent: string | null): string {
  if (!agent) return 'Unknown browser';
  const browser = /Edg(?:e|A|iOS)?\//.test(agent) ? 'Edge' : /OPR\//.test(agent) ? 'Opera' : /Firefox\/|FxiOS\//.test(agent) ? 'Firefox'
    : /Chrome\/|CriOS\//.test(agent) ? 'Chrome' : /Safari\//.test(agent) ? 'Safari' : 'Browser';
  const device = /iPad/.test(agent) ? 'iPad' : /iPhone/.test(agent) ? 'iPhone' : /Android/.test(agent) ? 'Android'
    : /Windows/.test(agent) ? 'Windows' : /Macintosh|Mac OS/.test(agent) ? 'macOS' : /Linux/.test(agent) ? 'Linux' : 'Unknown device';
  return `${browser} on ${device}`;
}

export function initSecurityActivity(closeMenu: () => void) {
  const modal = document.querySelector<HTMLDialogElement>('[data-security-activity-modal]')!;
  const sessions = modal.querySelector<HTMLElement>('[data-security-sessions]')!;
  const events = modal.querySelector<HTMLElement>('[data-security-events]')!;
  const status = modal.querySelector<HTMLElement>('[data-security-activity-status]')!;
  const previous = modal.querySelector<HTMLButtonElement>('[data-security-prev]')!;
  const next = modal.querySelector<HTMLButtonElement>('[data-security-next]')!;
  let page = 0;
  let request = 0;
  const tabs = [...modal.querySelectorAll<HTMLButtonElement>('[data-security-tab]')];
  const selectTab = (name: string) => {
    tabs.forEach(tab => { const selected = tab.dataset.securityTab === name; tab.setAttribute('aria-selected', String(selected)); tab.tabIndex = selected ? 0 : -1; });
    modal.querySelectorAll<HTMLElement>('[data-security-panel]').forEach(panel => { panel.hidden = panel.dataset.securityPanel !== name; });
  };
  tabs.forEach((tab, index) => {
    tab.addEventListener('click', () => selectTab(tab.dataset.securityTab!));
    tab.addEventListener('keydown', event => {
      if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
      event.preventDefault();
      const target = tabs[event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1 : (index + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length];
      selectTab(target.dataset.securityTab!); target.focus();
    });
  });
  const time = (value: string) => value ? new Date(value).toLocaleString() : 'Unknown';
  const line = (tag: string, text: string, className = '') => {
    const item = document.createElement(tag); item.textContent = text; item.className = className; return item;
  };
  const names: Record<string, string> = { LOGIN_PASSWORD: 'Password check', LOGIN_2FA: 'Two-factor sign-in',
    LOGOUT: 'Signed out', LOGOUT_ALL: 'Signed out everywhere', REAUTH: 'Identity verification', REFRESH_REUSE: 'Session reuse blocked' };
  async function load(target: number) {
    const current = ++request;
    previous.disabled = next.disabled = true;
    status.textContent = 'Loading activity…';
    try {
      const result = await api<Activity>(`/api/security/activity?page=${target}`);
      if (current !== request) return;
      page = result.events.page;
      sessions.replaceChildren(); events.replaceChildren();
      for (const session of result.sessions) {
        const item = line('article', '', `security-session${session.current ? ' current-device' : ''}`);
        const heading = line('div', '', 'security-session-heading');
        const device = line('strong', deviceLabel(session.userAgent)); device.title = session.userAgent ?? 'Unknown browser'; device.tabIndex = 0;
        heading.append(device);
        if (session.current) heading.append(line('span', 'This device', 'current-device-badge'));
        item.append(heading, line('span', `IP: ${session.ip ?? 'Unknown'}`),
          line('small', `Signed in ${time(session.startedAt)} · Last active ${time(session.lastSeenAt)}`));
        sessions.append(item);
      }
      if (!result.sessions.length) sessions.append(line('p', 'No active sessions found.', 'muted'));
      for (const event of result.events.content) {
        const item = line('article', '', 'security-event');
        const heading = line('div', '', 'security-event-heading');
        heading.append(line('strong', names[event.type] ?? event.type), line('span', event.outcome === 'SUCCESS' ? 'Successful' : 'Blocked', event.outcome === 'SUCCESS' ? 'security-outcome' : 'security-outcome denied'));
        item.append(heading, line('span', `IP: ${event.ip ?? 'Unknown'}`), line('time', time(event.time)));
        events.append(item);
      }
      if (!result.events.content.length) events.append(line('p', 'No sign-in activity recorded yet.', 'muted'));
      modal.querySelector('[data-security-page]')!.textContent = result.events.totalPages ? `${page + 1} / ${result.events.totalPages}` : '';
      previous.disabled = page === 0; next.disabled = page + 1 >= result.events.totalPages;
      status.textContent = '';
    } catch {
      if (current !== request) return;
      status.textContent = 'Activity could not be loaded. Close this window and try again.';
    }
  }
  document.querySelector('[data-open-security-activity]')!.addEventListener('click', () => {
    closeMenu(); selectTab('sessions'); sessions.replaceChildren(); events.replaceChildren();
    modal.querySelector('[data-security-page]')!.textContent = '';
    modal.showModal(); void load(0);
  });
  modal.querySelector('[data-close-security-activity]')!.addEventListener('click', () => modal.close());
  previous.addEventListener('click', () => void load(page - 1));
  next.addEventListener('click', () => void load(page + 1));
}
