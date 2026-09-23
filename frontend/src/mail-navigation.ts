export type MailLocation = {
  view: 'inbox' | 'trash' | 'drafts' | 'contacts' | 'sent';
  accountId: number | null; folderId: number | null; filter: string; query: string; page: number; messageId: number | null;
};
const id = (value: string | null) => value && /^\d+$/.test(value) && Number.isSafeInteger(Number(value)) && Number(value)>0 ? Number(value) : null;
export function readMailLocation(url: URL): MailLocation {
  const match=url.pathname.match(/^\/mail(?:\/(trash|drafts|contacts|sent|accounts\/\d+))?\/?$/);
  const segment=match?.[1]??'';
  const view=(['trash','drafts','contacts','sent'].includes(segment)?segment:'inbox') as MailLocation['view'];
  const allowed=view==='trash'?['all','unread','starred','drafts']:view==='contacts'?['all','starred']:view==='inbox'?['all','unread','starred']:['all'];
  const filter=url.searchParams.get('filter')??'all';
  return {view,accountId:segment.startsWith('accounts/')?id(segment.slice(9)):view==='trash'?id(url.searchParams.get('account')):null,folderId:view==='inbox'?id(url.searchParams.get('folder')):null,
    filter:allowed.includes(filter)?filter:'all',query:(url.searchParams.get('q')??'').slice(0,200),page:Math.min(100000,Math.max(0,(id(url.searchParams.get('page'))??1)-1)),
    messageId:view==='inbox'||view==='trash'?id(url.searchParams.get('message')):null};
}
export function mailLocationUrl(route: MailLocation): string {
  const path=route.view==='inbox'?(route.accountId?`/mail/accounts/${route.accountId}`:'/mail'):`/mail/${route.view}`;
  const params=new URLSearchParams();
  if(route.view==='trash'&&route.accountId)params.set('account',String(route.accountId));
  if(route.filter!=='all')params.set('filter',route.filter);
  if(route.query)params.set('q',route.query);
  if(route.page>0)params.set('page',String(route.page+1));
  if(route.folderId)params.set('folder',String(route.folderId));
  if(route.messageId)params.set('message',String(route.messageId));
  return path+(params.size?`?${params}`:'');
}
