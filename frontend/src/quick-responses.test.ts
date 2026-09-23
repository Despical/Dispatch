// @vitest-environment jsdom
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { mockedApi } = vi.hoisted(() => ({ mockedApi: vi.fn() }));
vi.mock('./api', () => ({
  api: mockedApi,
  ApiError: class extends Error { constructor(public status: number, message: string) { super(message); } }
}));

const template = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), '../../src/main/resources/templates/mail.html'), 'utf8');
const endpoint = '/api/mail/canned-responses';
const initialResponses = [
  { id: 11, title: 'Thanks for reaching out', bodyHtml: 'Thanks for contacting us. We will get back to you shortly.' },
  { id: 12, title: 'Order update', bodyHtml: 'Your parcel is on its way. Tracking details will follow.' }
];
const element = <T extends HTMLElement>(selector: string): T => document.querySelector<T>(selector)!;
const manager = () => element<HTMLDialogElement>('[data-quick-responses-modal]');
const editor = () => element<HTMLDialogElement>('[data-canned-editor-modal]');
const form = () => element<HTMLFormElement>('[data-canned-form]');
const field = (name: string) => form().elements.namedItem(name) as HTMLInputElement | HTMLTextAreaElement;
const settle = () => vi.advanceTimersByTimeAsync(0);
const change = (name: string, value: string) => {
  field(name).value = value;
  field(name).dispatchEvent(new Event('input', { bubbles: true }));
};
const submit = () => form().dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
const mutations = () => mockedApi.mock.calls.filter(([, options]) => options?.method && options.method !== 'GET');

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(complete => { resolve = complete; });
  return { promise, resolve };
}

describe('quick responses with the real mail template', () => {
  let savedResponses = structuredClone(initialResponses);
  const closeMenu = vi.fn();
  const confirmAction = vi.fn<() => Promise<boolean>>();
  const onChanged = vi.fn<() => Promise<void>>();
  const dialogMethods = new Map<string, PropertyDescriptor | undefined>();
  let cleanupListeners = () => {};

  async function serverResponse(path: string, options?: RequestInit): Promise<unknown> {
    if (path === endpoint && (!options?.method || options.method === 'GET')) return structuredClone(savedResponses);
    if (path === endpoint && options?.method === 'POST') {
      const item = { id: 13, ...JSON.parse(String(options.body)) };
      savedResponses.push(item);
      return structuredClone(item);
    }
    const id = Number(path.slice(`${endpoint}/`.length));
    if (path.startsWith(`${endpoint}/`) && options?.method === 'PUT') {
      const item = { id, ...JSON.parse(String(options.body)) };
      savedResponses = savedResponses.map(response => response.id === id ? item : response);
      return structuredClone(item);
    }
    if (path.startsWith(`${endpoint}/`) && options?.method === 'DELETE') {
      savedResponses = savedResponses.filter(response => response.id !== id);
      return undefined;
    }
    throw new Error(`Unexpected mocked API call: ${options?.method ?? 'GET'} ${path}`);
  }

  async function openManager() {
    element<HTMLButtonElement>('[data-open-quick-responses]').click();
    await settle();
    expect(manager().open).toBe(true);
  }

  async function openCreate() {
    await openManager();
    element<HTMLButtonElement>('[data-open-canned-create]').click();
    await settle();
    expect(editor().open).toBe(true);
    expect(manager().open).toBe(false);
  }

  beforeEach(async () => {
    vi.resetModules();
    vi.useFakeTimers();
    savedResponses = structuredClone(initialResponses);
    mockedApi.mockReset().mockImplementation(serverResponse);
    closeMenu.mockReset();
    confirmAction.mockReset().mockResolvedValue(true);
    onChanged.mockReset().mockResolvedValue(undefined);
    document.body.innerHTML = new DOMParser().parseFromString(template, 'text/html').body.innerHTML;
    const documentListeners = vi.spyOn(document, 'addEventListener');
    const windowListeners = vi.spyOn(window, 'addEventListener');
    cleanupListeners = () => {
      for (const [type, listener, options] of documentListeners.mock.calls) document.removeEventListener(type, listener, options);
      for (const [type, listener, options] of windowListeners.mock.calls) window.removeEventListener(type, listener, options);
    };
    for (const name of ['show', 'showModal', 'close']) dialogMethods.set(name, Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, name));
    Object.defineProperties(HTMLDialogElement.prototype, {
      show: { configurable: true, value: function(this: HTMLDialogElement) { this.open = true; } },
      showModal: { configurable: true, value: function(this: HTMLDialogElement) { this.open = true; } },
      close: { configurable: true, value: function(this: HTMLDialogElement) { this.open = false; this.dispatchEvent(new Event('close')); } }
    });
    const { initQuickResponses } = await import('./quick-responses');
    initQuickResponses({ closeMenu, confirmAction, onChanged });
  });

  afterEach(() => {
    cleanupListeners();
    for (const [name, descriptor] of dialogMethods) {
      if (descriptor) Object.defineProperty(HTMLDialogElement.prototype, name, descriptor);
      else Reflect.deleteProperty(HTMLDialogElement.prototype, name);
    }
    vi.clearAllTimers();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('shows saved titles and body excerpts, and searches both', async () => {
    await openManager();
    expect(closeMenu).toHaveBeenCalledOnce();
    expect(element('[data-canned-list]').textContent).toContain(initialResponses[0].title);
    expect(element('[data-canned-list]').textContent).toContain('Tracking details will follow.');
    expect(document.querySelector('[data-canned-count]')).toBeNull();

    const search = element<HTMLInputElement>('[data-canned-search]');
    search.value = 'parcel';
    search.dispatchEvent(new Event('input', { bubbles: true }));
    await settle();
    expect(document.querySelector('[data-canned-id="11"]')).toBeNull();
    expect(document.querySelector('[data-canned-id="12"]')).not.toBeNull();
    search.value = 'THANKS FOR REACHING';
    search.dispatchEvent(new Event('input', { bubbles: true }));
    await settle();
    expect(document.querySelector('[data-canned-id="11"]')).not.toBeNull();
    expect(document.querySelector('[data-canned-id="12"]')).toBeNull();
  });

  it('creates a response in its own editor and refreshes the saved list and composer', async () => {
    await openCreate();
    expect(field('title').value).toBe('');
    expect(field('bodyHtml').value).toBe('');
    change('title', 'Return instructions');
    change('bodyHtml', 'Please include your order number.');
    submit();
    await settle();

    expect(mutations()).toEqual([[endpoint, expect.objectContaining({ method: 'POST', body: JSON.stringify({ title: 'Return instructions', bodyHtml: 'Please include your order number.' }) })]]);
    expect(editor().open).toBe(false);
    expect(manager().open).toBe(true);
    expect(element('[data-canned-id="13"]').textContent).toContain('Return instructions');
    expect(onChanged).toHaveBeenCalledOnce();
  });

  it('edits the selected response and previews user input literally', async () => {
    await openManager();
    element<HTMLButtonElement>('[aria-label="Edit Order update"]').click();
    await settle();
    expect(field('title').value).toBe('Order update');
    expect(field('bodyHtml').value).toBe(initialResponses[1].bodyHtml);
    change('title', 'Shipping <update>');
    change('bodyHtml', '<img src=x onerror="alert(1)">\nYour parcel is ready.');
    expect(element('[data-canned-preview-title]').textContent).toBe('Shipping <update>');
    expect(element('[data-canned-preview-body]').textContent).toBe('<img src=x onerror="alert(1)">\nYour parcel is ready.');
    expect(element('[data-canned-preview-body]').querySelector('img')).toBeNull();
    submit();
    await settle();

    const [[path, options]] = mutations();
    expect(path).toBe(`${endpoint}/12`);
    expect(options.method).toBe('PUT');
    expect(JSON.parse(options.body)).toEqual({ title: 'Shipping <update>', bodyHtml: '<img src=x onerror="alert(1)">\nYour parcel is ready.' });
    expect(element('[data-canned-id="12"]').textContent).toContain('Shipping <update>');
    expect(onChanged).toHaveBeenCalledOnce();
  });

  it('keeps the editor and typed response after a save failure', async () => {
    await openCreate();
    change('title', 'Unsent changes');
    change('bodyHtml', 'Keep this response if the network fails.');
    mockedApi.mockImplementation(async (path: string, options?: RequestInit) => {
      if (options?.method === 'POST') throw new Error('The server is unavailable. Try again.');
      return serverResponse(path, options);
    });
    submit();
    await settle();
    expect(editor().open).toBe(true);
    expect(field('title').value).toBe('Unsent changes');
    expect(field('bodyHtml').value).toBe('Keep this response if the network fails.');
    expect(editor().textContent).toContain('The server is unavailable. Try again.');
    expect(element<HTMLButtonElement>('[data-canned-save]').disabled).toBe(false);
    expect(onChanged).not.toHaveBeenCalled();
  });

  it('confirms deletion and refreshes the list and composer only after acceptance', async () => {
    await openManager();
    confirmAction.mockResolvedValueOnce(false);
    element<HTMLButtonElement>('[aria-label="Delete Order update"]').click();
    await settle();
    expect(confirmAction).toHaveBeenCalledOnce();
    expect(mutations()).toHaveLength(0);
    expect(document.querySelector('[data-canned-id="12"]')).not.toBeNull();
    element<HTMLButtonElement>('[aria-label="Delete Order update"]').click();
    await vi.advanceTimersByTimeAsync(200);
    expect(mutations()).toEqual([[`${endpoint}/12`, expect.objectContaining({ method: 'DELETE' })]]);
    expect(document.querySelector('[data-canned-id="12"]')).toBeNull();
    expect(document.querySelector('[data-canned-count]')).toBeNull();
    expect(onChanged).toHaveBeenCalledOnce();
  });

  it('sends one create request while a save is already pending', async () => {
    await openCreate();
    change('title', 'Single response');
    change('bodyHtml', 'This response should only be saved once.');
    const pending = deferred<unknown>();
    mockedApi.mockImplementation((path: string, options?: RequestInit) => options?.method === 'POST' ? pending.promise : serverResponse(path, options));
    submit();
    submit();
    await settle();
    expect(mutations()).toHaveLength(1);
    expect(element<HTMLButtonElement>('[data-canned-save]').disabled).toBe(true);
    savedResponses.push({ id: 13, title: field('title').value, bodyHtml: field('bodyHtml').value });
    pending.resolve(savedResponses.at(-1));
    await settle();
    expect(editor().open).toBe(false);
    expect(onChanged).toHaveBeenCalledOnce();
  });

  it('keeps unsaved edits when discard is rejected and returns to the manager when accepted', async () => {
    await openCreate();
    change('title', 'Work in progress');
    confirmAction.mockResolvedValueOnce(false);
    element<HTMLButtonElement>('[data-close-canned-editor]').click();
    await settle();
    expect(editor().open).toBe(true);
    expect(field('title').value).toBe('Work in progress');
    element<HTMLButtonElement>('[data-close-canned-editor]').click();
    await settle();
    expect(editor().open).toBe(false);
    expect(manager().open).toBe(true);
    expect(mutations()).toHaveLength(0);
  });

  it('preserves existing and newly saved responses when the initial list arrives late', async () => {
    const initialLoad = deferred<unknown>();
    let firstLoad = true;
    mockedApi.mockImplementation((path: string, options?: RequestInit) => {
      if (path === endpoint && !options?.method && firstLoad) {
        firstLoad = false;
        return initialLoad.promise;
      }
      return serverResponse(path, options);
    });
    await openCreate();
    change('title', 'Saved before loading finishes');
    change('bodyHtml', 'The first request is still in flight.');
    submit();
    await settle();
    expect(document.querySelector('[data-canned-count]')).toBeNull();
    initialLoad.resolve(structuredClone(initialResponses));
    await settle();
    expect(document.querySelector('[data-canned-count]')).toBeNull();
    expect(document.querySelector('[data-canned-id="11"]')).not.toBeNull();
    expect(document.querySelector('[data-canned-id="12"]')).not.toBeNull();
    expect(element('[data-canned-id="13"]').textContent).toContain('Saved before loading finishes');
  });

  it('recovers the saved list and search after a failed load is retried', async () => {
    mockedApi.mockRejectedValueOnce(new Error('Responses are temporarily unavailable.'));
    await openManager();
    expect(element('[data-canned-list]').textContent).toContain('Responses are temporarily unavailable.');
    expect(element<HTMLInputElement>('[data-canned-search]').disabled).toBe(true);
    element<HTMLButtonElement>('[data-canned-list] button').click();
    await settle();
    expect(element<HTMLInputElement>('[data-canned-search]').disabled).toBe(false);
    expect(document.querySelector('[data-canned-count]')).toBeNull();
    expect(document.querySelector('[data-canned-id="11"]')).not.toBeNull();
    expect(document.querySelector('[data-canned-id="12"]')).not.toBeNull();
  });

  it('does not resurrect a deleted response when a reopened list returns an older snapshot', async () => {
    await openManager();
    const deletion = deferred<unknown>();
    const staleLoad = deferred<unknown>();
    let firstReload = true;
    mockedApi.mockImplementation((path: string, options?: RequestInit) => {
      if (options?.method === 'DELETE') return deletion.promise;
      if (path === endpoint && !options?.method && firstReload) {
        firstReload = false;
        return staleLoad.promise;
      }
      return serverResponse(path, options);
    });
    element<HTMLButtonElement>('[aria-label="Delete Order update"]').click();
    await settle();
    element<HTMLButtonElement>('[data-close-quick-responses]').click();
    await openManager();
    savedResponses = savedResponses.filter(response => response.id !== 12);
    deletion.resolve(undefined);
    await vi.advanceTimersByTimeAsync(200);
    expect(document.querySelector('[data-canned-id="12"]')).toBeNull();
    staleLoad.resolve(structuredClone(initialResponses));
    await settle();
    expect(document.querySelector('[data-canned-id="12"]')).toBeNull();
    expect(document.querySelector('[data-canned-count]')).toBeNull();
    expect(element<HTMLInputElement>('[data-canned-search]').disabled).toBe(false);
  });
});
