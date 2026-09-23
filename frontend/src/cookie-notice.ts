const acceptedKey = 'dispatch.cookie-notice.accepted';
const deferredKey = 'dispatch.cookie-notice.deferred';

function stored(kind: 'local' | 'session', key: string): boolean {
  try { return (kind === 'local' ? localStorage : sessionStorage).getItem(key) === 'yes'; }
  catch { return false; }
}

function remember(kind: 'local' | 'session', key: string): void {
  try { (kind === 'local' ? localStorage : sessionStorage).setItem(key, 'yes'); }
  catch { /* The notice can still be dismissed when storage is unavailable. */ }
}

export function initCookieNotice(): void {
  if (stored('local', acceptedKey) || stored('session', deferredKey) || document.querySelector('.cookie-notice')) return;

  const notice = document.createElement('aside');
  notice.className = 'cookie-notice';
  notice.setAttribute('role', 'dialog');
  notice.setAttribute('aria-labelledby', 'cookie-notice-title');
  notice.setAttribute('aria-describedby', 'cookie-notice-description');

  const title = document.createElement('h2');
  title.id = 'cookie-notice-title';
  title.textContent = 'Essential cookies';
  const description = document.createElement('p');
  description.id = 'cookie-notice-description';
  description.textContent = 'Dispatch uses cookies to keep you signed in and protect requests. Signing in requires them. We do not use advertising cookies.';
  const actions = document.createElement('div');
  actions.className = 'cookie-notice-actions';
  const policy = document.createElement('a');
  policy.href = '/privacy-policy#cookies';
  policy.textContent = 'Privacy policy';
  const later = document.createElement('button');
  later.type = 'button';
  later.className = 'cookie-notice-later';
  later.textContent = 'Later';
  later.addEventListener('click', () => { remember('session', deferredKey); notice.remove(); });
  const accept = document.createElement('button');
  accept.type = 'button';
  accept.className = 'cookie-notice-accept';
  accept.textContent = 'Accept cookies';
  accept.addEventListener('click', () => { remember('local', acceptedKey); notice.remove(); });
  actions.append(policy, later, accept);
  notice.append(title, description, actions);
  document.body.append(notice);
}
