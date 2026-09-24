import { api, ApiError } from './api';
import { initMailSharing } from './mail-sharing';
import { composeMessage, type ComposeMode } from './compose';
import { initAccountOrder } from './account-order';
import { initComposeAccountPicker } from './compose-account-picker';
import { initSecurityActivity } from './security-activity';
import { initQuickResponses } from './quick-responses';
import { initMailExtras, type ExtraView } from './mail-extras';
import { readMailLocation, mailLocationUrl } from './mail-navigation';

type Account = { id: number; displayName: string; email: string; authProvider: 'GOOGLE' | 'PASSWORD'; syncStatus: string | null; syncError: string | null; lastSyncAt: string | null; active: boolean };
type Folder = { id: number; accountId: number; name: string; unreadCount: number };
type Summary = { id: number; accountId: number; accountName: string; subject: string; fromAddress: string; preview: string; receivedAt: string; read: boolean; starred: boolean; pinned: boolean; hasAttachments: boolean };
type Attachment = { id: number; filename: string; contentType: string; sizeBytes: number; scanStatus: string; scanDetail: string };
type Detail = { id: number; accountId: number; accountName: string; subject: string; fromAddress: string; recipients: string; textBody: string; internetMessageId: string | null; referencesHeader: string | null; receivedAt: string; read: boolean; starred: boolean; pinned: boolean; attachments: Attachment[] };
type Page<T> = { content: T[]; page: number; size: number; totalElements: number; totalPages: number };
type Outbound = { id: string; status: string; failureReason?: string };
type Canned = { id: number; title: string; bodyHtml: string };
type Profile = { id: number; displayName: string; email: string };
type DraftSummary = { id: string; recipients: string; subject: string; preview: string; savedAt: string };
type DraftDetail = { id: string; accountId: number; accountName: string; accountEmail: string; accountActive: boolean;
  to: string; cc: string | null; bcc: string | null; subject: string; bodyText: string; bodyHtml: string | null;
  inReplyTo: string | null; referencesHeader: string | null; savedAt: string; attachments: Attachment[]; sourceMessageId: number | null };
type TrashItem = { message: Summary | null; draft: DraftSummary | null };

const $ = <T extends Element>(selector: string): T => {
  const element = document.querySelector<T>(selector);
  if (!element) throw new Error(`Missing UI element: ${selector}`);
  return element;
};

function closeMobileNav(focusToggle = false): void {
  $<HTMLElement>('[data-account-pane]').classList.remove('open');
  const toggle = $<HTMLButtonElement>('[data-open-nav]');
  toggle.setAttribute('aria-expanded', 'false');
  if (focusToggle) toggle.focus();
}

const state = {
  accounts: [] as Account[], folders: new Map<number, Folder[]>(), messages: [] as Summary[], detail: null as Detail | null,
  accountId: null as number | null, folderId: null as number | null, page: 0, pages: 0, filter: 'all', query: '',
  trash: false, drafts: false, extra: null as ExtraView, messageTotal: 0, draftId: null as string | null
};

let searchTimer = 0;
let draftTimer = 0;
let composeContext = { inReplyTo: null as string | null, referencesHeader: null as string | null, sourceMessageId: null as number | null };
let draftSave: Promise<string | null> | null = null;
let composeSending = false;
let composeClosing = false;
let messageToastTimer = 0;
let messageToastHideTimer = 0;
let messageToastFrame = 0;
let attachmentPreviewRequest = 0;
let mailboxViewRevision = 0;
let lastTrashedIds: number[] = [];
let lastTrashedDraftIds: string[] = [];
const mutatingMessageIds = new Set<number>();
let incomingDraftId: string | null = null;
const mutatingDraftIds = new Set<string>();
let incomingMessage: { id: number; className: 'pinning-in' | 'restoring' } | null = null;
let detailExternalImages = false;
let detailOriginalFormatting = false;
let pendingExternalUrl: string | null = null;
const selectedMessageIds = new Set<number>();
let accountOrder: ReturnType<typeof initAccountOrder> | undefined;
let pendingAccountRender = false;
let accountOrderRevision = 0;
let accountListInitialized = false;
const enteringAccountIds = new Set<number>();
let composeAccountPicker: ReturnType<typeof initComposeAccountPicker> | undefined;
let mailboxRequest = 0;
let draftCountRequest = 0;
let openingDraft = false;
let storedDraftAttachments: Attachment[] = [];
let originalDraftHtml: { html: string; text: string } | null = null;
let trashItems: TrashItem[] = [];
let visibleDrafts: DraftSummary[] = [];
let trashCountRequest = 0;
let titleCountRequest = 0;
let inboxUnreadCount: number | null = null;
let inboxCountAccountId: number | null = null;
let inboxCountFolderId: number | null = null;
let draftTotal = 0;
let cannedRequest = 0;
let mailExtras: ReturnType<typeof initMailExtras>;
let mailSharing: ReturnType<typeof initMailSharing> | undefined;
let routeReady=false, navigationRevision=0;
function syncMailUrl(replace=false):void {
  if(!routeReady)return;
  navigationRevision++;
  const url=mailLocationUrl({view:state.extra??(state.trash?'trash':state.drafts?'drafts':'inbox'),accountId:state.accountId,folderId:state.folderId,
    filter:state.extra==='sent'||state.drafts?'all':state.filter,query:state.query,page:state.page,messageId:state.extra||state.drafts?null:state.detail?.id??null});
  if(window.location.pathname+window.location.search!==url)window.history[replace?'replaceState':'pushState'](null,'',url);
}
async function restoreMailLocation():Promise<void>{
  const revision=++navigationRevision,route=readMailLocation(new URL(window.location.href));
  closeMobileNav();
  mailExtras.leave();hideMessageToast();selectedMessageIds.clear();clearDetail();
  const routeAccountId = route.view === 'trash' && route.accountId === null
    ? state.accounts[0]?.id ?? null : route.accountId;
  const account=[...state.accounts,...(mailSharing?.accounts()??[])].find(a=>a.id===routeAccountId);
  state.accountId=account && (route.view!=='trash'||state.accounts.some(own=>own.id===account.id)||sharedPermissions(account.id)?.canDelete)?account.id:null;
  state.folderId=account?route.folderId:null;
  mailSharing?.reveal(state.accountId);
  state.extra=route.view==='contacts'||route.view==='sent'?route.view:null;state.trash=route.view==='trash';state.drafts=route.view==='drafts';
  state.filter=route.filter;state.query=route.query;state.page=route.page;
  $<HTMLInputElement>('[data-search]').value=state.query;
  setMailboxTitle(state.extra==='contacts'?'Contacts':state.extra==='sent'?'Sent':state.trash?'Trash':state.drafts?'Drafts':account?.email??'Unified inbox',state.trash?account?.email:undefined);
  renderAccounts();updatePanePrimaryAction();updateEmptyStateCopy();
  await loadMessages();if(revision!==navigationRevision)return;
  if(route.messageId)await openMessage(route.messageId,false);
  if(revision===navigationRevision)syncMailUrl(true);
}
function sharedPermissions(id: number | null) { return mailSharing?.permission(id); }
function setMailboxTitle(title: string, accountEmail?: string): void {
  $('[data-mailbox-title]').textContent = title;
  const account = $('[data-mailbox-account]');
  account.textContent = accountEmail ?? '';
  account.classList.toggle('hidden', !accountEmail);
  account.closest('.pane-toolbar')?.classList.toggle('has-account-title', Boolean(accountEmail));
  renderDocumentTitle();
}

function renderDocumentTitle(): void {
  const account = [...state.accounts, ...(mailSharing?.accounts() ?? [])].find(item => item.id === state.accountId);
  const label = state.extra === 'contacts' ? 'Contacts' : state.extra === 'sent' ? 'Sent'
    : state.trash ? 'Trash' : state.drafts ? 'Drafts'
      : state.folderId === null ? 'Inbox'
        : state.folders.get(state.accountId!)?.find(folder => folder.id === state.folderId)?.name ?? 'Inbox';
  const count = state.trash ? (trashCountAccountId === state.accountId ? trashTotal : 0)
    : state.drafts ? draftTotal
      : state.extra || inboxCountAccountId !== state.accountId || inboxCountFolderId !== state.folderId ? 0
        : inboxUnreadCount ?? 0;
  document.title = `${label}${count > 0 ? ` (${count})` : ''}${account ? ` | ${account.email}` : ''} | Dispatch`;
}

async function refreshInboxUnreadCount(): Promise<void> {
  const request = ++titleCountRequest;
  const accountId = state.accountId;
  const folderId = state.folderId;
  if (state.extra || state.trash || state.drafts) {
    inboxUnreadCount = null;
    renderDocumentTitle();
    return;
  }
  if (inboxCountAccountId !== accountId || inboxCountFolderId !== folderId) {
    inboxUnreadCount = null;
    renderDocumentTitle();
  }
  try {
    const params = new URLSearchParams({ page: '0', size: '1', unread: 'true', trashed: 'false' });
    if (accountId !== null) params.set('accountId', String(accountId));
    if (folderId !== null) params.set('folderId', String(folderId));
    const result = await api<Page<Summary>>(`/api/mail/messages?${params}`);
    if (request !== titleCountRequest || state.accountId !== accountId || state.folderId !== folderId || state.extra || state.trash || state.drafts) return;
    inboxUnreadCount = result.totalElements;
    inboxCountAccountId = accountId;
    inboxCountFolderId = folderId;
    renderDocumentTitle();
  } catch {
    if (request !== titleCountRequest || state.accountId !== accountId || state.folderId !== folderId) return;
    inboxUnreadCount = null;
    renderDocumentTitle();
  }
}
function canOrganize(id: number) { return sharedPermissions(id)?.canOrganize ?? true; }
function canDelete(id: number) { return sharedPermissions(id)?.canDelete ?? true; }
function senderAccounts() { return [...state.accounts, ...(mailSharing?.senderAccounts().map(a=>({...a, active:true})) ?? [])]; }
function readableAccounts() { return state.accounts.length > 0 || !!mailSharing?.accounts().length; }

let extraUndo: (() => Promise<void>) | null = null;
let trashTotal = 0;
let trashCountAccountId: number | null = null;

function renderTrashCount(): void {
  $('[data-trash-count]').textContent = state.accountId !== null && trashCountAccountId === state.accountId && trashTotal > 0
    ? String(trashTotal) : '';
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error && error.message) return error.message;
  return 'The operation could not be completed.';
}

function alert(message: string): void {
  const box = $<HTMLElement>('[data-global-alert]'); box.textContent = message; box.classList.remove('hidden');
  window.setTimeout(() => box.classList.add('hidden'), 6000);
}

async function reconcileStaleMailbox(error: unknown, accountId?: number | null): Promise<boolean> {
  if (!(error instanceof ApiError) || ![409, 500, 502].includes(error.status)) return false;
  const ids = accountId ? state.accounts.filter(account => account.id === accountId).map(account => account.id)
    : state.accounts.map(account => account.id);
  if (ids.length === 0) return false;
  try {
    await Promise.all(ids.map(id => api<void>(`/api/mail/accounts/${id}/sync`, { method: 'POST' })));
    await loadAccounts(false);
    await loadMessages();
    showMessageToast('Mailbox updated from Gmail', 'M20 11a8 8 0 0 0-14.8-4M4 4v5h5M4 13a8 8 0 0 0 14.8 4M20 20v-5h-5', 'success', false, 4500);
    return true;
  } catch (syncError) {
    alert(errorMessage(syncError));
    return true;
  }
}

function confirmAction(title: string, message: string, confirmLabel: string): Promise<boolean> {
  const dialog = $<HTMLDialogElement>('[data-confirm-modal]');
  $('[data-confirm-title]').textContent = title;
  $('[data-confirm-message]').textContent = message;
  $('[data-confirm-submit]').textContent = confirmLabel;
  dialog.returnValue = 'cancel';
  dialog.showModal();
  return new Promise(resolve => {
    dialog.addEventListener('close', () => resolve(dialog.returnValue === 'confirm'), { once: true });
  });
}

async function copyText(value: string, button: HTMLButtonElement): Promise<void> {
  try {
    await navigator.clipboard.writeText(value);
  } catch {
    const input = document.createElement('textarea');
    input.value = value; input.style.position = 'fixed'; input.style.opacity = '0';
    document.body.append(input); input.select(); document.execCommand('copy'); input.remove();
  }
  const previous = button.title; button.title = 'Copied'; button.setAttribute('aria-label', 'Copied');
  window.setTimeout(() => { button.title = previous; button.setAttribute('aria-label', 'Copy setup key'); }, 1600);
}

function initials(value: string): string {
  const normalized = value.replace(/<.*?>/g, '').trim();
  return normalized.slice(0, 2).toUpperCase() || '?';
}

export function formatMessageDate(value: string, now = new Date()): string {
  if (!value) return '';
  const item = new Date(value);
  if (Number.isNaN(item.getTime())) return '';
  const time = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', hour12: false }).format(item);
  if (item.toDateString() === now.toDateString()) return time;
  const sevenDaysAgo = new Date(now);
  sevenDaysAgo.setDate(now.getDate() - 7);
  if (item <= now && item > sevenDaysAgo) {
    const weekday = new Intl.DateTimeFormat('en', { weekday: 'short' }).format(item);
    return `${time} ${weekday}`;
  }
  return `${String(item.getDate()).padStart(2, '0')}.${String(item.getMonth() + 1).padStart(2, '0')}.${item.getFullYear()}`;
}

export function messageContentUrl(id: number, externalImages = false, original = false): string {
  return `/api/mail/messages/${id}/content?externalImages=${externalImages}&original=${original}`;
}

export function safeExternalLink(value: string): { url: string; host: string; secure: boolean } | null {
  try {
    const parsed = new URL(value);
    if (parsed.protocol === 'mailto:') {
      const address = parsed.pathname.split('?')[0];
      return address ? { url: parsed.href, host: address, secure: true } : null;
    }
    if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') return null;
    return { url: parsed.href, host: parsed.hostname, secure: parsed.protocol === 'https:' };
  } catch {
    return null;
  }
}

export function profileInitials(value: string): string {
  const parts = value.trim().split(/\s+/).filter(Boolean);
  if (!parts.length) return 'A';
  return `${parts[0][0]}${parts.length > 1 ? parts[parts.length - 1][0] : ''}`.toLocaleUpperCase();
}

function node<K extends keyof HTMLElementTagNameMap>(tag: K, className?: string, text?: string): HTMLElementTagNameMap[K] {
  const element = document.createElement(tag); if (className) element.className = className; if (text !== undefined) element.textContent = text; return element;
}

function icon(pathData: string): SVGSVGElement {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.classList.add('nav-icon'); svg.setAttribute('viewBox', '0 0 24 24'); svg.setAttribute('aria-hidden', 'true');
  const path = document.createElementNS('http://www.w3.org/2000/svg', 'path'); path.setAttribute('d', pathData); svg.append(path);
  return svg;
}

function messageIcon(pathData: string): SVGSVGElement {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 24 24'); svg.setAttribute('aria-hidden', 'true');
  const path = document.createElementNS('http://www.w3.org/2000/svg', 'path'); path.setAttribute('d', pathData); svg.append(path);
  return svg;
}

function selectionCheckIcon(): SVGSVGElement {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 16 16'); svg.setAttribute('aria-hidden', 'true');
  const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  path.setAttribute('d', 'M13.78 4.22a.75.75 0 0 1 0 1.06l-7.25 7.25a.75.75 0 0 1-1.06 0L2.22 9.28a.751.751 0 0 1 .018-1.042.751.751 0 0 1 1.042-.018L6 10.94l6.72-6.72a.75.75 0 0 1 1.06 0Z');
  svg.append(path); return svg;
}

export function totalUnreadCount(folders: Array<{ unreadCount: number }>): number {
  return folders.reduce((total, folder) => total + Math.max(0, folder.unreadCount), 0);
}

async function loadAccounts(showLoading = true): Promise<void> {
  const loading = $<HTMLElement>('[data-account-loading]'); if (showLoading) loading.classList.remove('hidden');
  try {
    const revision = accountOrderRevision;
    const accounts = await api<Account[]>('/api/mail/accounts');
    if (revision !== accountOrderRevision) {
      // A refresh started before an order save must not undo that newer order.
      const positions = new Map(state.accounts.map((account, index) => [account.id, index]));
      accounts.sort((a, b) => (positions.get(a.id) ?? Infinity) - (positions.get(b.id) ?? Infinity));
    }
    if (accountListInitialized) {
      const previous = new Set(state.accounts.map(account => account.id));
      accounts.filter(account => !previous.has(account.id)).forEach(account => enteringAccountIds.add(account.id));
    }
    state.accounts = accounts;
    accountListInitialized = true;
    await Promise.all(state.accounts.map(async account => {
      try { state.folders.set(account.id, await api<Folder[]>(`/api/mail/folders?accountId=${account.id}`)); }
      catch { state.folders.set(account.id, []); }
    }));
    await mailSharing?.refresh().catch(error=>alert(errorMessage(error)));
    renderAccounts(); renderManagedAccounts();
    const animatedIds = [...enteringAccountIds];
    window.setTimeout(() => animatedIds.forEach(id => enteringAccountIds.delete(id)), 450);
    fillComposeAccounts(); updateAccountAvailability();
  } catch (error) { alert(errorMessage(error)); } finally { if (showLoading) loading.classList.add('hidden'); }
}

function renderAccounts(): void {
  renderTrashCount();
  renderDocumentTitle();
  if (accountOrder?.busy()) { pendingAccountRender = true; return; }
  pendingAccountRender = false;
  const list = $<HTMLElement>('[data-account-list]'); list.replaceChildren();
  $<HTMLElement>('[data-unified]').classList.toggle('active', !state.extra && !state.trash && !state.drafts && state.accountId === null);
  $('[data-contacts]').classList.toggle('active', state.extra === 'contacts');
  $('[data-sent]').classList.toggle('active', state.extra === 'sent');
  $<HTMLElement>('[data-trash]').classList.toggle('active', state.trash);
  $<HTMLElement>('[data-drafts]').classList.toggle('active', state.drafts);
  for (const account of state.accounts) {
    const folders = state.folders.get(account.id) ?? [];
    const unread = totalUnreadCount(folders);
    const row = node('div', 'account-head account-entry'); row.tabIndex = -1;
    if (enteringAccountIds.has(account.id)) row.classList.add('account-entering');
    const head = node('button', `nav-item account-main${state.accountId === account.id ? ' active' : ''}`); head.type='button';
    row.dataset.account = String(account.id);
    head.title = `${account.displayName} · ${account.syncStatus ?? 'Pending'} · Drag to reorder`;
    head.setAttribute('aria-describedby', 'account-order-help');
    head.setAttribute('aria-keyshortcuts', 'Alt+ArrowUp Alt+ArrowDown');
    const email = node('span', 'account-email', account.email);
    const count = node('span', `nav-count${account.syncStatus === 'ERROR' ? ' error' : ''}`,
      account.syncStatus === 'ERROR' ? '!' : unread ? String(unread) : '');
    if (account.syncStatus === 'ERROR') {
      const syncError = account.syncError || 'Mail synchronization failed. Try synchronizing again.';
      count.title = syncError;
      count.setAttribute('aria-label', syncError);
      head.title = `${account.displayName} · ${syncError}`;
    } else if (unread) count.title = `${unread} unread`;
    head.append(account.authProvider === 'GOOGLE' ? gmailAccountIcon() : icon('M4 6.5h16v11H4zM4.5 7l7.5 6 7.5-6'), email, count);
    row.addEventListener('click', () => selectMailbox(account.id, null, account.email)); row.append(head);
    const shared = mailSharing?.sharingButton(account.id, account.email); if(shared){row.classList.add('is-shared');row.append(shared);}
    list.append(row);
  }
  mailSharing?.renderSidebar();
}

function selectMailbox(accountId: number | null, folderId: number | null, title: string): void {
  mailExtras.leave(); state.extra = null;
  hideMessageToast();
  if (state.filter === 'drafts') state.filter = 'all';
  state.accountId = accountId; state.folderId = folderId; state.page = 0; state.trash = false; state.drafts = false;
  selectedMessageIds.clear();
  setMailboxTitle(title);
  updatePanePrimaryAction();
  clearDetail();
  syncMailUrl();renderAccounts(); updateEmptyStateCopy(); void loadMessages();
  closeMobileNav();
}

function selectTrash(): void {
  mailExtras.leave(); state.extra = null;
  hideMessageToast();
  if (!state.accounts.some(account => account.id === state.accountId) && !sharedPermissions(state.accountId)?.canDelete)
    state.accountId = state.accounts[0]?.id ?? null;
  state.folderId = null; state.page = 0; state.trash = true; state.drafts = false;
  selectedMessageIds.clear();
  setMailboxTitle('Trash', [...state.accounts,...(mailSharing?.accounts()??[])].find(account => account.id === state.accountId)?.email);
  updatePanePrimaryAction();
  clearDetail();
  syncMailUrl();renderAccounts(); updateEmptyStateCopy(); void loadMessages();
  closeMobileNav();
}

async function loadMessages(): Promise<void> {
  void refreshTrashCount();
  void refreshInboxUnreadCount();
  const request = ++mailboxRequest;
  if (state.extra) { await mailExtras.load(state.query,state.page); return; }
  const list = $<HTMLElement>('[data-message-list]'), loading = ensureMessageLoading(list);
  loading.classList.remove('hidden'); $<HTMLElement>('[data-message-empty]').classList.add('hidden'); list.replaceChildren(loading);
  if (state.drafts) {
    try {
      const params = new URLSearchParams({ page: String(state.page), query: state.query });
      const result = await api<Page<DraftSummary>>(`/api/mail/outbound/drafts?${params}`);
      if (request !== mailboxRequest) return;
      if (!result.content.length && result.totalPages > 0 && state.page >= result.totalPages) { state.page = result.totalPages - 1; await loadMessages(); return; }
      state.pages = result.totalPages; state.page = result.page; state.messageTotal = result.totalElements;
      visibleDrafts = result.content; renderDrafts(result.content); renderPagination(); updateEmptyStateCopy(); updatePanePrimaryAction();
    } catch (error) { if (request === mailboxRequest) { list.replaceChildren(); alert(errorMessage(error)); } }
    return;
  }
  if (state.trash) {
    try {
      const params = new URLSearchParams({ page: String(state.page), query: state.query, filter: state.filter });
      if (state.accountId) params.set('accountId', String(state.accountId));
      const result = await api<Page<TrashItem>>(`/api/mail/trash?${params}`);
      if (request !== mailboxRequest) return;
      if (!result.content.length && result.totalPages > 0 && state.page >= result.totalPages) { state.page = result.totalPages - 1; await loadMessages(); return; }
      trashItems = result.content; state.messages = trashItems.flatMap(item => item.message ? [item.message] : []);
      state.pages = result.totalPages; state.page = result.page; state.messageTotal = result.totalElements;
      renderMessages(); renderPagination(); updateEmptyStateCopy(); updatePanePrimaryAction();
    } catch (error) { if (request === mailboxRequest) { list.replaceChildren(); alert(errorMessage(error)); } }
    return;
  }
  const params = new URLSearchParams({ page: String(state.page), size: '30' });
  params.set('trashed', String(state.trash));
  if (state.accountId) params.set('accountId', String(state.accountId)); if (state.folderId) params.set('folderId', String(state.folderId));
  if (state.query) params.set('query', state.query);
  if (state.filter === 'unread') params.set('unread', 'true'); if (state.filter === 'starred') params.set('starred', 'true');
  try {
    const result = await api<Page<Summary>>(`/api/mail/messages?${params}`);
    if (request !== mailboxRequest) return;
    state.messages = result.content; state.pages = result.totalPages; state.page = result.page; state.messageTotal = result.totalElements;
    renderMessages(); renderPagination(); updateEmptyStateCopy();
    updatePanePrimaryAction();
  } catch (error) { if (request === mailboxRequest) { list.replaceChildren(); alert(errorMessage(error)); $<HTMLElement>('[data-message-empty]').classList.remove('hidden'); } }
}

function selectDrafts(): void {
  mailExtras.leave(); state.extra = null;
  hideMessageToast();
  state.drafts = true; state.trash = false; state.accountId = null; state.folderId = null; state.page = 0;
  state.query = ''; $<HTMLInputElement>('[data-search]').value = '';
  selectedMessageIds.clear();
  clearDetail();
  setMailboxTitle('Drafts');
  syncMailUrl();
  renderAccounts(); updatePanePrimaryAction(); updateEmptyStateCopy();
  void loadMessages(); void refreshDraftCount();
  closeMobileNav();
}

function selectExtra(view: ExtraView): void {
  mailExtras.leave(); hideMessageToast(); state.extra = view; state.trash = false; state.drafts = false;
  state.accountId = null; state.folderId = null; state.page = 0; state.query = ''; state.filter = 'all';
  setMailboxTitle(view === 'contacts' ? 'Contacts' : 'Sent');
  $<HTMLInputElement>('[data-search]').value = ''; selectedMessageIds.clear(); clearDetail();
  syncMailUrl();
  renderAccounts(); updatePanePrimaryAction(); updateEmptyStateCopy(); void loadMessages();
  closeMobileNav();
}

async function refreshDraftCount(): Promise<void> {
  const request = ++draftCountRequest;
  try {
    const result = await api<{ count: number }>('/api/mail/outbound/drafts/count');
    if (request === draftCountRequest) {
      draftTotal = result.count;
      $('[data-draft-count]').textContent = result.count > 0 ? String(result.count) : '';
      renderDocumentTitle();
    }
  } catch { /* Keep the last known count when offline. */ }
}

function refreshDraftView(): void {
  void refreshDraftCount();
  if (state.drafts) void loadMessages();
}

async function refreshTrashCount(): Promise<void> {
  const request = ++trashCountRequest;
  const accountId = state.accountId;
  if (accountId === null) {
    trashTotal = 0;
    trashCountAccountId = null;
    renderTrashCount();
    renderDocumentTitle();
    updatePanePrimaryAction();
    return;
  }
  try {
    const result = await api<{ count: number }>(`/api/mail/trash/count?accountId=${accountId}`);
    if (request !== trashCountRequest || state.accountId !== accountId) return;
    trashTotal = result.count;
    trashCountAccountId = accountId;
    renderTrashCount();
    renderDocumentTitle();
    updatePanePrimaryAction();
  } catch {
    if (request !== trashCountRequest || state.accountId !== accountId) return;
    trashTotal = 0;
    trashCountAccountId = null;
    renderTrashCount();
    renderDocumentTitle();
    updatePanePrimaryAction();
  }
}

async function trashDraft(id: string, item: HTMLElement, trashed: boolean): Promise<void> {
  const view = mailboxViewRevision;
  if (mutatingDraftIds.has(id)) return;
  mutatingDraftIds.add(id);
  item.dataset.busy = 'true';
  try {
    if (trashed && state.draftId === id && $<HTMLDialogElement>('[data-compose-modal]').open) {
      await closeCompose();
      if ($<HTMLDialogElement>('[data-compose-modal]').open) return;
      await loadMessages();
      item = [...document.querySelectorAll<HTMLElement>('[data-draft-id]')].find(row => row.dataset.draftId === id) ?? item;
    }
    item.classList.add(trashed ? 'removing' : 'trash-restoring-out');
    if (trashed && view === mailboxViewRevision) showMovingToTrashToast();
    await Promise.all([
      api(`/api/mail/outbound/drafts/${id}/trashed`, { method: 'PATCH', body: JSON.stringify({ value: trashed }) }),
      new Promise(resolve => window.setTimeout(resolve, trashed ? 190 : 400))
    ]);
    if (trashed && view === mailboxViewRevision) showTrashToast([], [id]);
    void loadMessages(); void refreshDraftCount();
  } catch (error) { item.classList.remove('removing', 'trash-restoring-out'); if (trashed && view === mailboxViewRevision) hideMessageToast(); alert(errorMessage(error)); }
  finally { delete item.dataset.busy; mutatingDraftIds.delete(id); }
}

function renderDrafts(drafts: DraftSummary[], append = false): void {
  const list = $<HTMLElement>('[data-message-list]'); if (!append) list.replaceChildren();
  if (!append) $('[data-message-empty]').classList.toggle('hidden', drafts.length !== 0);
  for (const draft of drafts) {
    const item = node('article', `message-item draft-item${state.draftId === draft.id ? ' active' : ''}`);
    item.dataset.draftId = draft.id;
    if (incomingDraftId === draft.id) {
      incomingDraftId = null; item.classList.add('restoring');
      item.addEventListener('animationend', () => item.classList.remove('restoring'), { once: true });
    }
    const open = node('button', 'message-open'); open.type = 'button';
    open.setAttribute('aria-label', `Open draft ${draft.subject || '(No subject)'}`);
    const avatar = node('span', 'message-avatar'); avatar.append(messageIcon('M14 3H5v18h14V8l-5-5Zm0 0v5h5M8 13h8M8 17h5'));
    const copy = node('span', 'message-copy');
    const meta = node('span', 'message-meta'); meta.append(node('span', 'message-from', draft.recipients ? `To: ${draft.recipients}` : 'No recipients yet'));
    copy.append(meta, node('span', 'message-subject', draft.subject || '(No subject)'), node('span', 'message-preview', draft.preview || 'Empty draft'));
    const side = node('span', 'message-side'); side.append(node('time', '', formatMessageDate(draft.savedAt)));
    open.append(avatar, copy, side);
    if (state.trash) {
      open.setAttribute('aria-label', `Restore draft ${draft.subject || '(No subject)'}`);
      open.addEventListener('click', () => void trashDraft(draft.id, item, false));
      meta.prepend(node('span', 'draft-label', 'Draft'));
    } else open.addEventListener('click', () => void openSavedDraft(draft.id));
    const action = node('button', `message-action-button ${state.trash ? 'message-restore-action' : 'message-delete-action'}`);
    action.type = 'button'; action.title = state.trash ? 'Restore draft' : 'Move draft to Trash'; action.setAttribute('aria-label', action.title);
    action.append(messageIcon(state.trash ? 'M3 12a9 9 0 1 0 3-6.7M3 4v6h6' : 'M3 6h18M8 6V4h8v2m-9 0 1 14h8l1-14M10 10v6m4-6v6'));
    const trashed = !state.trash;
    action.addEventListener('click', () => void trashDraft(draft.id, item, trashed));
    item.append(open, action); list.append(item);
  }
}

function updatePanePrimaryAction(): void {
  const available = readableAccounts();
  const searchLabel=state.extra==='contacts'?'Search contacts':state.extra==='sent'?'Search sent mail':'Search mail';
  $<HTMLInputElement>('[data-search]').placeholder=searchLabel;
  $('[data-search]').setAttribute('aria-label',searchLabel);
  $<HTMLInputElement>('[data-search]').disabled = !available && !state.drafts && !state.trash && !state.extra;
  const refresh = $<HTMLButtonElement>('[data-refresh]');
  refresh.title=state.extra?'Refresh':'Synchronize';refresh.setAttribute('aria-label',refresh.title);
  if (!refresh.classList.contains('syncing')) refresh.disabled = !available && !state.drafts && !state.trash && !state.extra;
  document.querySelector('[data-filter]')?.parentElement?.classList.toggle('hidden', state.drafts || state.extra === 'sent');
  document.querySelector('.pane-toolbar')?.classList.toggle('with-divider', state.drafts || state.extra === 'sent');
  $('[data-filter="unread"]').classList.toggle('hidden', state.extra === 'contacts');
  $('[data-filter="drafts"]').classList.toggle('hidden', !state.trash);
  document.querySelectorAll<HTMLElement>('[data-filter]').forEach(button => button.classList.toggle('active', button.dataset.filter === state.filter));
  document.querySelector<HTMLElement>('[data-compose-toolbar]')?.classList.toggle('hidden', state.trash || state.extra === 'contacts');
  $('[data-add-contact]').classList.toggle('hidden',state.extra !== 'contacts');
  const empty = document.querySelector<HTMLButtonElement>('[data-empty-trash]');
  empty?.classList.toggle('hidden', !state.trash);
  if (empty) empty.disabled = state.trash && (trashCountAccountId !== state.accountId || trashTotal === 0);
}

async function emptyTrash(): Promise<void> {
  if (!state.trash || trashCountAccountId !== state.accountId || trashTotal === 0) return;
  if (!await confirmAction('Empty Trash?', 'Permanently delete every message and draft in Trash? This cannot be undone.', 'Empty Trash')) return;
  const previousTotal = trashTotal;
  const button = $<HTMLButtonElement>('[data-empty-trash]'); button.disabled = true;
  try {
    const result = await api<{ deleted: number }>(`/api/mail/messages/trash${state.accountId ? `?accountId=${state.accountId}` : ''}`, { method: 'DELETE' });
    selectedMessageIds.clear(); if (state.detail) clearDetail(); state.page = 0;
    await loadMessages();
    showMessageToast(`${result.deleted} ${result.deleted === 1 ? 'message' : 'messages'} permanently deleted`, 'M3 6h18M8 6V4h8v2m-9 0 1 14h8l1-14M10 10v6m4-6v6', 'success', false, 4500);
    if (result.deleted < previousTotal) void reconcileStaleMailbox(new ApiError(409, 'Gmail changed'), state.accountId);
  } catch (error) { alert(errorMessage(error)); }
  finally { button.disabled = trashCountAccountId !== state.accountId || trashTotal === 0; }
}

export function ensureMessageLoading(list: HTMLElement): HTMLElement {
  const existing = list.querySelector<HTMLElement>('[data-message-loading]');
  if (existing) return existing;
  const loading = node('div', 'message-loading'); loading.dataset.messageLoading = '';
  loading.append(node('div'), node('div'), node('div'), node('div'));
  return loading;
}

function renderMessages(): void {
  if (state.extra) return;
  if (state.drafts) { renderDrafts(visibleDrafts); return; }
  const list = $<HTMLElement>('[data-message-list]'); list.replaceChildren();
  const entrance = incomingMessage; incomingMessage = null;
  const visibleIds = new Set(state.messages.map(message => message.id));
  for (const id of selectedMessageIds) {
    const message = state.messages.find(item => item.id === id);
    if (!visibleIds.has(id) || !message || !canOrganize(message.accountId) || !canDelete(message.accountId)) selectedMessageIds.delete(id);
  }
  $<HTMLElement>('[data-message-empty]').classList.toggle('hidden', state.messages.length !== 0);
  for (const message of state.messages) {
    const selected = selectedMessageIds.has(message.id);
    const item = node('article', `message-item${message.read ? '' : ' unread'}${message.pinned ? ' pinned' : ''}${selected ? ' selected' : ''}${state.detail?.id === message.id ? ' active' : ''}`);
    item.dataset.messageId = String(message.id);
    if (entrance?.id === message.id) {
      item.classList.add(entrance.className);
      item.addEventListener('animationend', () => item.classList.remove(entrance.className), { once: true });
    }
    const open = node('button', 'message-open'); open.type = 'button'; open.setAttribute('aria-label', `Open ${message.subject}`);
    const avatar = node('span', 'message-avatar', initials(message.fromAddress));
    const copy = node('span', 'message-copy'); const meta = node('span', 'message-meta');
    meta.append(node('span', 'message-from', message.fromAddress));
    if (message.hasAttachments) {
      const attachment = node('span', 'message-attachment'); attachment.title = 'Has attachments';
      attachment.append(messageIcon('m21.4 11.1-9.2 9.2a6 6 0 0 1-8.5-8.5l9.2-9.2a4 4 0 0 1 5.7 5.7l-9.2 9.2a2 2 0 0 1-2.8-2.8l8.5-8.5'));
      meta.append(attachment);
    }
    copy.append(meta, node('span', 'message-subject', message.subject), node('span', 'message-preview', message.preview));
    const side = node('span', 'message-side'); const received = node('time', '', formatMessageDate(message.receivedAt));
    received.dateTime = message.receivedAt; received.title = new Date(message.receivedAt).toLocaleString(); side.append(received);
    open.append(avatar, copy, side); open.addEventListener('click', () => void openMessage(message.id));

    const select = node('button', `message-select-toggle${selected ? ' active' : ''}`); select.type = 'button';
    select.title = selected ? 'Deselect message' : 'Select message'; select.setAttribute('aria-label', select.title); select.setAttribute('aria-pressed', String(selected));
    const checkbox = node('span', 'message-checkbox'); if (selected) checkbox.append(selectionCheckIcon()); select.append(checkbox);
    select.addEventListener('click', () => toggleMessageSelection(message.id));

    const actions = node('span', 'message-hover-actions');
    const pin = node('button', 'message-action-button message-pin-action'); pin.type = 'button';
    setPinButtonState(pin, message.pinned);
    pin.append(messageIcon('M12 17v5M5 3h14l-2 7 3 3H4l3-3-2-7Z'));
    pin.addEventListener('click', () => void togglePinned(message, item, pin));
    actions.append(pin);
    const read = node('button', 'message-action-button message-read-action'); read.type = 'button';
    setReadButtonState(read, message.read);
    read.addEventListener('click', () => void toggleRead(message, item, read));
    let remove: HTMLButtonElement | null = null;
    if (!state.trash) {
      remove = node('button', 'message-action-button message-delete-action'); remove.type = 'button';
      remove.title = 'Move to Trash'; remove.setAttribute('aria-label', remove.title);
      remove.append(messageIcon('M3 6h18M8 6V4h8v2m-9 0 1 14h8l1-14M10 10v6m4-6v6'));
      remove.addEventListener('click', () => void trashMessage(message, item));
    } else {
      remove = node('button', 'message-action-button message-restore-action'); remove.type = 'button';
        remove.title = 'Restore from Trash'; remove.setAttribute('aria-label', remove.title);
      remove.append(messageIcon('M3 12a9 9 0 1 0 3-6.7M3 4v6h6'));
      remove.addEventListener('click', () => void restoreFromTrash(message, item));
    }
    const star = node('button', `message-star-action${message.starred ? ' active' : ''}`); star.type = 'button'; star.dataset.messageStar = String(message.id);
    star.title = message.starred ? 'Remove star' : 'Star message'; star.setAttribute('aria-label', star.title); star.setAttribute('aria-pressed', String(message.starred));
    star.append(messageIcon('m12 3 2.8 5.7 6.2.9-4.5 4.4 1.1 6.2-5.6-2.9-5.6 2.9 1.1-6.2L3 9.6l6.2-.9Z'));
    star.addEventListener('click', () => void toggleStarred(message, star));
    item.append(open);
    if(canOrganize(message.accountId)){item.append(actions,read,star);}
    if(canDelete(message.accountId)&&remove)item.append(remove);
    if(canOrganize(message.accountId)&&canDelete(message.accountId))item.append(select);
    else item.classList.add('no-selection');
    list.append(item);
  }
  if (state.trash) {
    renderDrafts(trashItems.flatMap(item => item.draft ? [item.draft] : []), true);
    const rows = [...list.children] as HTMLElement[];
    for (const entry of trashItems) {
      const row = rows.find(row => entry.message ? row.dataset.messageId === String(entry.message.id) : row.dataset.draftId === entry.draft?.id);
      if (row) list.append(row);
    }
    $('[data-message-empty]').classList.toggle('hidden', trashItems.length !== 0);
  }
  renderSelectionView();
}

function toggleMessageSelection(messageId: number): void {
  if (selectedMessageIds.has(messageId)) selectedMessageIds.delete(messageId);
  else selectedMessageIds.add(messageId);
  renderMessages();
}

function renderSelectionView(): void {
  const count = selectedMessageIds.size;
  const selection = $<HTMLElement>('[data-selection-detail]');
  if (count === 0) {
    selection.classList.add('hidden');
    if (state.detail) renderDetail();
    else {
      $<HTMLElement>('[data-message-detail]').classList.add('hidden');
      $<HTMLElement>('[data-reading-empty]').classList.remove('hidden');
      $<HTMLElement>('[data-reading-pane]').classList.remove('open');
    }
    return;
  }
  $<HTMLElement>('[data-reading-empty]').classList.add('hidden');
  $<HTMLElement>('[data-message-detail]').classList.add('hidden');
  selection.classList.remove('hidden');
  $('[data-selection-count]').textContent = `${count} ${count === 1 ? 'message' : 'messages'} selected`;
  $('[data-selection-delete-label]').textContent = state.trash ? 'Restore' : 'Delete';
  const icon = $<HTMLElement>('[data-selection-delete-icon]');
  icon.replaceChildren(messageIcon(state.trash
    ? 'M3 12a9 9 0 1 0 3-6.7M3 4v6h6'
    : 'M3 6h18M8 6V4h8v2m-9 0 1 14h8l1-14M10 10v6m4-6v6'));
  $<HTMLElement>('[data-reading-pane]').classList.add('open');
}

export function shouldAnimatePin(message: Pick<Summary, 'id' | 'pinned' | 'receivedAt'>,
  messages: readonly Pick<Summary, 'id' | 'pinned' | 'receivedAt'>[], page = 0): boolean {
  if (page !== 0 || message.id !== messages[0]?.id) return true;
  if (!message.pinned) return false;
  const receivedAt = Date.parse(message.receivedAt);
  if (!Number.isFinite(receivedAt)) return true;
  return messages.some(other => {
    if (other.id === message.id) return false;
    const otherReceivedAt = Date.parse(other.receivedAt);
    return other.pinned || !Number.isFinite(otherReceivedAt) || otherReceivedAt > receivedAt
      || (otherReceivedAt === receivedAt && other.id > message.id);
  });
}

export function setPinButtonState(button: HTMLButtonElement, pinned: boolean): void {
  button.classList.toggle('active', pinned);
  button.title = pinned ? 'Unpin message' : 'Pin message to top';
  button.setAttribute('aria-label', button.title);
  button.setAttribute('aria-pressed', String(pinned));
}

async function togglePinned(message: Summary, item: HTMLElement, control: HTMLButtonElement): Promise<void> {
  if (control.disabled) return;
  control.disabled = true;
  const previous = message.pinned, pinned = !previous;
  const animate = shouldAnimatePin(message, state.messages, state.page);
  const applyPinned = (value: boolean): void => {
    message.pinned = value;
    if (state.detail?.id === message.id) state.detail.pinned = value;
    item.classList.toggle('pinned', value);
    setPinButtonState(control, value);
  };
  if (animate) item.classList.add('pinning-out');
  else applyPinned(pinned);
  try {
    await Promise.all([
      api<void>(`/api/mail/messages/${message.id}/pinned`, { method: 'PATCH', body: JSON.stringify({ value: pinned }) }),
      animate ? new Promise(resolve => window.setTimeout(resolve, 500)) : Promise.resolve()
    ]);
    applyPinned(pinned);
    if (animate) {
      incomingMessage = { id: message.id, className: 'pinning-in' };
      await loadMessages();
    }
  } catch (error) { applyPinned(previous); item.classList.remove('pinning-out'); alert(errorMessage(error)); }
  finally { control.disabled = false; }
}

export function setReadButtonState(button: HTMLButtonElement, read: boolean): void {
  button.title = read ? 'Mark as unread' : 'Mark as read';
  button.setAttribute('aria-label', button.title);
  button.replaceChildren(messageIcon(read
    ? 'M4 6.5h16v11H4zM4.5 7l7.5 6 7.5-6'
    : 'M3 8l9 6 9-6M3 8l9-5 9 5v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z'));
}

async function toggleRead(message: Summary, item: HTMLElement, control: HTMLButtonElement): Promise<void> {
  if (control.dataset.busy === 'true') return;
  control.dataset.busy = 'true';
  const previous = message.read;
  const read = !previous;
  message.read = read;
  item.classList.toggle('unread', !read);
  setReadButtonState(control, read);
  if (state.detail?.id === message.id) state.detail.read = read;
  try {
    await api<void>(`/api/mail/messages/${message.id}/read`, { method: 'PATCH', body: JSON.stringify({ value: read }) });
    void refreshInboxUnreadCount();
    if (state.filter === 'unread' && read) await loadMessages();
  } catch (error) {
    message.read = previous;
    item.classList.toggle('unread', !previous);
    setReadButtonState(control, previous);
    if (state.detail?.id === message.id) state.detail.read = previous;
    alert(errorMessage(error));
  } finally {
    delete control.dataset.busy;
  }
}

export function setStarButtonState(button: HTMLButtonElement, starred: boolean, animate = false): void {
  button.classList.toggle('active', starred);
  button.setAttribute('aria-pressed', String(starred));
  button.title = starred ? 'Remove star' : 'Star message';
  button.setAttribute('aria-label', button.title);
  if (animate) {
    button.classList.remove('star-animating');
    window.requestAnimationFrame(() => button.classList.add('star-animating'));
  }
}

export function setSynchronizedStarState(buttons: Array<HTMLButtonElement | null | undefined>, starred: boolean, animate = false): void {
  for (const button of new Set(buttons.filter((item): item is HTMLButtonElement => Boolean(item)))) {
    setStarButtonState(button, starred, animate);
  }
}

async function toggleStarred(message: Summary, control?: HTMLButtonElement): Promise<void> {
  if (control?.dataset.busy === 'true') return;
  if (control) control.dataset.busy = 'true';
  const previous = message.starred;
  const starred = !previous;
  message.starred = starred;
  const detailStar = $<HTMLButtonElement>('[data-toggle-star]');
  const listStar = document.querySelector<HTMLButtonElement>(`[data-message-star="${message.id}"]`);
  const controls = [control, listStar, state.detail?.id === message.id ? detailStar : null];
  setSynchronizedStarState(controls, starred, true);
  if (state.detail?.id === message.id) {
    state.detail.starred = starred;
  }
  const view = mailboxViewRevision;
  const leavingStarred = state.filter === 'starred' && !starred;
  const row = leavingStarred ? document.querySelector<HTMLElement>(`[data-message-id="${message.id}"]`) : null;
  row?.classList.add('removing');
  try {
    await Promise.all([
      api<void>(`/api/mail/messages/${message.id}/starred`, { method: 'PATCH', body: JSON.stringify({ value: starred }) }),
      ...(leavingStarred ? [new Promise(resolve => window.setTimeout(resolve, 190))] : [])
    ]);
    if (leavingStarred && view === mailboxViewRevision) {
      if (state.detail?.id === message.id) clearDetail();
      await loadMessages();
    }
  } catch (error) {
    row?.classList.remove('removing');
    message.starred = previous;
    setSynchronizedStarState(controls, previous, true);
    if (state.detail?.id === message.id) {
      state.detail.starred = previous;
    }
    alert(errorMessage(error));
  } finally {
    if (control) delete control.dataset.busy;
  }
}

async function trashMessage(message: Summary, item: HTMLElement): Promise<void> {
  const view = mailboxViewRevision;
  if (mutatingMessageIds.has(message.id)) return;
  mutatingMessageIds.add(message.id);
  item.classList.add('removing');
  if (view === mailboxViewRevision) showMovingToTrashToast();
  try {
    await Promise.all([
      api<void>(`/api/mail/messages/${message.id}`, { method: 'DELETE' }),
      new Promise(resolve => window.setTimeout(resolve, 190))
    ]);
    if (state.detail?.id === message.id) clearDetail();
    if (view === mailboxViewRevision) showTrashToast([message.id]);
    void loadMessages();
  } catch (error) { item.classList.remove('removing'); if (view === mailboxViewRevision) hideMessageToast(); if (!await reconcileStaleMailbox(error, message.accountId)) alert(errorMessage(error)); }
  finally { mutatingMessageIds.delete(message.id); }
}

async function restoreFromTrash(message: Summary, item: HTMLElement): Promise<void> {
  item.classList.add('trash-restoring-out');
  try {
    await Promise.all([
      api<void>(`/api/mail/messages/${message.id}/trashed`, { method: 'PATCH', body: JSON.stringify({ value: false }) }),
      new Promise(resolve => window.setTimeout(resolve, 400))
    ]);
    if (state.detail?.id === message.id) clearDetail();
    await loadMessages();
  } catch (error) { item.classList.remove('trash-restoring-out'); if (!await reconcileStaleMailbox(error, message.accountId)) alert(errorMessage(error)); }
}

function showTrashToast(messageIds: number[], draftIds: string[] = []): void {
  lastTrashedIds = [...messageIds];
  lastTrashedDraftIds = [...draftIds];
  const count = messageIds.length + draftIds.length;
  const label = count === 1 ? 'Moved to Trash' : `${count} messages moved to Trash`;
  showMessageToast(label, 'M3 6h18M8 6V4h8v2m-9 0 1 14h8l1-14M10 10v6m4-6v6', 'trash', true, 6000);
}

function showMovingToTrashToast(): void {
  showMessageToast('Moving to Trash…', 'M3 6h18M8 6V4h8v2m-9 0 1 14h8l1-14M10 10v6m4-6v6', 'trash', false, 0);
}

function showSyncToast(): void {
  lastTrashedIds = []; lastTrashedDraftIds = [];
  showMessageToast('Synchronized successfully', 'M20 11a8 8 0 0 0-14.8-4M4 4v5h5M4 13a8 8 0 0 0 14.8 4M20 20v-5h-5', 'success', false, 4000);
}

function showMessageToast(label: string, iconPath: string, kind: 'trash' | 'success', showUndo: boolean, duration: number): void {
  window.clearTimeout(messageToastTimer); window.clearTimeout(messageToastHideTimer);
  const toast = $<HTMLElement>('[data-message-toast]'); toast.classList.remove('visible', 'hidden'); toast.dataset.kind = kind;
  $('[data-message-toast-label]').textContent = label;
  $<HTMLElement>('[data-message-toast-icon]').replaceChildren(messageIcon(iconPath));
  $<HTMLButtonElement>('[data-undo-trash]').classList.toggle('hidden', !showUndo);
  window.cancelAnimationFrame(messageToastFrame);
  messageToastFrame = window.requestAnimationFrame(() => toast.classList.add('visible'));
  if (duration > 0) messageToastTimer = window.setTimeout(hideMessageToast, duration);
}

function hideMessageToast(): void {
  extraUndo = null;
  mailboxViewRevision++;
  window.cancelAnimationFrame(messageToastFrame);
  window.clearTimeout(messageToastTimer); lastTrashedIds = []; lastTrashedDraftIds = [];
  const toast = $<HTMLElement>('[data-message-toast]'); toast.classList.remove('visible');
  window.clearTimeout(messageToastHideTimer);
  messageToastHideTimer = window.setTimeout(() => toast.classList.add('hidden'), 300);
}

async function undoTrash(): Promise<void> {
  if (extraUndo) {
    const action = extraUndo; extraUndo = null;
    try { await action(); hideMessageToast(); } catch(error) { extraUndo = action; alert(errorMessage(error)); }
    return;
  }
  const messageIds = [...lastTrashedIds], draftIds = [...lastTrashedDraftIds]; if (messageIds.length + draftIds.length === 0) return;
  const undo = $<HTMLButtonElement>('[data-undo-trash]'); if (undo.disabled) return; undo.disabled = true;
  window.clearTimeout(messageToastTimer);
  try {
    await Promise.all([
      ...messageIds.map(id => api<void>(`/api/mail/messages/${id}/trashed`, { method: 'PATCH', body: JSON.stringify({ value: false }) })),
      ...draftIds.map(id => api<void>(`/api/mail/outbound/drafts/${id}/trashed`, { method: 'PATCH', body: JSON.stringify({ value: false }) }))
    ]);
    if (draftIds.length === 1 && state.drafts) incomingDraftId = draftIds[0];
    hideMessageToast(); if (messageIds.length === 1) incomingMessage = { id: messageIds[0], className: 'restoring' }; await loadMessages();
    if (draftIds.length) await refreshDraftCount();
  } catch (error) { if (!await reconcileStaleMailbox(error)) alert(errorMessage(error)); }
  finally { undo.disabled = false; }
}

async function applySelectionDelete(): Promise<void> {
  const view = mailboxViewRevision;
  const messageIds = [...selectedMessageIds]; if (messageIds.length === 0) return;
  const items = messageIds.map(id => document.querySelector<HTMLElement>(`[data-message-id="${id}"]`)).filter((item): item is HTMLElement => item !== null);
  const restoring = state.trash;
  items.forEach(item => item.classList.add(restoring ? 'trash-restoring-out' : 'removing'));
  if (!restoring && view === mailboxViewRevision) showMovingToTrashToast();
  try {
    await Promise.all([
      ...messageIds.map(id => restoring
        ? api<void>(`/api/mail/messages/${id}/trashed`, { method: 'PATCH', body: JSON.stringify({ value: false }) })
        : api<void>(`/api/mail/messages/${id}`, { method: 'DELETE' })),
      new Promise(resolve => window.setTimeout(resolve, restoring ? 400 : 200))
    ]);
    selectedMessageIds.clear();
    if (state.detail && messageIds.includes(state.detail.id)) clearDetail();
    if (!restoring && view === mailboxViewRevision) showTrashToast(messageIds);
    void loadMessages();
  } catch (error) {
    items.forEach(item => item.classList.remove('trash-restoring-out', 'removing'));
    if (!restoring && view === mailboxViewRevision) hideMessageToast();
    if (!await reconcileStaleMailbox(error)) alert(errorMessage(error));
  }
}

async function markSelectionUnread(): Promise<void> {
  const messageIds = [...selectedMessageIds]; if (messageIds.length === 0) return;
  try {
    await Promise.all(messageIds.map(id => api<void>(`/api/mail/messages/${id}/read`, { method: 'PATCH', body: JSON.stringify({ value: false }) })));
    state.messages.filter(message => messageIds.includes(message.id)).forEach(message => { message.read = false; });
    if (state.detail && messageIds.includes(state.detail.id)) state.detail.read = false;
    selectedMessageIds.clear(); renderMessages(); void refreshInboxUnreadCount();
  } catch (error) { alert(errorMessage(error)); }
}

function cancelSelection(): void {
  selectedMessageIds.clear(); renderMessages();
}

function clearDetail(): void {
  $('[data-extra-detail]').classList.add('hidden');
  state.detail = null;
  detailExternalImages = false;
  detailOriginalFormatting = false;
  closeDetailMenu();
  hideExternalLinkConfirm();
  $<HTMLElement>('[data-selection-detail]').classList.add('hidden');
  $<HTMLElement>('[data-message-detail]').classList.add('hidden');
  $<HTMLElement>('[data-reading-empty]').classList.remove('hidden');
  $<HTMLElement>('[data-reading-pane]').classList.remove('open');
  $<HTMLIFrameElement>('[data-message-frame]').src = 'about:blank';
}

function renderPagination(): void {
  const bar = $<HTMLElement>('[data-pagination]'); bar.classList.toggle('hidden', state.pages <= 1);
  $('[data-page-label]').textContent = state.pages ? `${state.page + 1} of ${state.pages}` : '';
  $<HTMLButtonElement>('[data-prev]').disabled = state.page <= 0; $<HTMLButtonElement>('[data-next]').disabled = state.page + 1 >= state.pages;
}

async function openMessage(id: number, updateUrl=true): Promise<void> {
  const request = mailboxRequest;
  if (selectedMessageIds.size) { selectedMessageIds.clear(); renderMessages(); }
  try {
    const detail = await api<Detail>(`/api/mail/messages/${id}`);
    if (request !== mailboxRequest || state.extra) return;
    state.detail = detail;
    if(updateUrl)syncMailUrl();
    detailExternalImages = false; detailOriginalFormatting = false; closeDetailMenu(); hideExternalLinkConfirm();
    renderMessages(); renderDetail();
    $<HTMLElement>('[data-reading-pane]').classList.add('open');
    if (!state.detail.read && canOrganize(detail.accountId)) {
      await api<void>(`/api/mail/messages/${id}/read`, { method: 'PATCH', body: JSON.stringify({ value: true }) });
      state.detail.read = true; const summary = state.messages.find(item => item.id === id); if (summary) summary.read = true; renderMessages();
      void refreshInboxUnreadCount();
    }
  } catch (error) { alert(errorMessage(error)); }
}

function renderDetail(): void {
  const detail = state.detail; if (!detail) return;
  $<HTMLElement>('[data-reading-empty]').classList.add('hidden'); $<HTMLElement>('[data-message-detail]').classList.remove('hidden');
  $('[data-detail-subject]').textContent = detail.subject;
  const sender = senderParts(detail.fromAddress);
  $('[data-detail-from-name]').textContent = sender.name;
  $('[data-detail-from-address]').textContent = sender.address ? `<${sender.address}>` : '';
  $('[data-detail-to]').textContent = `To: ${detail.recipients}`;
  $('[data-detail-date]').textContent = new Intl.DateTimeFormat('en', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(detail.receivedAt));
  $('[data-sender-avatar]').textContent = initials(detail.fromAddress);
  $('[data-toggle-star]').classList.toggle('hidden',!canOrganize(detail.accountId));
  document.querySelectorAll<HTMLElement>('[data-reply],[data-reply-all],[data-forward]').forEach(button=>button.classList.toggle('hidden',sharedPermissions(detail.accountId)?.canSend===false));
  setStarButtonState($<HTMLButtonElement>('[data-toggle-star]'), detail.starred);
  refreshDetailFrame();
  renderAttachments(detail.attachments);
}

function senderParts(value: string): { name: string; address: string } {
  const match = value.trim().match(/^(.*?)\s*<([^>]+)>$/);
  if (!match) return { name: value.trim(), address: '' };
  const name = match[1].trim().replace(/^['"]|['"]$/g, '');
  return { name: name || match[2].trim(), address: match[2].trim() };
}

function refreshDetailFrame(): void {
  const detail = state.detail; if (!detail) return;
  const frame = $<HTMLIFrameElement>('[data-message-frame]');
  frame.classList.toggle('original-render', detailOriginalFormatting);
  frame.src = messageContentUrl(detail.id, detailExternalImages, detailOriginalFormatting);
  const renderButton = $<HTMLButtonElement>('[data-render-mode]');
  renderButton.classList.toggle('active', detailOriginalFormatting);
  renderButton.setAttribute('aria-pressed', String(detailOriginalFormatting));
  renderButton.title = detailOriginalFormatting ? 'Show simplified text view' : 'Show original email design';
  renderButton.setAttribute('aria-label', renderButton.title);
  const imageButton = $<HTMLButtonElement>('[data-load-images]');
  imageButton.disabled = detailExternalImages;
  imageButton.classList.toggle('active', detailExternalImages);
  $('[data-load-images-label]').textContent = detailExternalImages ? 'Remote images loaded' : 'Load remote images';
}

function closeDetailMenu(): void {
  const popover = document.querySelector<HTMLElement>('[data-detail-popover]');
  const trigger = document.querySelector<HTMLButtonElement>('[data-detail-menu]');
  popover?.classList.add('hidden');
  trigger?.setAttribute('aria-expanded', 'false');
}

function hideExternalLinkConfirm(): void {
  pendingExternalUrl = null;
  document.querySelector<HTMLElement>('[data-link-confirm]')?.classList.add('hidden');
}

function showExternalLinkConfirm(value: string, rect?: { left?: number; top?: number; bottom?: number }): void {
  const link = safeExternalLink(value);
  if (!link) {
    alert('This link uses an unsupported or unsafe address.');
    return;
  }
  pendingExternalUrl = link.url;
  const confirm = $<HTMLElement>('[data-link-confirm]');
  $('[data-link-host]').textContent = link.url.startsWith('mailto:')
    ? `Email address: ${link.host}`
    : `${link.secure ? 'Destination' : 'Unencrypted destination'}: ${link.host}`;
  $('[data-link-address]').textContent = link.url;
  confirm.classList.remove('hidden');
  const frameBox = $<HTMLIFrameElement>('[data-message-frame]').getBoundingClientRect();
  const desiredLeft = frameBox.left + (Number.isFinite(rect?.left) ? Number(rect?.left) : 20);
  const desiredTop = frameBox.top + (Number.isFinite(rect?.bottom) ? Number(rect?.bottom) : 24) + 8;
  const left = Math.max(12, Math.min(desiredLeft, window.innerWidth - confirm.offsetWidth - 12));
  let top = desiredTop;
  if (top + confirm.offsetHeight > window.innerHeight - 12) {
    const anchorTop = frameBox.top + (Number.isFinite(rect?.top) ? Number(rect?.top) : 24);
    top = Math.max(12, anchorTop - confirm.offsetHeight - 8);
  }
  confirm.style.left = `${left}px`;
  confirm.style.top = `${top}px`;
}

function renderAttachments(items: Attachment[]): void {
  const section = $<HTMLElement>('[data-attachments]'), list = $<HTMLElement>('[data-attachment-list]');
  section.classList.toggle('hidden', items.length === 0); list.replaceChildren();
  $('[data-attachment-heading]').textContent = `${items.length} ${items.length === 1 ? 'attachment' : 'attachments'}`;
  for (const item of items) {
    const row = node('div', 'attachment-item'), copy = node('div', 'attachment-copy');
    const fileIcon = node('span', 'attachment-file-icon'); fileIcon.append(messageIcon('M7 3h7l4 4v14H7zM14 3v5h5M10 12h5M10 16h5'));
    copy.append(node('strong', '', item.filename), node('small', `scan-${item.scanStatus.toLowerCase()}`, `${formatBytes(item.sizeBytes)} · ${scanLabel(item)}`)); row.append(fileIcon, copy);
    row.title = item.filename;
    const actions = node('div', 'attachment-actions');
    const action = (label: string, icon: string, handler: () => Promise<void>) => {
      const button = node('button', 'attachment-download'); button.type = 'button'; button.title = label; button.setAttribute('aria-label', `${label} ${item.filename}`);
      button.append(messageIcon(icon));
      button.addEventListener('click', () => { button.disabled = true; void handler().catch(error => alert(errorMessage(error))).finally(() => { button.disabled = false; }); });
      actions.append(button); return button;
    };
    if (item.scanStatus === 'CLEAN') {
      action('Preview', 'M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0', () => previewAttachment(item));
      action('Download', 'M12 3v12m0 0 4-4m-4 4-4-4M5 19h14', async () => {
        const blob = await api<Blob>(`/api/mail/attachments/${item.id}`, {}, true, true);
        const url = URL.createObjectURL(blob), link = document.createElement('a');
        link.href = url; link.download = item.filename; document.body.append(link); link.click(); link.remove();
        window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
      });
    } else if (item.scanStatus === 'UNAVAILABLE' && !sharedPermissions(state.detail?.accountId ?? null)) {
      action('Retry scan', 'M20 11a8 8 0 0 0-14.8-4M4 4v5h5M4 13a8 8 0 0 0 14.8 4M20 20v-5h-5', async () => {
        const result = await api<Pick<Attachment, 'scanStatus' | 'scanDetail'>>(`/api/mail/attachments/${item.id}/scan`, { method: 'POST' });
        Object.assign(item, result);
        if (state.detail?.attachments === items) renderAttachments(items);
        if (result.scanStatus === 'UNAVAILABLE') alert('The scanner is currently unavailable. Please try again shortly.');
      });
      copy.append(node('small', '', 'Retry scanning to enable download.'));
    }
    row.append(actions);
    list.append(row);
  }
}

async function previewAttachment(item: Attachment): Promise<void> {
  const request = ++attachmentPreviewRequest;
  const modal = $<HTMLDialogElement>('[data-attachment-preview-modal]');
  const content = $<HTMLElement>('[data-attachment-preview-content]');
  $('[data-attachment-preview-title]').textContent = item.filename;
  content.replaceChildren(node('p', 'settings-intro', 'Loading preview…')); modal.showModal();
  try {
    const result = await api<{ kind: string; content: string }>(`/api/mail/attachments/${item.id}/preview`);
    if (!modal.open || request !== attachmentPreviewRequest) return;
    if (result.kind === 'image') {
      const image = document.createElement('img'); image.alt = item.filename; image.src = result.content; content.replaceChildren(image);
    } else { content.replaceChildren(node('pre', '', result.content)); }
  } catch (error) { if (modal.open && request === attachmentPreviewRequest) content.replaceChildren(node('p', 'settings-intro', errorMessage(error))); }
}

function scanLabel(item: Attachment): string {
  return ({ SCANNING: 'Scanning', CLEAN: 'No threat detected', SUSPICIOUS: 'Suspicious', UNAVAILABLE: 'Scanner unavailable', QUARANTINED: 'Quarantined' } as Record<string, string>)[item.scanStatus] ?? item.scanStatus;
}

function formatBytes(bytes: number): string { return bytes < 1024 * 1024 ? `${Math.ceil(bytes / 1024)} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`; }

function fillComposeAccounts(): void {
  const select = $<HTMLSelectElement>('[data-compose-account]'), selected = select.value;
  const previous = select.options[select.selectedIndex]?.cloneNode(true) as HTMLOptionElement | undefined;
  select.replaceChildren();
  for (const account of senderAccounts()) { const option = new Option(`${account.displayName} <${account.email}>`, String(account.id)); select.add(option); }
  if (previous && $<HTMLDialogElement>('[data-compose-modal]').open && ![...select.options].some(option => option.value === selected)) select.add(previous);
  if (selected) select.value = selected;
  if (select.selectedIndex < 0 && select.options.length) select.selectedIndex = 0;
  composeAccountPicker?.refresh();
}

async function openCompose(mode: ComposeMode = 'new'): Promise<void> {
  if (composeSending || composeClosing || openingDraft) return;
  if (mode !== 'new' && state.detail && sharedPermissions(state.detail.accountId)?.canSend === false) return;
  if (!senderAccounts().length) {
    alert('Connect a mail account before composing a message.');
    return;
  }
  const modal = $<HTMLDialogElement>('[data-compose-modal]'), form = $<HTMLFormElement>('[data-compose-form]');
  if (modal.open) {
    setComposeMinimized(false);
    (form.elements.namedItem('body') as HTMLTextAreaElement).focus();
    return;
  }
  if (draftSave) await draftSave;
  clearTimeout(draftTimer);
  form.reset(); state.draftId = null; storedDraftAttachments = []; originalDraftHtml = null;
  fillComposeAccounts();
  const source = mode === 'new' ? null : state.detail;
  const draft = composeMessage(mode, source, senderAccounts().find(account => account.id === source?.accountId)?.email);
  composeContext = { inReplyTo: draft.inReplyTo, referencesHeader: draft.referencesHeader, sourceMessageId: source?.id ?? null };
  const accountId = source?.accountId ?? state.accountId;
  if (accountId && senderAccounts().some(a=>a.id===accountId)) (form.elements.namedItem('accountId') as HTMLSelectElement).value = String(accountId);
  composeAccountPicker?.refresh();
  for (const name of ['to', 'cc', 'subject', 'body'] as const) (form.elements.namedItem(name) as HTMLInputElement | HTMLTextAreaElement).value = draft[name];
  $('[data-compose-title]').textContent = draft.title;
  $('[data-draft-status]').textContent = 'Drafts save as you type';
  $('[data-compose-file-summary]').classList.add('hidden');
  $('[data-compose-quick-response]').classList.add('hidden');
  $('[data-toggle-quick-response]').setAttribute('aria-expanded', 'false');
  setRecipientVisibility('cc', Boolean(draft.cc)); setRecipientVisibility('bcc', false);
  modal.classList.remove('is-expanded');
  $('[data-expand-compose]').setAttribute('aria-pressed', 'false');
  $('[data-expand-compose]').setAttribute('aria-label', 'Expand message');
  setComposeMinimized(false);
  modal.show();
  const focus = form.elements.namedItem(mode === 'reply' || mode === 'replyAll' ? 'body' : 'to') as HTMLInputElement | HTMLTextAreaElement;
  focus.focus(); focus.setSelectionRange(0, 0); focus.scrollTop = 0;
  void loadCanned().catch(() => { /* compose still works */ });
}

async function openSavedDraft(id: string): Promise<void> {
  if (openingDraft || composeSending || composeClosing || mutatingDraftIds.has(id)) return;
  const modal = $<HTMLDialogElement>('[data-compose-modal]');
  if (modal.open && state.draftId === id) {
    openingDraft = true;
    try {
      if (composeContext.sourceMessageId) await openMessage(composeContext.sourceMessageId);
      setComposeMinimized(false); $<HTMLTextAreaElement>('[data-compose-form] [name=body]').focus();
    } finally { openingDraft = false; }
    return;
  }
  openingDraft = true;
  try {
    if (modal.open) { await closeCompose(); if (modal.open) return; }
    if (draftSave) await draftSave;
    const draft = await api<DraftDetail>(`/api/mail/outbound/drafts/${id}`);
    if (draft.sourceMessageId) await openMessage(draft.sourceMessageId);
    else clearDetail();
    const form = $<HTMLFormElement>('[data-compose-form]'); form.reset(); clearTimeout(draftTimer);
    state.draftId = draft.id; storedDraftAttachments = draft.attachments;
    originalDraftHtml = draft.bodyHtml ? { html: draft.bodyHtml, text: draft.bodyText } : null;
    composeContext = { inReplyTo: draft.inReplyTo, referencesHeader: draft.referencesHeader, sourceMessageId: draft.sourceMessageId ?? null };
    fillComposeAccounts();
    const select = $<HTMLSelectElement>('[data-compose-account]');
    if (![...select.options].some(option => option.value === String(draft.accountId))) {
      select.add(new Option(`${draft.accountName} <${draft.accountEmail}> (disconnected)`, String(draft.accountId)));
    }
    select.value = String(draft.accountId); composeAccountPicker?.refresh();
    for (const [name, value] of Object.entries({ to: draft.to, cc: draft.cc, bcc: draft.bcc, subject: draft.subject, body: draft.bodyText })) {
      (form.elements.namedItem(name) as HTMLInputElement).value = value ?? '';
    }
    setRecipientVisibility('cc', Boolean(draft.cc)); setRecipientVisibility('bcc', Boolean(draft.bcc));
    $('[data-compose-title]').textContent = 'Edit draft';
    $('[data-draft-status]').textContent = draft.accountActive ? `Draft saved at ${new Date(draft.savedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}` : 'Sender disconnected. Choose a connected account before sending.';
    $('[data-compose-quick-response]').classList.add('hidden'); $('[data-toggle-quick-response]').setAttribute('aria-expanded', 'false');
    updateComposeAttachments();
    modal.classList.remove('is-expanded'); $('[data-expand-compose]').setAttribute('aria-pressed', 'false');
    $('[data-expand-compose]').setAttribute('aria-label', 'Expand message');
    setComposeMinimized(false); modal.show();
    (form.elements.namedItem('body') as HTMLTextAreaElement).focus();
    void loadCanned().catch(() => {});
  } catch (error) { alert(errorMessage(error)); }
  finally { openingDraft = false; }
}

function updateComposeAttachments(): void {
  const local = Array.from($<HTMLInputElement>('[data-compose-files]').files ?? []);
  const summary = $('[data-compose-file-summary]');
  summary.textContent = [...storedDraftAttachments.map(file => `${file.filename} (${formatBytes(file.sizeBytes)}) · ${scanLabel(file)}`),
    ...local.map(file => `${file.name} (${formatBytes(file.size)})`)].join(' · ');
  summary.classList.toggle('hidden', !storedDraftAttachments.length && !local.length);
}

function setRecipientVisibility(name: string, visible: boolean): void {
  $(`[data-recipient-row="${name}"]`).classList.toggle('hidden', !visible);
  $(`[data-toggle-recipient="${name}"]`).setAttribute('aria-expanded', String(visible));
}

function setComposeMinimized(minimized: boolean): void {
  $('[data-compose-modal]').classList.toggle('is-minimized', minimized);
  $('[data-minimize-compose]').setAttribute('aria-expanded', String(!minimized));
  $('[data-minimize-compose]').setAttribute('aria-label', minimized ? 'Restore message' : 'Minimize message');
}

function scheduleDraft(): void {
  clearTimeout(draftTimer);
  if (!composeSending && !composeClosing) draftTimer = window.setTimeout(() => void saveDraft(), 1200);
}

function values(value: FormDataEntryValue | null): string[] { return String(value ?? '').split(',').map(item => item.trim()).filter(Boolean); }

function composePayload(data: FormData) {
  return {
    accountId: Number(data.get('accountId')), to: values(data.get('to')), cc: values(data.get('cc')), bcc: values(data.get('bcc')),
    subject: String(data.get('subject') ?? ''), bodyText: String(data.get('body') ?? ''),
    bodyHtml: originalDraftHtml?.text === String(data.get('body') ?? '') ? originalDraftHtml.html : '',
    ...composeContext
  };
}

function saveDraft(data = new FormData($<HTMLFormElement>('[data-compose-form]'))): Promise<string | null> {
  const payload = composePayload(data);
  const save = (draftSave ?? Promise.resolve(null)).then(() => persistDraft(payload));
  draftSave = save;
  void save.finally(() => { if (draftSave === save) draftSave = null; });
  return save;
}

async function persistDraft(payload: ReturnType<typeof composePayload>): Promise<string | null> {
  if (!$<HTMLDialogElement>('[data-compose-modal]').open) return null;
  try {
    const path = state.draftId ? `/api/mail/outbound/drafts/${state.draftId}` : '/api/mail/outbound/drafts';
    const result = await api<Outbound>(path, { method: state.draftId ? 'PUT' : 'POST', body: JSON.stringify(payload) }); state.draftId = result.id;
    $('[data-draft-status]').textContent = `Draft saved at ${new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`;
    refreshDraftView();
    return state.draftId;
  } catch { $('[data-draft-status]').textContent = 'Draft could not be saved'; return null; }
}

function lockComposeForm(form: HTMLFormElement): () => void {
  composeAccountPicker?.close();
  const controls = Array.from(form.querySelectorAll<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement | HTMLButtonElement>('input, select, textarea, button'));
  const disabled = controls.map(control => control.disabled);
  const previousBusy = form.getAttribute('aria-busy');
  controls.forEach(control => { control.disabled = true; });
  form.setAttribute('aria-busy', 'true');
  return () => {
    controls.forEach((control, index) => { control.disabled = disabled[index]; });
    if (previousBusy === null) form.removeAttribute('aria-busy');
    else form.setAttribute('aria-busy', previousBusy);
  };
}

async function closeCompose(): Promise<void> {
  if (composeSending || composeClosing) return;
  composeClosing = true;
  clearTimeout(draftTimer);
  const form = $<HTMLFormElement>('[data-compose-form]'), data = new FormData(form);
  const hasFiles = Boolean($<HTMLInputElement>('[data-compose-files]').files?.length);
  const unlock = lockComposeForm(form);
  try {
    if (hasFiles && !await confirmAction('Close message?', 'Attached files are kept only in this window and will be removed when you close it. Your message text will be saved as a draft.', 'Close message')) return;
    if (state.draftId || draftSave || ['to', 'cc', 'bcc', 'subject', 'body'].some(name => String(data.get(name) ?? '').trim())) {
      if (!await saveDraft(data)) return;
    }
    $<HTMLDialogElement>('[data-compose-modal]').close();
  } finally { composeClosing = false; unlock(); }
}

async function sendCompose(event: SubmitEvent): Promise<void> {
  event.preventDefault(); const submitter = event.submitter as HTMLButtonElement | null; const modal = $<HTMLDialogElement>('[data-compose-modal]');
  if (composeSending || composeClosing) return;
  if (submitter?.value === 'cancel') { await closeCompose(); return; }
  const form = event.currentTarget as HTMLFormElement, data = new FormData(form); if (!form.reportValidity()) return;
  const payload = composePayload(data), files = Array.from($<HTMLInputElement>('[data-compose-files]').files ?? []);
  composeSending = true;
  const sendButton = form.querySelector<HTMLButtonElement>('.compose-send')!;
  sendButton.classList.add('sending'); sendButton.querySelector('span')!.textContent = 'Sending…';
  clearTimeout(draftTimer);
  const unlock = lockComposeForm(form);
  try {
    const draftId = await saveDraft(data); if (!draftId) throw new ApiError(0, 'The draft could not be saved before sending.');
    for (const file of files) {
      $('[data-draft-status]').textContent = `Scanning ${file.name}…`;
      const upload = new FormData(); upload.append('file', file);
      const scanned = await api<Attachment>(`/api/mail/outbound/drafts/${draftId}/attachments`, { method: 'POST', body: upload });
      if (scanned.scanStatus !== 'CLEAN') throw new ApiError(409, `${file.name} was not cleared for sending: ${scanLabel(scanned)}`);
    }
    const result = await api<Outbound>('/api/mail/outbound/send', { method: 'POST', body: JSON.stringify({ ...payload, draftId, idempotencyKey: crypto.randomUUID() }) }); state.draftId = null; modal.close(); refreshDraftView(); mailExtras.queued(result); }
  catch (error) { alert(errorMessage(error)); } finally { composeSending = false; unlock(); sendButton.classList.remove('sending'); sendButton.querySelector('span')!.textContent = 'Send'; }
}

async function loadCanned(): Promise<void> {
  const request = ++cannedRequest;
  const items = await api<Canned[]>('/api/mail/canned-responses'), select = $<HTMLSelectElement>('[data-canned-select]');
  if (request !== cannedRequest) return;
  select.replaceChildren(new Option('Choose a saved response…', '')); for (const item of items) { const option = new Option(item.title, String(item.id)); option.dataset.body = item.bodyHtml; select.add(option); }
}

function renderManagedAccounts(): void {
  const root = $<HTMLElement>('[data-managed-accounts]'); root.replaceChildren();
  for (const account of state.accounts) {
    const row = node('div', 'managed-account'), copy = node('div', 'managed-account-info');
    if (enteringAccountIds.has(account.id)) row.classList.add('account-entering');
    const providerIcon = account.authProvider === 'GOOGLE' ? gmailAccountIcon() : icon('M4 6.5h16v11H4zM4.5 7l7.5 6 7.5-6');
    providerIcon.classList.add('managed-account-provider-icon');
    const details = node('div');
    details.append(node('strong', '', account.displayName), node('small', '', `${account.email} · ${account.syncStatus ?? 'Pending'}`));
    copy.append(providerIcon, details);
    const remove = node('button', 'button button-ghost', 'Remove'); remove.addEventListener('click', async () => {
      if (!await confirmAction('Remove mail account?', `Remove ${account.displayName} from Dispatch? This does not delete anything from the mail server.`, 'Remove account')) return;
      try {
        await api<void>(`/api/mail/accounts/${account.id}`, { method: 'DELETE' });
        row.classList.add('account-leaving');
        document.querySelector<HTMLElement>(`[data-account="${account.id}"]`)?.classList.add('account-leaving');
        await new Promise(resolve => window.setTimeout(resolve, 260));
        if (state.accountId === account.id) {
          state.accountId = state.trash ? state.accounts.find(candidate => candidate.id !== account.id)?.id ?? null : null;
          state.folderId = null;
          setMailboxTitle(state.trash ? 'Trash' : 'Unified inbox',
            state.trash ? state.accounts.find(candidate => candidate.id === state.accountId)?.email : undefined);
          syncMailUrl(true);
        }
        await loadAccounts(); await loadMessages();
      }
      catch (error) { alert(errorMessage(error)); }
    }); row.append(copy, remove); root.append(row);
  }
}

function updateAccountAvailability(): void {
  const available = readableAccounts();
  applyAccountAvailability(available);
}

function updateEmptyStateCopy(): void {
  const available = readableAccounts();
  updateReadingEmptyCopy(available);
  if (state.extra) {
    $('[data-message-empty-title]').textContent = state.extra === 'contacts' ? 'No contacts found' : 'No sent messages';
    $('[data-message-empty-text]').textContent = state.extra === 'contacts' ? 'Save a contact or try a different search.' : 'Messages sent from Dispatch and their delivery status appear here.';
  } else if (state.drafts) {
    $('[data-message-empty-title]').textContent = state.query ? 'No matching drafts' : 'No saved drafts';
    $('[data-message-empty-text]').textContent = state.query ? 'Try another subject or recipient.' : 'Messages you save while writing will appear here.';
  } else if (state.trash) {
    const filtered = state.filter !== 'all' || Boolean(state.query);
    $('[data-message-empty-title]').textContent = filtered ? state.filter === 'drafts' ? 'No matching drafts' : 'No matching messages' : 'Trash is empty';
    $('[data-message-empty-text]').textContent = filtered ? 'Try another filter or clear your search.' : 'Messages and drafts moved to Trash will appear here.';
  } else {
    $('[data-message-empty-title]').textContent = available ? 'No messages here' : 'Connect your first mail account';
    $('[data-message-empty-text]').textContent = available
      ? 'Try another mailbox or clear the active filters.'
      : 'Add an IMAP and SMTP account to start receiving and sending mail.';
  }
}

function updateReadingEmptyCopy(available: boolean): void {
  if(state.extra) {
    $('[data-reading-empty-title]').textContent = state.extra === 'contacts' ? 'Choose a contact' : 'Choose a sent message';
    $('[data-reading-empty-text]').textContent = state.extra === 'contacts' ? 'Open a contact to view their address or write an email.' : 'Open a message to see its content and delivery status.'; return;
  }
  $('[data-reading-empty-title]').textContent = state.drafts ? 'Choose a draft' : available ? 'No message selected' : 'No mail account connected';
  $('[data-reading-empty-text]').textContent = state.drafts ? 'Open a saved draft to continue writing.' : available
    ? 'Open a message from the inbox to read it here.'
    : 'Connect an account from the profile menu to get started.';
}

export function applyAccountAvailability(available: boolean): void {
  document.querySelectorAll<HTMLButtonElement>('[data-compose]').forEach(button => {
    button.disabled = !senderAccounts().length;
    button.title = senderAccounts().length ? '' : 'Connect a mail account or request sending access before composing.';
  });
  $<HTMLButtonElement>('[data-refresh]').disabled = !available && !state.drafts && !state.trash && !state.extra;
  $<HTMLInputElement>('[data-search]').disabled = !available && !state.drafts && !state.trash && !state.extra;
  $<HTMLElement>('[data-account-empty]').classList.toggle('hidden', state.accounts.length > 0);
  updateEmptyStateCopy();
  updateReadingEmptyCopy(available);
}

export async function initMail(): Promise<void> {
  composeAccountPicker = initComposeAccountPicker($('[data-compose-account-picker]'));
  accountOrder = initAccountOrder($('[data-account-list]'), $('[data-account-order-status]'), {
    save: async accountIds => {
      await api<void>('/api/mail/accounts/order', { method: 'PUT', body: JSON.stringify({ accountIds }) });
      accountOrderRevision++;
      const positions = new Map(accountIds.map((id, index) => [id, index]));
      state.accounts.sort((a, b) => (positions.get(a.id) ?? Infinity) - (positions.get(b.id) ?? Infinity));
    },
    onError: error => alert(errorMessage(error)),
    onSettled: () => { if (pendingAccountRender) renderAccounts(); }
  });
  document.querySelectorAll<HTMLDialogElement>('dialog.modal:not([data-canned-editor-modal]):not([data-contact-editor]):not([data-sharing-editor]):not([data-sharing-accounts-modal])').forEach(dialog => {
    dialog.addEventListener('click', event => { if (event.target === dialog) dialog.close('cancel'); });
  });
  $('[data-unified]').addEventListener('click', () => selectMailbox(null, null, 'Unified inbox'));
  $('[data-trash]').addEventListener('click', selectTrash);
  $('[data-drafts]').addEventListener('click', selectDrafts);
  $('[data-empty-trash]').addEventListener('click', () => void emptyTrash());
  $('[data-undo-trash]').addEventListener('click', () => void undoTrash());
  $('[data-selection-delete]').addEventListener('click', () => void applySelectionDelete());
  $('[data-selection-unread]').addEventListener('click', () => void markSelectionUnread());
  $('[data-selection-cancel]').addEventListener('click', cancelSelection);
  document.querySelectorAll('[data-compose]').forEach(button => button.addEventListener('click', () => void openCompose()));
  document.querySelectorAll('[data-close-compose]').forEach(button => button.addEventListener('click', () => void closeCompose()));
  $('[data-minimize-compose]').addEventListener('click', () => setComposeMinimized(!$('[data-compose-modal]').classList.contains('is-minimized')));
  $('[data-expand-compose]').addEventListener('click', event => {
    setComposeMinimized(false);
    const expanded = $('[data-compose-modal]').classList.toggle('is-expanded');
    const button = event.currentTarget as HTMLButtonElement;
    button.setAttribute('aria-pressed', String(expanded)); button.setAttribute('aria-label', expanded ? 'Restore message size' : 'Expand message');
    button.title = expanded ? 'Restore size' : 'Expand';
  });
  document.querySelectorAll<HTMLButtonElement>('[data-toggle-recipient]').forEach(button => button.addEventListener('click', () => {
    const name = button.dataset.toggleRecipient!;
    const visible = button.getAttribute('aria-expanded') !== 'true';
    const input = $<HTMLInputElement>(`[data-recipient-row="${name}"] input`);
    if (!visible && input.value.trim()) { input.focus(); return; }
    setRecipientVisibility(name, visible); if (visible) input.focus();
  }));
  $('[data-attach-compose]').addEventListener('click', () => $<HTMLInputElement>('[data-compose-files]').click());
  $<HTMLInputElement>('[data-compose-files]').addEventListener('change', updateComposeAttachments);
  $('[data-toggle-quick-response]').addEventListener('click', event => {
    const visible = $('[data-compose-quick-response]').classList.toggle('hidden') === false;
    (event.currentTarget as HTMLButtonElement).setAttribute('aria-expanded', String(visible));
    if (visible) $<HTMLSelectElement>('[data-canned-select]').focus();
  });
  document.querySelectorAll('[data-reply]').forEach(button => button.addEventListener('click', () => void openCompose('reply')));
  document.querySelectorAll('[data-forward]').forEach(button => button.addEventListener('click', () => void openCompose('forward')));
  $('[data-reply-all]').addEventListener('click', () => void openCompose('replyAll'));
  $<HTMLFormElement>('[data-compose-form]').addEventListener('submit', event => void sendCompose(event));
  $<HTMLFormElement>('[data-compose-form]').addEventListener('input', scheduleDraft);
  $<HTMLFormElement>('[data-compose-form]').addEventListener('keydown', event => {
    if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') { event.preventDefault(); $<HTMLFormElement>('[data-compose-form]').requestSubmit($<HTMLButtonElement>('[data-compose-form] button[type=submit]')); }
  });
  $<HTMLSelectElement>('[data-canned-select]').addEventListener('change', event => { const option = (event.target as HTMLSelectElement).selectedOptions[0]; if (option?.dataset.body) { ($<HTMLFormElement>('[data-compose-form]').elements.namedItem('body') as HTMLTextAreaElement).value += option.dataset.body; scheduleDraft(); } });
  $<HTMLInputElement>('[data-search]').addEventListener('input', event => { clearTimeout(searchTimer); searchTimer = window.setTimeout(() => { state.query = (event.target as HTMLInputElement).value.trim(); state.page = 0; syncMailUrl(true);void loadMessages(); }, 300); });
  document.addEventListener('keydown', event => { if (event.key === '/' && !['INPUT','TEXTAREA'].includes((event.target as Element).tagName)) { event.preventDefault(); $<HTMLInputElement>('[data-search]').focus(); } });
  document.querySelectorAll<HTMLElement>('[data-filter]').forEach(button => button.addEventListener('click', () => { hideMessageToast(); document.querySelectorAll('[data-filter]').forEach(item => item.classList.remove('active')); button.classList.add('active'); state.filter = button.dataset.filter ?? 'all'; state.page = 0; syncMailUrl();void loadMessages(); }));
  $('[data-prev]').addEventListener('click', () => { state.page--; syncMailUrl();void loadMessages(); }); $('[data-next]').addEventListener('click', () => { state.page++; syncMailUrl();void loadMessages(); });
  $('[data-toggle-star]').addEventListener('click', async event => { if (!state.detail) return; const summary = state.messages.find(message => message.id === state.detail?.id); if (summary) await toggleStarred(summary, event.currentTarget as HTMLButtonElement); });
  $('[data-mark-unread]').addEventListener('click', async () => {
    closeDetailMenu(); if (!state.detail) return;
    try {
      await api<void>(`/api/mail/messages/${state.detail.id}/read`, { method: 'PATCH', body: JSON.stringify({ value: false }) });
      state.detail.read = false;
      const summary = state.messages.find(message => message.id === state.detail?.id);
      if (summary) summary.read = false;
      renderMessages(); void refreshInboxUnreadCount();
    }
    catch (error) { alert(errorMessage(error)); }
  });
  $('[data-load-images]').addEventListener('click', () => {
    if (!state.detail || detailExternalImages) return;
    detailExternalImages = true; closeDetailMenu(); refreshDetailFrame();
  });
  $('[data-render-mode]').addEventListener('click', () => {
    if (!state.detail) return;
    detailOriginalFormatting = !detailOriginalFormatting; refreshDetailFrame();
  });
  const detailMenu = $<HTMLButtonElement>('[data-detail-menu]');
  const detailPopover = $<HTMLElement>('[data-detail-popover]');
  const detailMenuWrap = detailMenu.closest<HTMLElement>('.detail-menu-wrap');
  const externalLinkConfirm = $<HTMLElement>('[data-link-confirm]');
  detailMenu.addEventListener('click', () => {
    const open = detailPopover.classList.toggle('hidden') === false;
    detailMenu.setAttribute('aria-expanded', String(open));
  });
  document.addEventListener('pointerdown', event => {
    if (!detailMenuWrap?.contains(event.target as Node)) closeDetailMenu();
    if (!externalLinkConfirm.classList.contains('hidden') && !externalLinkConfirm.contains(event.target as Node)) {
      hideExternalLinkConfirm();
    }
  }, true);
  window.addEventListener('message', event => {
    const frame = $<HTMLIFrameElement>('[data-message-frame]');
    if (event.source !== frame.contentWindow || !event.data || typeof event.data !== 'object') return;
    const data = event.data as { type?: string; url?: string; rect?: { left?: number; top?: number; bottom?: number } };
    if (data.type === 'dispatch-frame-click') { closeDetailMenu(); hideExternalLinkConfirm(); closeAccountMenu(); composeAccountPicker?.close(); mailExtras.closePicker(); }
    if (data.type === 'dispatch-link-confirm' && typeof data.url === 'string') {
      closeDetailMenu(); showExternalLinkConfirm(data.url, data.rect);
    }
  });
  $('[data-link-cancel]').addEventListener('click', hideExternalLinkConfirm);
  $('[data-link-open]').addEventListener('click', () => {
    if (!pendingExternalUrl) return;
    const target = pendingExternalUrl; hideExternalLinkConfirm();
    const opened = window.open(target, '_blank', 'noopener,noreferrer');
    if (opened) opened.opener = null;
  });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape') { closeDetailMenu(); hideExternalLinkConfirm(); }
  });
  $('[data-refresh]').addEventListener('click', async event => {
    const refresh = event.currentTarget as HTMLButtonElement;
    if (refresh.classList.contains('syncing')) return;
    if (sharedPermissions(state.accountId)) { await mailSharing?.refresh(); await loadMessages(); return; }
    if (state.extra || state.drafts) { await loadMessages(); await refreshDraftCount(); return; }
    const ids = state.accountId ? [state.accountId] : state.accounts.map(a => a.id);
    refresh.disabled = true; refresh.classList.add('syncing');
    try {
      await Promise.all(ids.map(id => api<void>(`/api/mail/accounts/${id}/sync`, { method: 'POST' })));
      await loadAccounts(false); await loadMessages(); showSyncToast();
    } catch (error) { alert(errorMessage(error)); }
    finally { refresh.classList.remove('syncing'); refresh.disabled = !readableAccounts(); }
  });
  const mobileNavToggle = $<HTMLButtonElement>('[data-open-nav]');
  mobileNavToggle.addEventListener('click', () => {
    const open = $<HTMLElement>('[data-account-pane]').classList.toggle('open');
    mobileNavToggle.setAttribute('aria-expanded', String(open));
  });
  $('[data-close-nav]').addEventListener('click', () => closeMobileNav(true));
  $<HTMLElement>('[data-account-pane]').addEventListener('click', event => {
    if ((event.target as Element).closest('[data-compose], [data-open-quick-responses], [data-open-mail-accounts]')) closeMobileNav();
  });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && $<HTMLElement>('[data-account-pane]').classList.contains('open')) closeMobileNav(true);
  });
  $('[data-back-list]').addEventListener('click', () => $<HTMLElement>('[data-reading-pane]').classList.remove('open'));
  mailExtras = initMailExtras({filter:()=>state.filter,view:()=>state.extra, navigate:selectExtra, clearDetail, alert,
    resetSearch:()=>{state.query='';state.page=0;$<HTMLInputElement>('[data-search]').value='';},
    pagination:(page,pages,count)=>{state.page=page;state.pages=pages;state.messageTotal=count;renderPagination();},
    compose:async(email)=>{
      const modal=$<HTMLDialogElement>('[data-compose-modal]'),wasOpen=modal.open;await openCompose();if(!modal.open)return;
      const input=$<HTMLInputElement>('[data-compose-form] [name=to]');
      const recipients=wasOpen?input.value.split(',').map(value=>value.trim()).filter(Boolean):[];
      if(!recipients.some(value=>(value.match(/<([^>]+)>/)?.[1]??value).toLowerCase()===email.toLowerCase()))recipients.push(email);
      input.value=recipients.join(', ');input.dispatchEvent(new Event('input',{bubbles:true}));mailExtras.closePicker();$<HTMLInputElement>('[data-compose-form] [name=subject]').focus();
    },
    undo:(label,action)=>{hideMessageToast();extraUndo=action;showMessageToast(label,'M3 6h18M8 6V4h8v2m-9 0 1 14h8l1-14','trash',true,6000);}
  });
  $('[data-contacts]').addEventListener('click',()=>selectExtra('contacts'));
  $('[data-sent]').addEventListener('click',()=>selectExtra('sent'));
  mailSharing = initMailSharing({closeMenu:closeAccountMenu,selected:()=>state.accountId,select:(id,email)=>selectMailbox(id,null,email),confirm:confirmAction,changed:()=>{
    if(state.accountId && !state.accounts.some(a=>a.id===state.accountId) && !mailSharing?.accounts().some(a=>a.id===state.accountId)) selectMailbox(null,null,'Unified inbox');
    renderAccounts();fillComposeAccounts();updateAccountAvailability();updatePanePrimaryAction();
    if(state.detail){renderMessages();renderDetail();}
  }});
  window.setInterval(()=>{if(!document.hidden)void mailSharing?.refresh().catch(()=>{});},30000);
  initAccountSettings(); initAccountMenu(); initSecurityActivity(closeAccountMenu);
  const googleParams = new URLSearchParams(window.location.search);
  const googleResult = googleParams.get('google');
  if (googleResult) {
    window.history.replaceState(null, '', '/mail');
    const accountId = Number(googleParams.get('account'));
    if (googleResult === 'connected' && Number.isSafeInteger(accountId) && accountId > 0)
      enteringAccountIds.add(accountId);
  }
  window.addEventListener('popstate',()=>{clearTimeout(searchTimer);void restoreMailLocation().catch(error=>alert(errorMessage(error)));});
  void refreshDraftCount();
  try {
    await loadAccounts(); routeReady = true; await restoreMailLocation();
    if (googleResult === 'connected')
      showMessageToast('Gmail account added', 'M5 12l4 4L19 6', 'success', false, 4500);
    else if (googleResult) alert('Google connection could not be completed. Please try again.');
  } catch (error) { alert(errorMessage(error)); }
}

function initAccountSettings(): void {
  const mailAccounts = $<HTMLDialogElement>('[data-mail-accounts-modal]');
  const accountCreate = $<HTMLDialogElement>('[data-account-create-modal]');
  initQuickResponses({ closeMenu: closeAccountMenu, confirmAction, onChanged: loadCanned });
  const profile = $<HTMLDialogElement>('[data-profile-modal]');
  const closeMenu = closeAccountMenu;
  document.querySelectorAll('[data-open-mail-accounts]').forEach(button => button.addEventListener('click', () => {
    closeMenu(); renderManagedAccounts(); mailAccounts.showModal();
  }));
  $('[data-open-profile]').addEventListener('click', () => {
    closeMenu(); profile.showModal();
  });
  $('[data-open-account-create]').addEventListener('click', () => {
    mailAccounts.close(); accountCreate.showModal();
    void api<{available:boolean}>('/api/mail/accounts/google/config').then(({available}) => {
      const button = $<HTMLButtonElement>('[data-connect-google]'); button.disabled = !available;
      $('[data-google-connect-note]').textContent = available
        ? 'Connect Gmail securely with your Google account.'
        : 'Google connection needs an OAuth client configured by the administrator.';
    }).catch(error => alert(errorMessage(error)));
  });
  $<HTMLButtonElement>('[data-connect-google]').addEventListener('click', async event => {
    const button = event.currentTarget as HTMLButtonElement; button.disabled = true;
    try {
      const result = await api<{url:string}>('/api/mail/accounts/google/start', { method: 'POST' });
      window.location.assign(result.url);
    } catch (error) { button.disabled = false; alert(errorMessage(error)); }
  });
  document.querySelectorAll('[data-close-account-create]').forEach(button => button.addEventListener('click', () => { accountCreate.close(); mailAccounts.showModal(); }));
  $('[data-close-mail-accounts]').addEventListener('click', () => mailAccounts.close());
  document.querySelectorAll('[data-close-profile]').forEach(button => button.addEventListener('click', () => profile.close()));
  $<HTMLFormElement>('[data-profile-form]').addEventListener('submit', async event => {
    event.preventDefault();
    const form = event.currentTarget as HTMLFormElement;
    const submit = form.querySelector<HTMLButtonElement>('button[type=submit]')!;
    const displayName = String(new FormData(form).get('displayName') ?? '').trim();
    submit.disabled = true;
    try {
      const updated = await api<Profile>('/api/auth/profile', { method: 'PATCH', body: JSON.stringify({ displayName }) });
      $('[data-user-name]').textContent = updated.displayName;
      $('[data-user-initials]').textContent = profileInitials(updated.displayName);
      $<HTMLInputElement>('[data-profile-form] [name=displayName]').value = updated.displayName;
      profile.close();
      alert('Profile updated.');
    } catch (error) { alert(errorMessage(error)); }
    finally { submit.disabled = false; }
  });
  $<HTMLFormElement>('[data-account-form]').addEventListener('submit', async event => { event.preventDefault(); const form = event.currentTarget as HTMLFormElement, data = Object.fromEntries(new FormData(form)) as Record<string, string>; const button = form.querySelector<HTMLButtonElement>('button[type=submit]')!; button.disabled = true; try { await api('/api/mail/accounts', { method: 'POST', body: JSON.stringify({ ...data, imapPort: Number(data.imapPort), smtpPort: Number(data.smtpPort) }) }); form.reset(); await loadAccounts(); await loadMessages(); renderManagedAccounts(); accountCreate.close(); mailAccounts.showModal(); } catch (error) { alert(errorMessage(error)); } finally { button.disabled = false; } });
}

function gmailAccountIcon(): SVGSVGElement {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.classList.add('account-gmail-icon'); svg.setAttribute('viewBox', '0 0 24 24'); svg.setAttribute('aria-hidden', 'true');
  for (const [d, color] of [
    ['M3.5 18V6.5', '#4285f4'], ['M3.5 6.5 12 13', '#ea4335'],
    ['M12 13 20.5 6.5', '#ea4335'], ['M20.5 6.5V10', '#fbbc04'], ['M20.5 10v8', '#34a853']
  ]) {
    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute('d', d); path.setAttribute('stroke', color); path.setAttribute('stroke-width', '3');
    path.setAttribute('stroke-linecap', 'round'); path.setAttribute('stroke-linejoin', 'round'); path.setAttribute('fill', 'none');
    svg.append(path);
  }
  return svg;
}

function closeAccountMenu(): void {
  const menu = $<HTMLElement>('[data-account-popover]');
  menu.hidePopover?.(); menu.classList.add('hidden');
  $('[data-account-menu]').setAttribute('aria-expanded', 'false');
}

function initAccountMenu(): void {
  const popover = $<HTMLElement>('[data-account-popover]'), trigger = $<HTMLButtonElement>('[data-account-menu]');
  trigger.addEventListener('click', event => {
    event.stopPropagation();
    const open = popover.classList.toggle('hidden') === false;
    if (open) popover.showPopover?.(); else popover.hidePopover?.();
    trigger.setAttribute('aria-expanded', String(open));
  });
  const dismissOutside = (event: Event) => {
    if (!popover.contains(event.target as Node) && !trigger.contains(event.target as Node)) closeAccountMenu();
  };
  document.addEventListener('pointerdown', dismissOutside, true);
  document.addEventListener('click', dismissOutside, true);
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape') closeAccountMenu();
  });
  const logout = async (allDevices: boolean) => {
    const buttons = document.querySelectorAll<HTMLButtonElement>('[data-logout], [data-logout-all]');
    buttons.forEach(button => { button.disabled = true; });
    try { await api(`/api/auth/logout?allDevices=${allDevices}`, { method: 'POST' }); window.location.assign('/login'); }
    catch (error) { alert(errorMessage(error)); buttons.forEach(button => { button.disabled = false; }); }
  };
  $('[data-logout]').addEventListener('click', () => void logout(false));
  document.querySelectorAll('[data-logout-all]').forEach(button => button.addEventListener('click', () => void logout(true)));
}
