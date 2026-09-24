const consentKey = 'dispatch.analytics-consent.v1';

function choice(): string | null {
  try { return localStorage.getItem(consentKey); }
  catch { return null; }
}

function saveChoice(value: 'accepted' | 'essential'): void {
  try { localStorage.setItem(consentKey, value); }
  catch { /* The choice still applies to this page when storage is unavailable. */ }
  window.dispatchEvent(new Event(value === 'accepted' ? 'dispatch:analytics-accepted' : 'dispatch:analytics-denied'));
}

function showNotice(): void {
  if (document.querySelector('.cookie-notice')) return;

  const notice = document.createElement('section');
  notice.className = 'cookie-notice';
  notice.setAttribute('aria-labelledby', 'cookie-notice-title');
  notice.setAttribute('aria-describedby', 'cookie-notice-description');

  const title = document.createElement('h2');
  title.id = 'cookie-notice-title';
  title.textContent = 'Cookies and analytics';
  const description = document.createElement('p');
  description.id = 'cookie-notice-description';
  description.textContent = 'Essential cookies keep sign-in and requests secure. With your permission, Google Analytics measures visits to public pages. You can continue with essential cookies only.';
  const actions = document.createElement('div');
  actions.className = 'cookie-notice-actions';
  const policy = document.createElement('a');
  policy.href = '/privacy-policy#cookies';
  policy.textContent = 'Privacy policy';
  const essential = document.createElement('button');
  essential.type = 'button';
  essential.className = 'cookie-notice-later';
  essential.textContent = 'Essential only';
  essential.addEventListener('click', () => { saveChoice('essential'); notice.remove(); });
  const accept = document.createElement('button');
  accept.type = 'button';
  accept.className = 'cookie-notice-accept';
  accept.textContent = 'Accept analytics';
  accept.addEventListener('click', () => { saveChoice('accepted'); notice.remove(); });
  actions.append(policy, essential, accept);
  notice.append(title, description, actions);
  document.body.append(notice);
}

export function initCookieNotice(): void {
  document.querySelectorAll<HTMLButtonElement>('[data-cookie-settings]')
    .forEach(button => button.addEventListener('click', showNotice));
  if (choice() === null) showNotice();
}
