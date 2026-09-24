// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest';
import { JSDOM } from 'jsdom';
import { applyAccountAvailability, ensureMessageLoading, formatMessageDate, messageContentUrl, profileInitials, safeExternalLink, setPinButtonState, setReadButtonState, setStarButtonState, setSynchronizedStarState, shouldAnimatePin, totalUnreadCount } from './mail';

describe('browser failure state', () => {
  it('shows a real aggregate unread count without rendering folder shortcuts', () => {
    expect(totalUnreadCount([{ unreadCount: 4 }, { unreadCount: 2 }, { unreadCount: 0 }])).toBe(6);
    expect(totalUnreadCount([
      { unreadCount: 0, specialUse: 'ALL' },
      { unreadCount: 1, specialUse: 'TRASH' },
      { unreadCount: 2, specialUse: 'JUNK' },
    ])).toBe(0);
  });

  it('formats message dates by today, last week, and older mail', () => {
    const now = new Date(2026, 7, 21, 18, 30);
    expect(formatMessageDate(new Date(2026, 7, 21, 13, 57).toISOString(), now)).toBe('13:57');
    expect(formatMessageDate(new Date(2026, 7, 15, 13, 0).toISOString(), now)).toBe('13:00 Sat');
    expect(formatMessageDate(new Date(2026, 7, 10, 9, 5).toISOString(), now)).toBe('10.08.2026');
  });

  it('keeps remote images and normal HTML rendering as explicit message-view options', () => {
    expect(messageContentUrl(42)).toBe('/api/mail/messages/42/content?externalImages=false&original=false&light=false');
    expect(messageContentUrl(42, true, true)).toBe('/api/mail/messages/42/content?externalImages=true&original=true&light=false');
    expect(messageContentUrl(42, false, false, true)).toBe('/api/mail/messages/42/content?externalImages=false&original=false&light=true');
  });

  it('only accepts web and email destinations for guarded message links', () => {
    expect(safeExternalLink('https://example.com/account')?.host).toBe('example.com');
    expect(safeExternalLink('http://example.com')?.secure).toBe(false);
    expect(safeExternalLink('mailto:support@example.com')?.host).toBe('support@example.com');
    expect(safeExternalLink('javascript:alert(1)')).toBeNull();
  });

  it('uses the first and last name for the profile avatar', () => {
    expect(profileInitials('Berke Akçen')).toBe('BA');
    expect(profileInitials('Berke')).toBe('B');
  });

  it('can render a safe, visible connection error without inserting HTML', () => {
    const dom = new JSDOM('<div class="hidden" data-global-alert></div>');
    const box = dom.window.document.querySelector<HTMLElement>('[data-global-alert]')!;
    const serverMessage = '<img src=x onerror=alert(1)>Connection failed';
    box.textContent = serverMessage;
    box.classList.remove('hidden');
    expect(box.textContent).toBe(serverMessage);
    expect(box.querySelector('img')).toBeNull();
    expect(box.classList.contains('hidden')).toBe(false);
  });

  it('disables composing and shows account setup guidance when no account exists', () => {
    document.body.innerHTML = `
      <button data-compose></button><button data-refresh></button><input data-search>
      <div class="hidden" data-account-empty></div>
      <h2 data-message-empty-title></h2><p data-message-empty-text></p>
      <h2 data-reading-empty-title></h2><p data-reading-empty-text></p>`;

    applyAccountAvailability(false);

    expect(document.querySelector<HTMLButtonElement>('[data-compose]')?.disabled).toBe(true);
    expect(document.querySelector('[data-account-empty]')?.classList.contains('hidden')).toBe(false);
    expect(document.querySelector('[data-message-empty-title]')?.textContent).toBe('Connect your first mail account');
  });

  it('recreates the loading skeleton after the message list has been rendered', () => {
    const list = document.createElement('div');
    const first = ensureMessageLoading(list);
    list.replaceChildren(first);
    list.replaceChildren();

    const recreated = ensureMessageLoading(list);

    expect(recreated).not.toBe(first);
    expect(recreated.matches('[data-message-loading]')).toBe(true);
    expect(recreated.children).toHaveLength(4);
  });

  it('updates a star control without rebuilding the message list', () => {
    const button = document.createElement('button');

    setStarButtonState(button, true);

    expect(button.classList.contains('active')).toBe(true);
    expect(button.getAttribute('aria-pressed')).toBe('true');
    expect(button.title).toBe('Remove star');

    setStarButtonState(button, false);
    expect(button.classList.contains('active')).toBe(false);
    expect(button.getAttribute('aria-pressed')).toBe('false');
  });

  it('keeps inbox and reading-pane star controls synchronized', () => {
    const inboxStar = document.createElement('button');
    const detailStar = document.createElement('button');

    setSynchronizedStarState([inboxStar, detailStar], true);
    expect(inboxStar.classList.contains('active')).toBe(true);
    expect(detailStar.classList.contains('active')).toBe(true);

    setSynchronizedStarState([inboxStar, detailStar], false);
    expect(inboxStar.classList.contains('active')).toBe(false);
    expect(detailStar.classList.contains('active')).toBe(false);
  });

  it('switches the per-message read action between envelope states', () => {
    const button = document.createElement('button');

    setReadButtonState(button, false);
    expect(button.title).toBe('Mark as read');
    expect(button.querySelector('svg')).not.toBeNull();

    setReadButtonState(button, true);
    expect(button.title).toBe('Mark as unread');
    expect(button.getAttribute('aria-label')).toBe('Mark as unread');
  });

  it('skips animation when pinning the first message without changing its position', () => {
    const first = { id: 2, pinned: false, receivedAt: '2026-09-21T12:00:00Z' };
    const second = { id: 1, pinned: false, receivedAt: '2026-09-20T12:00:00Z' };
    expect(shouldAnimatePin(first, [first, second])).toBe(false);
    expect(shouldAnimatePin(second, [first, second])).toBe(true);
  });

  it('skips animation when the only pinned message is already the newest message', () => {
    const first = { id: 2, pinned: true, receivedAt: '2026-09-21T12:00:00Z' };
    const second = { id: 1, pinned: false, receivedAt: '2026-09-20T12:00:00Z' };
    expect(shouldAnimatePin(first, [first, second])).toBe(false);
    expect(shouldAnimatePin(first, [first])).toBe(false);
  });

  it('still animates unpinning when another pin or a newer message moves ahead', () => {
    const first = { id: 1, pinned: true, receivedAt: '2026-09-20T12:00:00Z' };
    const otherPin = { id: 2, pinned: true, receivedAt: '2026-09-19T12:00:00Z' };
    const newerMessage = { id: 3, pinned: false, receivedAt: '2026-09-21T12:00:00Z' };
    expect(shouldAnimatePin(first, [first, otherPin])).toBe(true);
    expect(shouldAnimatePin(first, [first, newerMessage])).toBe(true);
  });

  it('matches the server ID tie-break when unpinning messages received at the same time', () => {
    const first = { id: 2, pinned: true, receivedAt: '2026-09-21T12:00:00Z' };
    const olderId = { id: 1, pinned: false, receivedAt: first.receivedAt };
    const newerId = { id: 3, pinned: false, receivedAt: first.receivedAt };
    expect(shouldAnimatePin(first, [first, olderId])).toBe(false);
    expect(shouldAnimatePin(first, [first, newerId])).toBe(true);
  });

  it('does not assume the first visible row on later pages is globally first', () => {
    const first = { id: 2, pinned: false, receivedAt: '2026-09-21T12:00:00Z' };
    expect(shouldAnimatePin(first, [first], 1)).toBe(true);
    expect(shouldAnimatePin({ ...first, pinned: true }, [{ ...first, pinned: true }], 1)).toBe(true);
  });

  it('restores the original neutral pin control without replacing its icon', () => {
    const button = document.createElement('button');
    const icon = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    button.append(icon);

    setPinButtonState(button, true);
    expect(button.classList.contains('active')).toBe(true);
    expect(button.getAttribute('aria-pressed')).toBe('true');

    setPinButtonState(button, false);
    expect(button.classList.contains('active')).toBe(false);
    expect(button.getAttribute('aria-pressed')).toBe('false');
    expect(button.getAttribute('aria-label')).toBe('Pin message to top');
    expect(button.firstChild).toBe(icon);
  });
});
