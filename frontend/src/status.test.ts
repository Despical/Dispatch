// @vitest-environment jsdom
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { initStatusPage } from './status';

const { mockedApi } = vi.hoisted(() => ({ mockedApi: vi.fn() }));
vi.mock('./api', () => ({ api: mockedApi }));

const template = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), '../../src/main/resources/templates/status.html'), 'utf8');
const base = {
  overall: 'ISSUE', application: 'UP', host: 'UNKNOWN', alertmanager: 'UP', checkedAt: '2026-09-24T12:00:00Z',
  capacity: { load: '0.5', uptime: '2h', resources: [
    { name: 'Memory', value: '80% used', detail: '80 GB of 100 GB', percent: 80, state: 'WARN' }
  ] },
  services: [
    { name: 'Dispatch application', category: 'Application', detail: 'Admin request handling is available.', state: 'UP', latencyMs: 0 },
    { name: 'Attachment scanner', category: 'Security', detail: 'Attachment scanning is unavailable; downloads remain blocked.', state: 'DOWN', latencyMs: null }
  ],
  alerts: [{ name: 'MailboxSyncDelayed', severity: 'warning', summary: 'Mailbox sync has not completed.' }]
};

beforeEach(() => {
  document.body.innerHTML = new DOMParser().parseFromString(template, 'text/html').body.innerHTML;
  mockedApi.mockReset();
  vi.spyOn(window, 'setInterval').mockReturnValue(0);
});
afterEach(() => vi.restoreAllMocks());

it('explains an issue using the failed service and active alert', async () => {
  mockedApi.mockResolvedValue(base);
  initStatusPage();
  await vi.waitFor(() => expect(document.querySelector('[data-status-overall]')?.textContent).toBe('Some systems need attention'));
  const section = document.querySelector('[data-status-reasons-section]')!;
  expect(section.classList.contains('hidden')).toBe(false);
  expect([...section.querySelectorAll('.admin-status-reason strong')].map(node => node.textContent))
    .toEqual(['Attachment scanner', 'Memory', 'MailboxSyncDelayed']);
  expect(section.textContent).toContain('downloads remain blocked');
  expect(section.textContent).toContain('Mailbox sync has not completed.');
});

it('hides the explanation when every check is healthy', async () => {
  mockedApi.mockResolvedValue({ ...base, overall: 'OPERATIONAL', capacity: { ...base.capacity, resources: [] }, services: [base.services[0]], alerts: [] });
  initStatusPage();
  await vi.waitFor(() => expect(document.querySelector('[data-status-overall]')?.textContent).toBe('All systems operational'));
  expect(document.querySelector('[data-status-reasons-section]')?.classList.contains('hidden')).toBe(true);
});
