import { api } from './api';

type QuickResponse = { id: number; title: string; bodyHtml: string };
type Options = {
  closeMenu: () => void;
  confirmAction: (title: string, message: string, confirmLabel: string) => Promise<boolean>;
  onChanged: () => Promise<void>;
};

const icon = (path: string) => {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 24 24'); svg.setAttribute('aria-hidden', 'true');
  const shape = document.createElementNS(svg.namespaceURI, 'path'); shape.setAttribute('d', path); svg.append(shape);
  return svg;
};
const text = (tag: string, className: string, value = '') => {
  const element = document.createElement(tag); element.className = className; element.textContent = value; return element;
};
const errorMessage = (error: unknown) => error instanceof Error ? error.message : 'The response could not be saved. Please try again.';

export function initQuickResponses({ closeMenu, confirmAction, onChanged }: Options): void {
  const $ = <T extends Element>(selector: string) => document.querySelector<T>(selector)!;
  const manager = $<HTMLDialogElement>('[data-quick-responses-modal]');
  const editor = $<HTMLDialogElement>('[data-canned-editor-modal]');
  const form = $<HTMLFormElement>('[data-canned-form]');
  const title = form.elements.namedItem('title') as HTMLInputElement;
  const body = form.elements.namedItem('bodyHtml') as HTMLTextAreaElement;
  const search = $<HTMLInputElement>('[data-canned-search]');
  const list = $<HTMLElement>('[data-canned-list]');
  const submit = $<HTMLButtonElement>('[data-canned-save]');
  const notice = $<HTMLElement>('[data-canned-notice]');
  const error = $<HTMLElement>('[data-canned-error]');
  let items: QuickResponse[] = [], editingId: number | null = null;
  let initialTitle = '', initialBody = '', saving = false, closing = false, loadRevision = 0, loaded = false;
  const deleting = new Set<number>();

  function announce(message: string): void { notice.textContent = message; notice.classList.toggle('hidden', !message); }

  function render(): void {
    list.replaceChildren();
    const query = search.value.trim().toLocaleLowerCase();
    const matches = items.filter(item => `${item.title}\n${item.bodyHtml}`.toLocaleLowerCase().includes(query));
    if (!matches.length) {
      const empty = text('div', 'quick-response-empty');
      empty.append(icon('M4 5h16v12H8l-4 4zM8 9h8M8 13h5'));
      empty.append(text('h3', '', query ? 'No matching responses' : 'Your replies, a little faster'));
      empty.append(text('p', '', query ? 'Try a different title or phrase.' : 'Save a reply you use often. It will be ready in the composer.'));
      list.append(empty); return;
    }
    for (const item of matches) {
      const row = text('div', 'quick-response-row'); row.dataset.cannedId = String(item.id);
      const open = document.createElement('button'); open.type = 'button'; open.className = 'quick-response-open'; open.setAttribute('aria-label', `Edit ${item.title}`);
      const badge = text('span', 'quick-response-icon'); badge.append(icon('M4 5h16v12H8l-4 4zM8 9h8M8 13h5'));
      const copy = text('span', 'quick-response-copy');
      copy.append(text('strong', '', item.title), text('span', '', item.bodyHtml.replace(/\s+/g, ' ').trim()));
      const arrow = text('span', 'quick-response-open-hint'); arrow.append(icon('m9 6 6 6-6 6'));
      open.append(badge, copy, arrow); open.addEventListener('click', () => openEditor(item));
      const remove = document.createElement('button'); remove.type = 'button'; remove.className = 'quick-response-delete'; remove.title = 'Delete response'; remove.setAttribute('aria-label', `Delete ${item.title}`);
      remove.append(icon('M3 6h18M8 6V4h8v2m-9 0 1 14h8l1-14M10 10v6m4-6v6'));
      remove.addEventListener('click', () => { void deleteResponse(item, row, remove); });
      row.append(open, remove); list.append(row);
    }
  }

  async function load(): Promise<void> {
    const revision = ++loadRevision;
    loaded = false; search.disabled = true;
    list.setAttribute('aria-busy', 'true'); list.replaceChildren(text('p', 'quick-response-loading', 'Loading responses…'));
    try {
      const result = await api<QuickResponse[]>('/api/mail/canned-responses');
      if (revision !== loadRevision) return;
      items = result; loaded = true; search.disabled = false; render();
    } catch (failure) {
      if (revision !== loadRevision) return;
      const retry = document.createElement('button'); retry.type = 'button'; retry.className = 'button button-ghost'; retry.textContent = 'Try again';
      retry.addEventListener('click', () => { void load(); });
      const empty = text('div', 'quick-response-empty'); empty.setAttribute('role', 'alert');
      empty.append(text('h3', '', 'Responses could not be loaded'), text('p', '', errorMessage(failure)), retry); list.replaceChildren(empty);
    } finally { if (revision === loadRevision) list.setAttribute('aria-busy', 'false'); }
  }

  function updatePreview(): void {
    $('[data-canned-preview-title]').textContent = title.value.trim() || 'Untitled response';
    const preview = $('[data-canned-preview-body]');
    preview.textContent = body.value.trim() ? body.value : 'Your response will appear here as you write.';
    preview.classList.toggle('is-placeholder', !body.value.trim());
    $('[data-canned-character-count]').textContent = `${body.value.length.toLocaleString()} ${body.value.length === 1 ? 'character' : 'characters'}`;
    submit.disabled = saving || !title.value.trim() || !body.value.trim();
  }

  function openEditor(item?: QuickResponse): void {
    editingId = item?.id ?? null; title.value = initialTitle = item?.title ?? ''; body.value = initialBody = item?.bodyHtml ?? '';
    error.classList.add('hidden'); error.textContent = '';
    $('[data-canned-editor-title]').textContent = item ? 'Edit response' : 'New response';
    submit.textContent = item ? 'Save changes' : 'Save response';
    updatePreview(); manager.close(); editor.showModal(); title.focus();
  }

  async function closeEditor(): Promise<void> {
    if (saving || closing) return;
    closing = true;
    try {
      if ((title.value !== initialTitle || body.value !== initialBody) &&
        !await confirmAction('Discard changes?', 'Your changes to this quick response have not been saved.', 'Discard changes')) return;
      editor.close(); manager.showModal();
      if (loaded) render(); else void load();
    } finally { closing = false; }
  }

  async function deleteResponse(item: QuickResponse, row: HTMLElement, remove: HTMLButtonElement): Promise<void> {
    if (deleting.has(item.id)) return;
    deleting.add(item.id); remove.disabled = true;
    const open = row.querySelector<HTMLButtonElement>('.quick-response-open')!; open.disabled = true;
    try {
      if (!await confirmAction('Delete quick response?', `Delete the quick response “${item.title}”?`, 'Delete response')) return;
      await api<void>(`/api/mail/canned-responses/${item.id}`, { method: 'DELETE' });
      ++loadRevision;
      row.classList.add('removing');
      await new Promise(resolve => window.setTimeout(resolve, 180));
      items = items.filter(response => response.id !== item.id); render(); announce('Response deleted.');
      if (!loaded) void load();
      void onChanged().catch(() => {});
    } catch (failure) { announce(errorMessage(failure)); }
    finally { deleting.delete(item.id); remove.disabled = false; open.disabled = false; }
  }

  document.querySelectorAll('[data-open-quick-responses]').forEach(button => button.addEventListener('click', () => {
    closeMenu(); search.value = ''; announce(''); manager.showModal(); void load();
  }));
  $('[data-close-quick-responses]').addEventListener('click', () => manager.close());
  document.querySelectorAll('[data-open-canned-create]').forEach(button => button.addEventListener('click', () => openEditor()));
  document.querySelectorAll('[data-close-canned-editor]').forEach(button => button.addEventListener('click', () => { void closeEditor(); }));
  editor.addEventListener('cancel', event => { event.preventDefault(); void closeEditor(); });
  editor.addEventListener('click', event => { if (event.target === editor) void closeEditor(); });
  search.addEventListener('input', render);
  form.addEventListener('input', updatePreview);
  form.addEventListener('submit', async event => {
    event.preventDefault(); if (saving || !form.reportValidity() || !title.value.trim() || !body.value.trim()) return;
    saving = true; error.classList.add('hidden');
    const controls = [...form.querySelectorAll<HTMLInputElement | HTMLTextAreaElement | HTMLButtonElement>('input, textarea, button')];
    controls.forEach(control => { control.disabled = true; }); submit.textContent = 'Saving…';
    try {
      const saved = await api<QuickResponse>(`/api/mail/canned-responses${editingId === null ? '' : `/${editingId}`}`, {
        method: editingId === null ? 'POST' : 'PUT', body: JSON.stringify({ title: title.value.trim(), bodyHtml: body.value })
      });
      ++loadRevision;
      items = [...items.filter(item => item.id !== saved.id), saved].sort((a, b) => a.title.localeCompare(b.title));
      editor.close(); search.value = ''; manager.showModal(); render(); announce(editingId === null ? 'Response saved.' : 'Response updated.');
      if (!loaded) void load();
      void onChanged().catch(() => {});
    } catch (failure) { error.textContent = errorMessage(failure); error.classList.remove('hidden'); }
    finally {
      saving = false; controls.forEach(control => { control.disabled = false; });
      submit.textContent = editingId === null ? 'Save response' : 'Save changes'; updatePreview();
    }
  });
}
