// @vitest-environment jsdom
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from './api';

const { mockedApi } = vi.hoisted(() => ({ mockedApi: vi.fn() }));
vi.mock('./api', () => ({
  api: mockedApi,
  ApiError: class extends Error { constructor(public status: number, message: string) { super(message); } }
}));

const template = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), '../../src/main/resources/templates/mail.html'), 'utf8');
const accounts = [
  { id: 1, displayName: 'Personal', email: 'personal@example.com', authProvider: 'PASSWORD', active: true },
  { id: 2, displayName: 'Work', email: 'work@example.com', authProvider: 'GOOGLE', active: true }
];
const messages = [
  { id: 101, accountId: 2, accountName: 'Work', subject: 'First conversation', fromAddress: 'Alex <alex@example.com>',
    recipients: 'work@example.com, Sam <sam@example.com>', textBody: 'Original first message', internetMessageId: '<first@example.com>', referencesHeader: '<earlier@example.com>',
    preview: 'First preview', receivedAt: '2026-09-21T12:00:00Z', read: true, starred: false, pinned: false, hasAttachments: false, attachments: [] },
  { id: 102, accountId: 1, accountName: 'Personal', subject: 'Second conversation', fromAddress: 'Pat <pat@example.com>',
    recipients: 'personal@example.com', textBody: 'Original second message', internetMessageId: '<second@example.com>', referencesHeader: null,
    preview: 'Second preview', receivedAt: '2026-09-20T12:00:00Z', read: true, starred: false, pinned: false, hasAttachments: false, attachments: [] }
];

const element = <T extends HTMLElement>(selector: string): T => document.querySelector<T>(selector)!;
const form = () => element<HTMLFormElement>('[data-compose-form]');
const field = (name: string) => form().elements.namedItem(name) as HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement;
const modal = () => element<HTMLDialogElement>('[data-compose-modal]');
const settle = () => vi.advanceTimersByTimeAsync(0);
const draftCalls = () => mockedApi.mock.calls.filter(([path, options]) => options?.method && /^\/api\/mail\/outbound\/drafts(?:\/[^/]+)?$/.test(path));
const savedPayload = () => JSON.parse(draftCalls().at(-1)?.[1].body ?? '{}');
const change = (name: string, value: string) => { field(name).value = value; field(name).dispatchEvent(new Event('input', { bubbles: true })); };

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => { resolve = resolvePromise; reject = rejectPromise; });
  return { promise, resolve, reject };
}

async function defaultResponse(path: string): Promise<unknown> {
  if (path === '/api/mail/sharing') return {outgoing:[],incoming:[],ownAccounts:accounts};
  if (path === '/api/mail/contacts') return [];
  if (path.startsWith('/api/mail/trash/count')) return { count: 0 };
  if (path.startsWith('/api/mail/trash?')) return { content: [], page: 0, totalPages: 0, totalElements: 0 };
  if (path === '/api/mail/outbound/drafts/count') return { count: 0 };
  if (path === '/api/system/storage') return { usedBytes: 0, totalBytes: 100, usedPercent: 0 };
  if (path === '/api/mail/accounts') return structuredClone(accounts);
  if (path.startsWith('/api/mail/folders?')) return [];
  if (path.startsWith('/api/mail/messages?')) return { content: structuredClone(messages), totalPages: 1, page: 0, totalElements: messages.length };
  if (/^\/api\/mail\/messages\/\d+$/.test(path)) return structuredClone(messages.find(message => path.endsWith(String(message.id))));
  if (path === '/api/mail/canned-responses') return [];
  if (path.includes('/sync')) return undefined;
  if (path.startsWith('/api/mail/outbound/drafts')) return { id: 'draft-1', status: 'DRAFT' };
  if (path === '/api/mail/outbound/send') return { id: 'draft-1', status: 'QUEUED' };
  throw new Error(`Unexpected mocked API call: ${path}`);
}

async function openReplyAll() {
  element<HTMLButtonElement>('[data-message-id="101"] .message-open').click();
  await settle();
  element<HTMLButtonElement>('[data-reply-all]').click();
  await settle();
}

describe('mail interactions with the real mail template', () => {
  it('closes the mobile folder drawer from its backdrop and after navigation', async () => {
    const toggle = element<HTMLButtonElement>('[data-open-nav]');
    const pane = element<HTMLElement>('[data-account-pane]');
    toggle.click();
    expect(pane.classList.contains('open')).toBe(true);
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    element<HTMLButtonElement>('[data-close-nav]').click();
    expect(pane.classList.contains('open')).toBe(false);
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    toggle.click();
    element<HTMLButtonElement>('[data-trash]').click(); await settle();
    expect(pane.classList.contains('open')).toBe(false);
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
  });
  it('shows the selected account Trash count and hides it in the unified inbox', async () => {
    mockedApi.mockImplementation(async (path: string) =>
      path.startsWith('/api/mail/trash/count') ? { count: path.includes('accountId=2') ? 2 : 4 } : defaultResponse(path));
    element<HTMLButtonElement>('[data-unified]').click(); await settle();
    expect(location.pathname).toBe('/mail');
    expect(element('[data-trash-count]').textContent).toBe('');
    element<HTMLElement>('[data-account="1"]').click(); await settle();
    expect(element('[data-trash-count]').textContent).toBe('4');
    element<HTMLElement>('[data-account="2"]').click(); await settle();
    expect(element('[data-trash-count]').textContent).toBe('2');
    element<HTMLButtonElement>('[data-trash]').click(); await settle();
    expect(element('[data-trash-count]').textContent).toBe('2');
    element<HTMLButtonElement>('[data-unified]').click(); await settle();
    expect(element('[data-trash-count]').textContent).toBe('');
  });
  it('opens the first account trash and normalizes a direct trash URL', async () => {
    history.replaceState(null, '', '/mail/trash');
    window.dispatchEvent(new PopStateEvent('popstate')); await settle();
    expect(location.pathname + location.search).toBe('/mail/trash?account=1');
    expect(element('[data-mailbox-title]').textContent).toBe('Trash');
    expect(mockedApi.mock.calls.some(([path]) => path.startsWith('/api/mail/trash?') && path.includes('accountId=1'))).toBe(true);
  });
  it('restores the previous view on a real history back event',async()=>{
    element('[data-trash]').click();await settle();element('[data-filter="unread"]').click();await settle();
    element('[data-contacts]').click();await settle();
    history.back();await vi.advanceTimersByTimeAsync(50);
    expect(location.pathname+location.search).toBe('/mail/trash?account=1&filter=unread');
    expect(element('[data-mailbox-title]').textContent).toBe('Trash');
    expect(element('[data-filter="unread"]').classList.contains('active')).toBe(true);
  });
  it('keeps mail navigation in the URL and restores a history entry without a reload',async()=>{
    element('[data-trash]').click();await settle();element('[data-filter="unread"]').click();await settle();
    expect(location.pathname+location.search).toBe('/mail/trash?account=1&filter=unread');
    element('[data-contacts]').click();await settle();element('[data-filter="starred"]').click();await settle();
    expect(location.pathname+location.search).toBe('/mail/contacts?filter=starred');
    history.replaceState(null,'','/mail/accounts/2?filter=starred&q=hello&message=101');
    window.dispatchEvent(new PopStateEvent('popstate'));await settle();
    expect(element('[data-mailbox-title]').textContent).toBe('work@example.com');
    expect(element<HTMLInputElement>('[data-search]').value).toBe('hello');
    expect(element('[data-filter="starred"]').classList.contains('active')).toBe(true);
    expect(element('[data-detail-subject]').textContent).toBe('First conversation');
    expect(location.pathname+location.search).toBe('/mail/accounts/2?filter=starred&q=hello&message=101');
    expect(mockedApi.mock.calls.some(([path])=>path.includes('/api/mail/messages?')&&path.includes('accountId=2')&&path.includes('query=hello'))).toBe(true);
  });
  it('opens a shared mailbox without sending read mutations, and removes it when hidden', async () => {
    const shared={id:9,email:'owner@example.test',displayName:'Owner',enabled:true,hidden:false,canView:true,accountIds:[8],accountPermissions:[{accountId:8,canView:true,canSend:false,canOrganize:false,canDelete:false}],canSend:false,canOrganize:false,canDelete:false,accounts:[{id:8,email:'team@example.test',displayName:'Team',authProvider:'PASSWORD',unreadCount:2}]};
    const message={...messages[0],accountId:8,read:false};
    mockedApi.mockImplementation(async(path:string,options?:RequestInit)=>{
      if(path==='/api/mail/sharing')return {outgoing:[],incoming:[structuredClone(shared)],ownAccounts:accounts};
      if(path==='/api/mail/sharing/9/visibility'){shared.hidden=true;shared.accounts=[];return;}
      if(path.startsWith('/api/mail/messages?')&&path.includes('accountId=8'))return {content:[message],page:0,totalPages:1,totalElements:1};
      if(path==='/api/mail/messages/101')return message;
      return defaultResponse(path);
    });
    element('[data-open-mail-sharing]').click();await settle();element('[data-close-sharing]').click();
    element('.shared-owner').click();expect(element('.shared-owner').getAttribute('aria-expanded')).toBe('true');
    element('[data-shared-account="8"]').click();await settle();
    expect(element('[data-mailbox-title]').textContent).toBe('team@example.test');
    expect(document.querySelector('[data-message-id="101"] .message-delete-action')).toBeNull();
    element('[data-message-id="101"] .message-open').click();await settle();
    expect(element('[data-reply]').classList.contains('hidden')).toBe(true);
    expect(mockedApi.mock.calls.some(([path,options])=>path==='/api/mail/messages/101/read'&&options?.method==='PATCH')).toBe(false);
    element('[data-open-mail-sharing]').click();await settle();element('[data-sharing-tab="incoming"]').click();
    element('[aria-label="Hide mailboxes from owner@example.test"]').click();await settle();
    expect(document.querySelector('[data-shared-account="8"]')).toBeNull();expect(element('[data-mailbox-title]').textContent).toBe('Unified inbox');
  });

  it('includes only send-authorized shared accounts in From and sends explicit permission choices', async () => {
    const shared={id:9,email:'owner@example.test',displayName:'Owner',enabled:true,hidden:false,canView:true,accountIds:[8],accountPermissions:[{accountId:8,canView:true,canSend:true,canOrganize:false,canDelete:false}],canSend:true,canOrganize:false,canDelete:false,accounts:[{id:8,email:'team@example.test',displayName:'Team',authProvider:'PASSWORD',unreadCount:0}]};
    mockedApi.mockImplementation(async(path:string,options?:RequestInit)=>{
      if(path==='/api/mail/sharing')return options?.method==='POST'?undefined:{outgoing:[],incoming:[structuredClone(shared)],ownAccounts:accounts};
      return defaultResponse(path);
    });
    element('[data-open-mail-sharing]').click();await settle();element('[data-add-share]').click();
    const shareForm=element<HTMLFormElement>('[data-sharing-form]');
    (shareForm.elements.namedItem('email') as HTMLInputElement).value='viewer@example.test';
    expect((shareForm.elements.namedItem('canView') as HTMLInputElement).checked).toBe(true);
    expect((shareForm.elements.namedItem('canView') as HTMLInputElement).disabled).toBe(false);
    (shareForm.elements.namedItem('canView') as HTMLInputElement).checked=false;
    expect((shareForm.elements.namedItem('canSend') as HTMLInputElement).checked).toBe(false);
    (shareForm.elements.namedItem('canOrganize') as HTMLInputElement).checked=true;
    shareForm.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await settle();
    expect(mockedApi.mock.calls.some(([path,options])=>path==='/api/mail/sharing'&&options?.method==='POST')).toBe(false);
    expect(element<HTMLDialogElement>('[data-sharing-accounts-modal]').open).toBe(true);
    expect(element<HTMLButtonElement>('[data-share-save]').disabled).toBe(true);
    element<HTMLInputElement>('[data-share-account="2"]').click();
    element('[data-share-back]').click();
    expect((shareForm.elements.namedItem('canView') as HTMLInputElement).checked).toBe(false);
    shareForm.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));
    expect(element<HTMLInputElement>('[data-share-account="2"]').checked).toBe(true);
    element<HTMLFormElement>('[data-sharing-accounts-form]').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await settle();
    const request=mockedApi.mock.calls.find(([path,options])=>path==='/api/mail/sharing'&&options?.method==='POST');
    expect(JSON.parse(request![1].body)).toEqual({email:'viewer@example.test',canView:false,canSend:false,canOrganize:true,canDelete:false,accountIds:[2],accountPermissions:[{accountId:2,canView:false,canSend:false,canOrganize:true,canDelete:false}]});
    element('[data-close-sharing]').click();element('[data-compose]').click();await settle();
    expect([...element<HTMLSelectElement>('[data-compose-account]').options].some(option=>option.value==='8')).toBe(true);
    expect(document.querySelector('[data-account-list] [data-account="8"]')).toBeNull();
  });

  it('submits different permissions for each selected mailbox', async () => {
    mockedApi.mockImplementation(async(path:string) => {
      if(path==='/api/mail/sharing')return {outgoing:[],incoming:[],ownAccounts:accounts};
      return defaultResponse(path);
    });
    element('[data-open-mail-sharing]').click();await settle();element('[data-add-share]').click();
    const form=element<HTMLFormElement>('[data-sharing-form]');
    (form.elements.namedItem('email') as HTMLInputElement).value='viewer@example.test';
    form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await settle();
    const first=element<HTMLInputElement>('[data-share-account="1"]');
    const second=element<HTMLInputElement>('[data-share-account="2"]');
    first.click();second.click();
    const options=[...document.querySelectorAll<HTMLElement>('.sharing-account-permissions')];
    options[0].querySelector<HTMLInputElement>('input')!.click();
    options[1].querySelectorAll<HTMLInputElement>('input')[1].click();
    element<HTMLFormElement>('[data-sharing-accounts-form]').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await settle();
    const request=mockedApi.mock.calls.find(([path,options])=>path==='/api/mail/sharing'&&options?.method==='POST');
    const permissions=JSON.parse(request![1].body).accountPermissions;
    expect(permissions).toEqual([
      {accountId:1,canView:false,canSend:false,canOrganize:false,canDelete:false},
      {accountId:2,canView:true,canSend:true,canOrganize:false,canDelete:false}
    ]);
  });


  it('keeps sharing empty states concise and uses the standard close icon',async()=>{
    element('[data-open-mail-sharing]').click();await settle();
    expect(element('[data-sharing-outgoing]').textContent).toBe('No shared mailboxes.');
    expect(element('[data-sharing-modal]').textContent).not.toContain('Use the eye');
    expect(element('[data-sharing-context]').classList.contains('hidden')).toBe(true);
    expect(element('[data-close-sharing]').querySelector('svg')).not.toBeNull();
    expect(element('[data-close-share-editor]').querySelector('svg')).not.toBeNull();
    expect(element('[data-close-share-accounts]').querySelector('svg')).not.toBeNull();
  });

  it('shows contacts in the inbox area and restores a deleted contact with Undo', async () => {
    const contact={id:7,displayName:'Alex Example',email:'alex@example.test'};let removed=false;
    mockedApi.mockImplementation(async(path:string,options?:RequestInit)=>{
      if(path==='/api/mail/contacts')return removed?[]:[contact];
      if(path==='/api/mail/contacts/7'&&options?.method==='DELETE'){removed=true;return;}
      if(path==='/api/mail/contacts/7/restore'){removed=false;return contact;}
      return defaultResponse(path);
    });
    element<HTMLButtonElement>('[data-contacts]').click();await settle();
    expect(element('[data-mailbox-title]').textContent).toBe('Contacts');
    element<HTMLButtonElement>('[aria-label="Open contact Alex Example"]').click();
    expect(element('[data-extra-detail]').textContent).toContain('alex@example.test');
    element<HTMLButtonElement>('[aria-label="Delete contact Alex Example"]').click();
    expect(element('[data-contact-id="7"]').classList.contains('removing')).toBe(true);
    await vi.advanceTimersByTimeAsync(210);expect(document.querySelector('[data-contact-id="7"]')).toBeNull();
    element<HTMLButtonElement>('[data-undo-trash]').click();await settle();
    expect(element('[data-contact-id="7"]').classList.contains('restoring')).toBe(true);
    element<HTMLButtonElement>('[data-unified]').click();await settle();
    expect(element('[data-extra-detail]').classList.contains('hidden')).toBe(true);
  });

  it('selects a saved recipient without replacing earlier recipients and closes its menu', async () => {
    mockedApi.mockImplementation(async(path:string)=>path==='/api/mail/contacts'?[{id:7,displayName:'Alex Example',email:'alex@example.test'}]:defaultResponse(path));
    element<HTMLButtonElement>('[data-compose]').click();await settle();
    field('to').value='first@example.test, Alex';
    element<HTMLButtonElement>('[data-pick-contact]').click();await settle();
    element<HTMLButtonElement>('.contact-picker-item').click();await settle();
    expect(field('to').value).toBe('first@example.test, alex@example.test, ');
    expect(element('[data-contact-picker]').classList.contains('hidden')).toBe(true);
  });

  it('creates a contact from the typed recipient and clears contact searches after saving', async () => {
    let saved:unknown;
    mockedApi.mockImplementation(async(path:string,options?:RequestInit)=>{
      if(path==='/api/mail/contacts'&&options?.method==='POST'){saved=JSON.parse(String(options.body));return {id:8,...saved as object};}
      return defaultResponse(path);
    });
    element<HTMLButtonElement>('[data-compose]').click();await settle();change('to','new@example.test');
    element<HTMLButtonElement>('.contact-picker-add').click();
    const contactForm=element<HTMLFormElement>('[data-contact-form]');
    expect((contactForm.elements.namedItem('email') as HTMLInputElement).value).toBe('new@example.test');
    (contactForm.elements.namedItem('displayName') as HTMLInputElement).value='New contact';
    contactForm.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await settle();
    expect(saved).toEqual({displayName:'New contact',email:'new@example.test'});
    expect(element<HTMLDialogElement>('[data-contact-editor]').open).toBe(false);
  });

  it('updates sent status badges from the server while preserving the sent reading pane', async () => {
    let status='QUEUED';
    const sent=()=>({id:'sent-1',accountName:'Work',from:'work@example.test',to:'alex@example.test',subject:'Outgoing check',bodyText:'Hello',status,failureReason:null,createdAt:'2026-09-22T10:00:00Z',sentAt:null});
    mockedApi.mockImplementation(async(path:string)=>path.startsWith('/api/mail/outbound/sent?')?{content:[sent()],page:0,totalPages:1,totalElements:1}:defaultResponse(path));
    element<HTMLButtonElement>('[data-sent]').click();await settle();
    expect(element('.delivery-badge').textContent).toBe('Queued');
    element<HTMLButtonElement>('[aria-label="Open sent message Outgoing check"]').click();
    expect(element('[data-extra-detail]').textContent).toContain('Hello');
    status='SENT';await vi.advanceTimersByTimeAsync(5000);
    expect(element('.delivery-badge').textContent).toBe('Sent');
    expect(element('[data-extra-detail]').textContent).toContain('SMTP server accepted');
  });

  it('shows a sending animation and follows the actual delivery result in its notification', async () => {
    const queued=deferred<unknown>();
    mockedApi.mockImplementation(async(path:string)=>{
      if(path==='/api/mail/outbound/send')return queued.promise;
      if(path==='/api/mail/outbound/sent/sent-1')return {id:'sent-1',status:'FAILED',failureReason:'SMTP sign-in was rejected.'};
      return defaultResponse(path);
    });
    element<HTMLButtonElement>('[data-compose]').click();await settle();change('to','alex@example.test');change('body','Hello');
    form().dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await settle();
    expect(element('.compose-send').classList.contains('sending')).toBe(true);
    queued.resolve({id:'sent-1',status:'QUEUED'});await settle();
    expect(element('[data-send-feedback-title]').textContent).toBe('Message queued');
    await vi.advanceTimersByTimeAsync(2000);
    expect(element('[data-send-feedback-title]').textContent).toBe('Message could not be sent');
    expect(element('[data-send-feedback-copy]').textContent).toContain('SMTP sign-in');
  });


  it('dismisses the trash toast on navigation', async () => {
    element<HTMLButtonElement>('[data-message-id="101"] .message-delete-action').click();
    await vi.advanceTimersByTimeAsync(210);
    const toast = element('[data-message-toast]');
    expect(toast.classList.contains('visible')).toBe(true);
    element<HTMLButtonElement>('[data-trash]').click(); await settle();
    expect(toast.classList.contains('visible')).toBe(false);
    expect(toast.classList.contains('hidden')).toBe(false);
    await vi.advanceTimersByTimeAsync(300);
    expect(toast.classList.contains('hidden')).toBe(true);
  });

  it('keeps Trash left aligned with the selected account above it', async () => {
    element<HTMLButtonElement>('[data-account="2"] .account-main').click(); await settle();
    element<HTMLButtonElement>('[data-trash]').click(); await settle();
    expect(element('[data-mailbox-title]').textContent).toBe('Trash');
    expect(element('[data-mailbox-account]').textContent).toBe('work@example.com');
    expect(element('[data-mailbox-account]').classList.contains('hidden')).toBe(false);
    element<HTMLButtonElement>('[data-contacts]').click(); await settle();
    expect(element('[data-mailbox-account]').classList.contains('hidden')).toBe(true);
  });

  it('synchronizes Gmail when refreshing Trash', async () => {
    element<HTMLButtonElement>('[data-account="2"] .account-main').click(); await settle();
    element<HTMLButtonElement>('[data-trash]').click(); await settle();
    element<HTMLButtonElement>('[data-refresh]').click(); await settle();
    expect(mockedApi).toHaveBeenCalledWith('/api/mail/accounts/2/sync', { method: 'POST' });
  });

  it('reconciles a stale Gmail delete response without leaving an error alert', async () => {
    let synchronized = false;
    mockedApi.mockImplementation(async (path: string, options?: RequestInit) => {
      if (path === '/api/mail/messages/101' && options?.method === 'DELETE')
        throw new ApiError(409, 'The Gmail message changed.');
      if (path === '/api/mail/accounts/2/sync') { synchronized = true; return; }
      if (path.startsWith('/api/mail/messages?')) {
        const content = synchronized ? messages.filter(message => message.id !== 101) : messages;
        return { content: structuredClone(content), page: 0, totalPages: 1, totalElements: content.length };
      }
      return defaultResponse(path);
    });
    element<HTMLButtonElement>('[data-message-id="101"] .message-delete-action').click();
    await vi.advanceTimersByTimeAsync(220);
    expect(synchronized).toBe(true);
    expect(document.querySelector('[data-message-id="101"]')).toBeNull();
    expect(element('[data-global-alert]').classList.contains('hidden')).toBe(true);
    expect(element('[data-message-toast-label]').textContent).toBe('Mailbox updated from Gmail');
  });

  it('shows account provider icons in the account manager', () => {
    element<HTMLButtonElement>('[data-open-mail-accounts]').click();
    const rows = [...document.querySelectorAll<HTMLElement>('[data-managed-accounts] .managed-account')];
    expect(rows).toHaveLength(2);
    expect(rows[0].querySelector('.managed-account-provider-icon')).not.toBeNull();
    expect(rows[1].querySelector('.account-gmail-icon')).not.toBeNull();
  });

  it('animates a Gmail deletion immediately while its remote move is pending', async () => {
    const move = deferred<void>();
    let removed = false;
    mockedApi.mockImplementation(async (path: string, options?: RequestInit) => {
      if (path === '/api/mail/messages/101' && options?.method === 'DELETE') {
        await move.promise; removed = true; return;
      }
      if (path.startsWith('/api/mail/messages?')) {
        const content = removed ? messages.filter(message => message.id !== 101) : messages;
        return { content: structuredClone(content), page: 0, totalPages: 1, totalElements: content.length };
      }
      return defaultResponse(path);
    });
    element<HTMLButtonElement>('[data-message-id="101"] .message-delete-action').click();
    expect(element('[data-message-id="101"]').classList.contains('removing')).toBe(true);
    expect(element('[data-message-toast-label]').textContent).toBe('Moving to Trash…');
    await vi.advanceTimersByTimeAsync(5000);
    expect(element('[data-message-toast-label]').textContent).toBe('Moving to Trash…');
    move.resolve(); await settle();
    expect(element('[data-message-toast-label]').textContent).toBe('Moved to Trash');
    expect(element('[data-undo-trash]').classList.contains('hidden')).toBe(false);
  });

  it('does not show a late delete toast after switching mail views', async () => {
    element<HTMLButtonElement>('[data-message-id="101"] .message-delete-action').click();
    element<HTMLButtonElement>('[data-trash]').click();
    await vi.advanceTimersByTimeAsync(400);
    expect(element('[data-message-toast]').classList.contains('hidden')).toBe(true);
    expect(element('[data-message-toast]').classList.contains('visible')).toBe(false);
  });

  it('renders attachment text literally and displays preview errors in the modal', async () => {
    const attachment = { id: 8, filename: 'example.html', scanStatus: 'CLEAN', sizeBytes: 50, contentType: 'text/html' };
    mockedApi.mockImplementation(async (path: string) => {
      if (path === '/api/mail/messages/101') return { ...messages[0], attachments: [attachment] };
      if (path === '/api/mail/attachments/8/preview') return { kind: 'text', content: '<script>unsafe()</script>' };
      return defaultResponse(path);
    });
    element<HTMLButtonElement>('[data-message-id="101"] .message-open').click(); await settle();
    element<HTMLButtonElement>('[aria-label="Preview example.html"]').click(); await settle();
    expect(element('[data-attachment-preview-content]').textContent).toBe('<script>unsafe()</script>');
    expect(element('[data-attachment-preview-content]').querySelector('script')).toBeNull();
    element<HTMLDialogElement>('[data-attachment-preview-modal]').close();
    mockedApi.mockImplementation(async () => { throw new Error('This file is too large to preview.'); });
    element<HTMLButtonElement>('[aria-label="Preview example.html"]').click(); await settle();
    expect(element('[data-attachment-preview-content]').textContent).toContain('too large');
  });

  it('enables preview and download only after a retry returns a clean scan', async () => {
    mockedApi.mockImplementation(async (path: string) => {
      if (path === '/api/mail/messages/101') return { ...messages[0], attachments: [{ id: 8, filename: 'example.txt', scanStatus: 'UNAVAILABLE', sizeBytes: 50 }] };
      if (path === '/api/mail/attachments/8/scan') return { scanStatus: 'CLEAN', scanDetail: 'No threat detected' };
      return defaultResponse(path);
    });
    element<HTMLButtonElement>('[data-message-id="101"] .message-open').click(); await settle();
    expect(document.querySelector('[aria-label="Download example.txt"]')).toBeNull();
    element<HTMLButtonElement>('[aria-label="Retry scan example.txt"]').click(); await settle();
    expect(document.querySelector('[aria-label="Download example.txt"]')).not.toBeNull();
    expect(document.querySelector('[aria-label="Preview example.txt"]')).not.toBeNull();
  });

  let cleanupListeners = () => {};
  const dialogMethods = new Map<string, PropertyDescriptor | undefined>();

  beforeEach(async () => {
    history.replaceState(null,'','/mail');
    vi.resetModules(); vi.useFakeTimers(); mockedApi.mockReset().mockImplementation(defaultResponse);
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
      showModal: { configurable: true, value: vi.fn(function(this: HTMLDialogElement) { this.open = true; }) },
      close: { configurable: true, value: function(this: HTMLDialogElement, returnValue?: string) { if (returnValue !== undefined) this.returnValue = returnValue; this.open = false; this.dispatchEvent(new Event('close')); } }
    });
    const { initMail } = await import('./mail');
    await initMail();
  });

  afterEach(() => {
    cleanupListeners();
    for (const [name, descriptor] of dialogMethods) {
      if (descriptor) Object.defineProperty(HTMLDialogElement.prototype, name, descriptor);
      else Reflect.deleteProperty(HTMLDialogElement.prototype, name);
    }
    vi.clearAllTimers(); vi.useRealTimers(); vi.restoreAllMocks();
  });

  it('opens a nonmodal reply-all window with its source account and preserves context while browsing other messages', async () => {
    await openReplyAll();
    expect(modal().open).toBe(true);
    expect(HTMLDialogElement.prototype.showModal).not.toHaveBeenCalled();
    expect(field('accountId').value).toBe('2');
    expect(field('to').value).toBe('alex@example.com');
    expect(field('cc').value).toBe('sam@example.com');
    expect(element('[data-recipient-row="cc"]').classList.contains('hidden')).toBe(false);

    change('body', 'My response to the first conversation');
    element<HTMLButtonElement>('[data-message-id="102"] .message-open').click();
    await settle();
    await vi.advanceTimersByTimeAsync(1200);

    expect(savedPayload()).toMatchObject({ accountId: 2, inReplyTo: '<first@example.com>', referencesHeader: '<earlier@example.com> <first@example.com>', bodyText: 'My response to the first conversation' });
    expect(mockedApi.mock.calls.some(([path]) => path === '/api/mail/outbound/send')).toBe(false);
  });

  it('preserves a working message through minimize, expand and repeated compose clicks', async () => {
    await openReplyAll(); change('body', 'Unsent work');
    element<HTMLButtonElement>('[data-minimize-compose]').click();
    expect(modal().classList.contains('is-minimized')).toBe(true);
    element<HTMLButtonElement>('[data-compose]').click();
    await settle();
    expect(modal().classList.contains('is-minimized')).toBe(false);
    element<HTMLButtonElement>('[data-expand-compose]').click();
    expect(modal().classList.contains('is-expanded')).toBe(true);
    expect(field('body').value).toBe('Unsent work');
    expect(field('subject').value).toBe('Re: First conversation');
  });

  it('preserves the chosen sending account when the inbox refreshes behind the composer', async () => {
    await openReplyAll();
    element<HTMLButtonElement>('[data-refresh]').click();
    await settle();
    expect(field('accountId').value).toBe('2');
  });

  it('keeps unsent content visible when saving on close fails', async () => {
    await openReplyAll(); change('body', 'Keep this message');
    mockedApi.mockImplementation((path: string) => path.startsWith('/api/mail/outbound/drafts') ? Promise.reject(new Error('Offline')) : defaultResponse(path));
    element<HTMLButtonElement>('[data-close-compose]').click();
    await settle();
    expect(modal().open).toBe(true);
    expect(field('body').value).toBe('Keep this message');
    expect(field('body').disabled).toBe(false);
    expect(form().hasAttribute('aria-busy')).toBe(false);
    expect(element('[data-draft-status]').textContent).toContain('could not be saved');
  });

  it('does not lose edits made while a close-triggered save is pending', async () => {
    await openReplyAll(); change('body', 'Before close');
    const save = deferred<{ id: string; status: string }>();
    mockedApi.mockImplementation((path: string) => path.startsWith('/api/mail/outbound/drafts') ? save.promise : defaultResponse(path));
    element<HTMLButtonElement>('[data-close-compose]').click();
    await settle();
    const body = field('body') as HTMLTextAreaElement;
    if (!body.disabled && !body.readOnly) change('body', 'An edit while saving');
    save.resolve({ id: 'draft-1', status: 'DRAFT' });
    await settle();
    if (!modal().open) expect(savedPayload().bodyText).toBe(body.value);
    else expect(body.value).toBe('An edit while saving');
  });

  it('serializes overlapping autosaves and persists each captured edit in order', async () => {
    await openReplyAll();
    const saves: ReturnType<typeof deferred<{ id: string; status: string }>>[] = [];
    let active = 0, maxActive = 0;
    mockedApi.mockImplementation((path: string) => {
      if (!path.startsWith('/api/mail/outbound/drafts') || path.endsWith('/count')) return defaultResponse(path);
      const save = deferred<{ id: string; status: string }>(); saves.push(save);
      active++; maxActive = Math.max(maxActive, active);
      return save.promise.finally(() => { active--; });
    });
    for (const body of ['First edit', 'Second edit', 'Third edit']) {
      change('body', body);
      await vi.advanceTimersByTimeAsync(1200);
    }
    expect(draftCalls()).toHaveLength(1);
    saves[0].resolve({ id: 'draft-1', status: 'DRAFT' });
    await settle();
    expect(draftCalls()).toHaveLength(2);
    expect(savedPayload().bodyText).toBe('Second edit');
    saves[1].resolve({ id: 'draft-1', status: 'DRAFT' });
    await settle();
    expect(draftCalls()).toHaveLength(3);
    expect(savedPayload().bodyText).toBe('Third edit');
    saves[2].resolve({ id: 'draft-1', status: 'DRAFT' });
    await settle();
    expect(maxActive).toBe(1);
    expect(draftCalls().map(([, options]) => options.method)).toEqual(['POST', 'PUT', 'PUT']);
  });

  it('waits for an earlier autosave and sends the complete snapshot while controls are locked', async () => {
    await openReplyAll();
    const earlierSave = deferred<{ id: string; status: string }>();
    const sending = deferred<{ id: string; status: string }>();
    let draftRequests = 0;
    mockedApi.mockImplementation((path: string) => {
      if (path.startsWith('/api/mail/outbound/drafts') && draftRequests++ === 0) return earlierSave.promise;
      if (path === '/api/mail/outbound/send') return sending.promise;
      return defaultResponse(path);
    });
    change('body', 'Earlier autosave');
    await vi.advanceTimersByTimeAsync(1200);
    change('body', 'The message to send');
    const submitter = element<HTMLButtonElement>('[data-compose-form] button[type="submit"]');
    form().dispatchEvent(new SubmitEvent('submit', { bubbles: true, cancelable: true, submitter }));
    await settle();
    expect(form().getAttribute('aria-busy')).toBe('true');
    expect(field('body').disabled).toBe(true);
    expect(element<HTMLButtonElement>('[data-close-compose]').disabled).toBe(true);
    expect(mockedApi.mock.calls.some(([path]) => path === '/api/mail/outbound/send')).toBe(false);

    earlierSave.resolve({ id: 'draft-1', status: 'DRAFT' });
    await settle();
    const sendCall = mockedApi.mock.calls.find(([path]) => path === '/api/mail/outbound/send');
    expect(JSON.parse(sendCall?.[1].body ?? '{}')).toMatchObject({ accountId: 2, to: ['alex@example.com'], cc: ['sam@example.com'], bodyText: 'The message to send', inReplyTo: '<first@example.com>' });
    expect(savedPayload().bodyText).toBe('The message to send');
    expect(modal().open).toBe(true);
    sending.resolve({ id: 'draft-1', status: 'QUEUED' });
    await settle();
    expect(modal().open).toBe(false);
    expect(field('body').disabled).toBe(false);
    expect(form().hasAttribute('aria-busy')).toBe(false);
  });

  it('lists saved drafts with a count and resumes the same draft without losing recipients or reply context', async () => {
    const draft = { id: 'saved-draft', accountId: 2, accountName: 'Work', accountEmail: 'work@example.com', accountActive: true,
      to: 'to@example.com', cc: 'cc@example.com', bcc: 'bcc@example.com', subject: 'Saved subject', bodyText: 'Saved body', bodyHtml: '',
      inReplyTo: '<reply>', referencesHeader: '<thread>', savedAt: '2026-09-22T08:00:00Z', attachments: [], sourceMessageId: 101 };
    mockedApi.mockImplementation(async (path: string, options?: { method?: string; body?: string }) => {
      if (path === '/api/mail/outbound/drafts/count') return { count: 1 };
      if (path.startsWith('/api/mail/outbound/drafts?')) return { content: [{ ...draft, recipients: draft.to, preview: draft.bodyText }], page: 0, totalPages: 1, totalElements: 1 };
      if (path === '/api/mail/outbound/drafts/saved-draft') return options?.method ? { id: 'saved-draft', status: 'DRAFT' } : draft;
      return defaultResponse(path);
    });
    element<HTMLButtonElement>('[data-drafts]').click(); await settle();
    expect(element('[data-mailbox-title]').textContent).toBe('Drafts');
    expect(element('[data-draft-count]').textContent).toBe('1');
    element<HTMLButtonElement>('[data-draft-id="saved-draft"] button').click(); await settle();
    expect(field('accountId').value).toBe('2');
    expect(field('to').value).toBe(draft.to); expect(field('cc').value).toBe(draft.cc); expect(field('bcc').value).toBe(draft.bcc);
    expect(field('subject').value).toBe('Saved subject'); expect(field('body').value).toBe('Saved body');
    expect(element('[data-detail-subject]').textContent).toBe('First conversation');
    expect(element('[data-draft-status]').textContent).toBe(`Draft saved at ${new Date(draft.savedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`);
    expect(document.querySelector('[data-draft-id="saved-draft"]')).not.toBeNull();
    change('body', 'Continued body'); await vi.advanceTimersByTimeAsync(1200);
    expect(draftCalls()).toHaveLength(1);
    expect(draftCalls()[0][0]).toBe('/api/mail/outbound/drafts/saved-draft');
    expect(savedPayload()).toMatchObject({ bodyText: 'Continued body', inReplyTo: '<reply>', referencesHeader: '<thread>', sourceMessageId: 101 });
    expect(element('[data-draft-count]').textContent).toBe('1');
  });

  it('keeps zero draft counts blank and removes a successfully queued draft from the list', async () => {
    let count = 1;
    const draft = { id: 'saved-draft', accountId: 1, accountName: 'Personal', accountEmail: 'personal@example.com', accountActive: true,
      to: 'to@example.com', subject: 'Ready', bodyText: 'Ready to send', savedAt: '2026-09-22T08:00:00Z', attachments: [] };
    mockedApi.mockImplementation(async (path: string, options?: { method?: string }) => {
      if (path === '/api/mail/outbound/drafts/count') return { count };
      if (path.startsWith('/api/mail/outbound/drafts?')) return { content: count ? [{ ...draft, recipients: draft.to, preview: draft.bodyText }] : [], page: 0, totalPages: count, totalElements: count };
      if (path === '/api/mail/outbound/drafts/saved-draft') return options?.method ? { id: 'saved-draft', status: 'DRAFT' } : draft;
      if (path === '/api/mail/outbound/send') { count = 0; return { id: 'saved-draft', status: 'QUEUED' }; }
      return defaultResponse(path);
    });
    element<HTMLButtonElement>('[data-drafts]').click(); await settle();
    element<HTMLButtonElement>('[data-draft-id="saved-draft"] button').click(); await settle();
    form().dispatchEvent(new SubmitEvent('submit', { bubbles: true, cancelable: true, submitter: element('[data-compose-form] button[type=submit]') }));
    await settle();
    expect(modal().open).toBe(false);
    expect(element('[data-draft-count]').textContent).toBe('');
    expect(document.querySelector('[data-draft-id]')).toBeNull();
    expect(element('[data-message-empty-title]').textContent).toBe('No saved drafts');
  });

  it('shows personal sign-in activity with sign-out-everywhere only in the profile menu', async () => {
    mockedApi.mockImplementation(async (path: string) => {
      if (path.startsWith('/api/security/activity?')) return { sessions: [{ current: true, ip: '127.0.0.1', userAgent: 'Test browser', startedAt: '2026-09-22T08:00:00Z', lastSeenAt: '2026-09-22T08:30:00Z' }], events: { content: [{ type: 'LOGIN_2FA', outcome: 'SUCCESS', ip: '127.0.0.1', time: '2026-09-22T08:00:00Z' }], page: 0, totalPages: 1 } };
      return defaultResponse(path);
    });
    element<HTMLButtonElement>('[data-open-security-activity]').click(); await settle();
    expect(element<HTMLDialogElement>('[data-security-activity-modal]').open).toBe(true);
    expect(element('[data-security-sessions]').textContent).toContain('This device');
    expect(element('[data-security-events]').textContent).toContain('Two-factor sign-in');
    expect(element('[data-security-events]').textContent).toContain('127.0.0.1');
    expect(document.querySelectorAll('[data-logout-all]')).toHaveLength(1);
    expect(document.querySelector('[data-security-activity-modal] [data-logout-all]')).toBeNull();
    expect(element('[data-security-panel="history"]').hidden).toBe(true);
    expect(element('.current-device .current-device-badge').textContent).toBe('This device');
    expect(element('.current-device strong').title).toBe('Test browser');
    expect(element('[data-security-sessions]').textContent).not.toContain('Test browser');
    element<HTMLButtonElement>('[data-security-tab="history"]').click();
    expect(element('[data-security-panel="history"]').hidden).toBe(false);
    expect(element('[data-security-panel="sessions"]').hidden).toBe(true);
    element('[data-security-tab="history"]').dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }));
    expect(element('[data-security-tab="sessions"]').getAttribute('aria-selected')).toBe('true');
  });

  it('moves drafts to the combined Trash, filters drafts, restores them and hides a zero total', async () => {
    let trashed = false, mailTrashed = true;
    const draft = { id: 'saved-draft', recipients: 'to@example.com', subject: 'Draft in Trash', preview: 'Body', savedAt: '2026-09-22T08:00:00Z' };
    mockedApi.mockImplementation(async (path: string, options?: { method?: string; body?: string }) => {
        if (path.startsWith('/api/mail/trash/count')) return { count: Number(trashed) + Number(mailTrashed) };
      if (path === '/api/mail/outbound/drafts/count') return { count: Number(!trashed) };
      if (path.startsWith('/api/mail/outbound/drafts?')) return { content: trashed ? [] : [draft], page: 0, totalPages: trashed ? 0 : 1, totalElements: Number(!trashed) };
      if (path === '/api/mail/outbound/drafts/saved-draft/trashed') { trashed = JSON.parse(options!.body!).value; return; }
      if (path.startsWith('/api/mail/trash?')) {
        const filter = new URLSearchParams(path.split('?')[1]).get('filter');
        const content = [...(mailTrashed && filter !== 'drafts' ? [{ message: messages[0], draft: null }] : []), ...(trashed && ['all', 'drafts'].includes(filter!) ? [{ message: null, draft }] : [])];
        return { content, page: 0, totalPages: content.length ? 1 : 0, totalElements: content.length };
      }
      if (path === '/api/mail/messages/101/trashed') { mailTrashed = false; return; }
      return defaultResponse(path);
    });
    element<HTMLButtonElement>('[data-drafts]').click(); await settle();
    element<HTMLButtonElement>('[aria-label="Move draft to Trash"]').click(); await settle();
    expect(element('[data-draft-id="saved-draft"]').classList.contains('removing')).toBe(true);
    await vi.advanceTimersByTimeAsync(190);
    expect(element('[data-draft-count]').textContent).toBe('');
    expect(element('[data-trash-count]').textContent).toBe('');
    expect(element('[data-message-toast-label]').textContent).toBe('Moved to Trash');
    expect(element('[data-undo-trash]').classList.contains('hidden')).toBe(false);
    element<HTMLButtonElement>('[data-undo-trash]').click(); await settle();
    expect(element('[data-draft-id="saved-draft"]').classList.contains('restoring')).toBe(true);
    expect(element('[data-draft-count]').textContent).toBe('1');
    element<HTMLButtonElement>('[aria-label="Move draft to Trash"]').click(); await vi.advanceTimersByTimeAsync(190);
    element<HTMLButtonElement>('[data-trash]').click(); await settle();
    expect(element('[data-trash-count]').textContent).toBe('2');
    expect(document.querySelector('[data-message-id="101"]')).not.toBeNull();
    expect(document.querySelector('[data-draft-id="saved-draft"]')).not.toBeNull();
    element<HTMLButtonElement>('[data-filter="drafts"]').click(); await settle();
    expect(document.querySelector('[data-message-id="101"]')).toBeNull();
    expect(element('[data-trash-count]').textContent).toBe('2');
    element<HTMLButtonElement>('[aria-label="Restore draft"]').click(); await settle();
    expect(element('[data-draft-id="saved-draft"]').classList.contains('trash-restoring-out')).toBe(true);
    await vi.advanceTimersByTimeAsync(400);
    expect(element('[data-draft-count]').textContent).toBe('1');
    expect(element('[data-trash-count]').textContent).toBe('1');
    expect(element<HTMLButtonElement>('[data-empty-trash]').disabled).toBe(false);
    element<HTMLButtonElement>('[data-filter="all"]').click(); await settle();
element<HTMLButtonElement>('[aria-label="Restore from Trash"]').click(); await vi.advanceTimersByTimeAsync(400);
    expect(element('[data-trash-count]').textContent).toBe('');
    expect(element<HTMLButtonElement>('[data-empty-trash]').disabled).toBe(true);
    element<HTMLButtonElement>('[data-filter="drafts"]').click(); await settle();
    element<HTMLButtonElement>('[data-unified]').click(); await settle();
    expect(element('[data-filter="drafts"]').classList.contains('hidden')).toBe(true);
    expect(element('[data-filter="all"]').classList.contains('active')).toBe(true);
  });

  it('uses the themed From picker, preserves reply identity and saves a changed sender', async () => {
    await openReplyAll();
    const trigger = element<HTMLButtonElement>('[data-compose-account-trigger]');
    expect(trigger.textContent).toContain('work@example.com');
    expect(element<HTMLSelectElement>('[data-compose-account]').hidden).toBe(true);
    trigger.click();
    expect(trigger.getAttribute('aria-expanded')).toBe('true');
    trigger.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true, cancelable: true }));
    trigger.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
    expect(field('accountId').value).toBe('1');
    expect(trigger.textContent).toContain('personal@example.com');
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    await vi.advanceTimersByTimeAsync(1200);
    expect(savedPayload().accountId).toBe(1);
    trigger.click();
    field('body').dispatchEvent(new MouseEvent('click', { bubbles: true }));
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    trigger.click();
    trigger.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    expect(field('accountId').value).toBe('1');
  });

  it('keeps quick responses only in the sidebar and offers mailbox sharing in the profile menu', async () => {
    const buttons = [...document.querySelectorAll<HTMLButtonElement>('[data-open-quick-responses]')];
    expect(buttons).toHaveLength(1);
    expect(document.querySelector('[data-account-popover] [data-open-quick-responses]')).toBeNull();
    expect(document.querySelector('[data-account-popover] [data-open-mail-sharing]')).not.toBeNull();
    for (const button of buttons) {
      button.click(); await settle();
      expect(element<HTMLDialogElement>('[data-quick-responses-modal]').open).toBe(true);
      element<HTMLButtonElement>('[data-close-quick-responses]').click();
    }
  });

  it('opens Connect mail account in its own dialog and returns to the account list', async () => {
    element<HTMLButtonElement>('[data-open-mail-accounts]').click(); await settle();
    const list = element<HTMLDialogElement>('[data-mail-accounts-modal]');
    const create = element<HTMLDialogElement>('[data-account-create-modal]');
    element<HTMLButtonElement>('[data-open-account-create]').click();
    expect(list.open).toBe(false); expect(create.open).toBe(true);
    expect(create.contains(element('[data-account-form]'))).toBe(true);
    element<HTMLButtonElement>('[data-close-account-create]').click();
    expect(create.open).toBe(false); expect(list.open).toBe(true);
  });

  it('slides a newly connected account in and collapses its row after removal', async () => {
    let available = structuredClone(accounts);
    mockedApi.mockImplementation(async (path: string, options?: RequestInit) => {
      if (path === '/api/mail/accounts' && options?.method === 'POST') {
        available.push({ id: 3, displayName: 'New', email: 'new@example.com', authProvider: 'PASSWORD', active: true });
        return;
      }
      if (path === '/api/mail/accounts/3' && options?.method === 'DELETE') { available = available.filter(account => account.id !== 3); return; }
      if (path === '/api/mail/accounts') return structuredClone(available);
      return defaultResponse(path);
    });
    element('[data-open-mail-accounts]').click(); element('[data-open-account-create]').click();
    element<HTMLFormElement>('[data-account-form]').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await settle();
    expect(element('[data-account="3"]').classList.contains('account-entering')).toBe(true);
    const row = [...document.querySelectorAll<HTMLElement>('.managed-account')].find(item => item.textContent?.includes('new@example.com'))!;
    row.querySelector<HTMLButtonElement>('button')!.click();
    element<HTMLDialogElement>('[data-confirm-modal]').close('confirm'); await settle();
    expect(row.classList.contains('account-leaving')).toBe(true);
    expect(element('[data-account="3"]').classList.contains('account-leaving')).toBe(true);
    await vi.advanceTimersByTimeAsync(260);
    expect(document.querySelector('[data-account="3"]')).toBeNull();
  });


  it('dismisses the profile menu on outside clicks even when propagation stops and on mail iframe clicks', async () => {
    const trigger = element<HTMLButtonElement>('[data-account-menu]');
    const popover = element('[data-account-popover]');
    trigger.click();
    popover.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    expect(trigger.getAttribute('aria-expanded')).toBe('true');
    const outside = element('[data-mailbox-title]');
    outside.addEventListener('click', event => event.stopPropagation());
    outside.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    trigger.click();
    window.dispatchEvent(new MessageEvent('message', { data: { type: 'dispatch-frame-click' }, source: window }));
    expect(trigger.getAttribute('aria-expanded')).toBe('true');
    const frame = element<HTMLIFrameElement>('[data-message-frame]');
    window.dispatchEvent(new MessageEvent('message', { data: { type: 'dispatch-frame-click' }, source: frame.contentWindow }));
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    expect(popover.classList.contains('hidden')).toBe(true);
  });

  it('saves account order and preserves it when selecting a mailbox and refreshing', async () => {
    let savedAccounts = structuredClone(accounts);
    mockedApi.mockImplementation(async (path: string, options?: { body?: string }) => {
      if (path === '/api/mail/accounts/order') {
        const ids: number[] = JSON.parse(options!.body!).accountIds;
        savedAccounts = ids.map(id => accounts.find(account => account.id === id)!);
        return;
      }
      if (path === '/api/mail/accounts') return structuredClone(savedAccounts);
      return defaultResponse(path);
    });
    const order = () => [...document.querySelectorAll<HTMLElement>('[data-account-list] [data-account]')].map(row => row.dataset.account);
    element('[data-account="2"]').dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, altKey: true, key: 'ArrowUp' }));
    await settle();
    expect(order()).toEqual(['2', '1']);
    expect(mockedApi).toHaveBeenCalledWith('/api/mail/accounts/order', { method: 'PUT', body: JSON.stringify({ accountIds: [2, 1] }) });
    element<HTMLButtonElement>('[data-account="2"]').click();
    await settle();
    expect(element('[data-mailbox-title]').textContent).toBe('work@example.com');
    expect(order()).toEqual(['2', '1']);
    element<HTMLButtonElement>('[data-refresh]').click();
    await settle();
    expect(order()).toEqual(['2', '1']);
  });

  it('warns before closing a message with local attachments and preserves them when cancelled', async () => {
    await openReplyAll();
    const attachment = new File(['content'], 'notes.txt', { type: 'text/plain' });
    Object.defineProperty(element('[data-compose-files]'), 'files', { configurable: true, value: [attachment] });
    element<HTMLButtonElement>('[data-close-compose]').click();
    await settle();
    const confirmation = element<HTMLDialogElement>('[data-confirm-modal]');
    expect(confirmation.open).toBe(true);
    expect(element('[data-confirm-message]').textContent).toContain('Attached files are kept only in this window');
    confirmation.close('cancel');
    await settle();
    expect(modal().open).toBe(true);
    expect(element<HTMLInputElement>('[data-compose-files]').files?.[0]).toBe(attachment);
    expect(field('body').disabled).toBe(false);
    expect(draftCalls()).toHaveLength(0);
  });
});
