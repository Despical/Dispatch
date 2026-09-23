import { api } from './api';

type Alert = { name: string; severity: string; summary: string };
type Resource = { name: string; value: string; detail: string; percent: number; state: string };
type Service = { name: string; category: string; detail: string; state: string; latencyMs: number | null };
type Status = { overall: string; application: string; host: string; alertmanager: string; alerts: Alert[]; checkedAt: string; capacity: { resources: Resource[]; load: string; uptime: string }; services: Service[] };

const item = <T extends HTMLElement>(selector: string): T => document.querySelector<T>(selector)!;
const element = (tag: string, className: string, value?: string): HTMLElement => {
  const result = document.createElement(tag); result.className = className;
  if (value !== undefined) result.textContent = value;
  return result;
};
const label = (state: string): string => state === 'UP' ? 'Operational' : state === 'DOWN' ? 'Unavailable' : state === 'WARN' ? 'Attention' : 'Unknown';

export function initStatusPage(): void {
  let loading = false;
  const show = async (): Promise<void> => {
    if (loading) return;
    loading = true;
    const banner = item<HTMLElement>('.admin-status-banner');
    try {
      const status = await api<Status>('/api/admin/status');
      banner.dataset.statusOverallState = status.overall;
      item('[data-status-overall]').textContent = status.overall === 'OPERATIONAL' ? 'All systems operational' : status.overall === 'ISSUE' ? 'Some systems need attention' : 'Monitoring data incomplete';
      item('[data-status-summary]').textContent = status.overall === 'OPERATIONAL' ? 'All monitored systems are operational.' : status.overall === 'ISSUE' ? 'Review the service checks and active alerts below.' : 'One or more monitoring services cannot be reached.';
      item('[data-status-updated]').textContent = `Checked ${new Date(status.checkedAt).toLocaleString()}`;
      item('[data-status-load]').textContent = status.capacity.load;
      item('[data-status-uptime]').textContent = status.capacity.uptime;
      const resources = item('[data-status-resources]'); resources.replaceChildren();
      for (const resource of status.capacity.resources) {
        const card = element('article', 'admin-status-resource'); card.dataset.state = resource.state;
        const top = element('div', 'admin-status-resource-top');
        top.append(element('span', '', resource.name), element('span', 'admin-status-resource-state', resource.state === 'UP' ? 'Healthy' : label(resource.state)));
        const track = element('div', 'admin-status-resource-track'); track.setAttribute('role', 'meter'); track.setAttribute('aria-label', `${resource.name} utilization`); track.setAttribute('aria-valuemin', '0'); track.setAttribute('aria-valuemax', '100'); track.setAttribute('aria-valuenow', String(resource.percent));
        const fill = element('span', ''); fill.style.width = `${Math.max(0, Math.min(100, resource.percent))}%`; track.append(fill);
        card.append(top, element('strong', 'admin-status-resource-value', resource.value), element('p', '', resource.detail), track, element('small', '', `${resource.percent}% utilized`));
        resources.append(card);
      }
      const services = item('[data-status-services]'); services.replaceChildren();
      for (const service of status.services) {
        const row = element('article', 'admin-status-service'); row.dataset.state = service.state;
        const copy = element('div', 'admin-status-service-copy'); const title = element('div', 'admin-status-service-title');
        title.append(element('strong', '', service.name), element('span', '', service.category));
        copy.append(title, element('p', '', service.detail));
        const result = element('div', 'admin-status-service-result'); result.append(element('strong', '', label(service.state)));
        if (service.latencyMs !== null) result.append(element('small', '', `${service.latencyMs} ms`));
        row.append(element('span', 'admin-status-service-light'), copy, result); services.append(row);
      }
      const alerts = item('[data-status-alerts]'); alerts.replaceChildren();
      if (!status.alerts.length) alerts.append(element('p', 'admin-status-no-alerts', status.alertmanager === 'UP' ? 'No active alerts.' : 'Alert information is unavailable.'));
      else for (const alert of status.alerts) {
        const row = element('article', 'admin-status-alert'); const copy = element('div', '');
        copy.append(element('strong', '', alert.name), element('p', '', alert.summary));
        row.append(copy, element('span', '', alert.severity)); alerts.append(row);
      }
      item('[data-status-error]').textContent = '';
    } catch {
      banner.dataset.statusOverallState = 'UNKNOWN';
      item('[data-status-overall]').textContent = 'Status unavailable';
      item('[data-status-summary]').textContent = 'Could not load current system health.';
      item('[data-status-resources]').replaceChildren(element('p', 'admin-status-loading', 'Host capacity unavailable.'));
      item('[data-status-services]').replaceChildren(element('p', 'admin-status-loading', 'Service checks unavailable.'));
      item('[data-status-alerts]').replaceChildren(element('p', 'admin-status-no-alerts', 'Alert information unavailable.'));
      item('[data-status-error]').textContent = 'Status will update automatically.';
    } finally { loading = false; }
  };
  void show();
  window.setInterval(() => { if (!document.hidden) void show(); }, 30_000);
}
