import { api } from './api';

export type ExtraView = 'contacts' | 'sent' | null;
type Contact = { id: number; displayName: string; email: string; starred: boolean };
type Sent = { id: string; accountName: string; from: string; to: string; cc: string; bcc: string; subject: string; bodyText: string; status: string; failureReason: string | null; createdAt: string; sentAt: string | null };
type Page<T> = { content: T[]; totalPages: number; totalElements: number; page: number };
type Options = {
  filter: () => string; view: () => ExtraView; navigate: (view: ExtraView) => void;
  pagination: (page: number, pages: number, count: number) => void;
  resetSearch: () => void;
  clearDetail: () => void; compose: (email: string) => Promise<void>;
  alert: (text: string) => void; undo: (text: string, action: () => Promise<void>) => void;
};
const $ = <T extends Element>(selector: string) => document.querySelector<T>(selector)!;
const el = <K extends keyof HTMLElementTagNameMap>(tag: K, className = '', value = '') => {
  const element = document.createElement(tag); element.className = className; element.textContent = value; return element;
};
const icon = (path: string) => {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg'); svg.setAttribute('viewBox','0 0 24 24'); svg.setAttribute('aria-hidden','true');
  const line = document.createElementNS(svg.namespaceURI,'path'); line.setAttribute('d',path); svg.append(line); return svg;
};
const errorText = (error: unknown) => error instanceof Error ? error.message : 'The operation could not be completed.';
const statusLabel = (status: string) => ({QUEUED:'Queued',SENDING:'Sending',SENT:'Sent',FAILED:'Failed',UNCERTAIN:'Check delivery'}[status] ?? status);
const badge = (status: string) => el('span', `delivery-badge status-${status.toLowerCase()}`, statusLabel(status));
const initials = (value: string) => {
  const name = value.replace(/<.*?>/g, '').replace(/[\"']/g, '').trim();
  const parts = name.split(/\s+/).filter(Boolean);
  return (parts.length > 1 ? parts[0][0] + parts.at(-1)![0] : name.slice(0, 2)).toUpperCase() || '?';
};
const date = (value: string | null) => value ? new Intl.DateTimeFormat('en',{dateStyle:'medium',timeStyle:'short'}).format(new Date(value)) : '';

export function initMailExtras(options: Options) {
  let contacts: Contact[] = [], query = '', page = 0, revision = 0, contactRevision = 0, incoming: number | null = null;
  let selected: Contact | Sent | null = null, editing: number | null = null, saving = false;
  let poll = 0, feedbackTimer = 0, feedbackRevision = 0;
  let sentSnapshot = '', pickerRevision = 0;
  const pendingDeletes = new Set<number>();
  const form = $<HTMLFormElement>('[data-contact-form]'), modal = $<HTMLDialogElement>('[data-contact-editor]');
  const to = $<HTMLInputElement>('[data-compose-form] [name=to]'), picker = $<HTMLElement>('[data-contact-picker]');
  const list = $<HTMLElement>('[data-message-list]');

  async function fetchContacts(): Promise<void> {
    const current = ++contactRevision;
    const result = await api<Contact[]>('/api/mail/contacts');
    if (current === contactRevision) contacts = result;
  }

  function showDetail(): HTMLElement {
    options.clearDetail();
    $('[data-reading-empty]').classList.add('hidden');
    const detail = $<HTMLElement>('[data-extra-detail]'); detail.classList.remove('hidden'); detail.replaceChildren();
    $('[data-reading-pane]').classList.add('open');
    const back = el('button','button button-ghost extra-back','Back to list'); back.type='button';
    back.addEventListener('click',()=> $('[data-reading-pane]').classList.remove('open')); detail.append(back);
    return detail;
  }

  function contactDetail(item: Contact): void {
    selected = item; const detail = showDetail();
    const avatar = el('div','contact-detail-avatar',initials(item.displayName));
    detail.append(avatar,el('h2','',item.displayName),el('p','contact-detail-email',item.email));
    const actions=el('div','extra-detail-actions');
    const compose=el('button','button button-primary','Write email'); compose.type='button'; compose.addEventListener('click',()=>void options.compose(item.email));
    const edit=el('button','button button-ghost','Edit contact'); edit.type='button'; edit.addEventListener('click',()=>openEditor(item));
    actions.append(compose,edit); detail.append(actions);
    document.querySelectorAll<HTMLElement>('[data-contact-id]').forEach(row=>row.classList.toggle('active',row.dataset.contactId===String(item.id)));
  }

  function renderContacts(): void {
    if (options.view() !== 'contacts') return;
    const matches=contacts.filter(item=>(options.filter()!=='starred'||item.starred)&&`${item.displayName} ${item.email}`.toLowerCase().includes(query.toLowerCase()));
    const pages=Math.ceil(matches.length/30); page=Math.min(page,Math.max(0,pages-1));
    list.replaceChildren(); options.pagination(page,pages,matches.length);
    $('[data-message-empty]').classList.toggle('hidden',matches.length>0);
    for (const item of matches.slice(page*30,page*30+30)) {
      const row=el('article',`message-item contact-row${selected?.id===item.id?' active':''}${incoming===item.id?' restoring':''}`); row.dataset.contactId=String(item.id);
      const open=el('button','message-open'); open.type='button'; open.setAttribute('aria-label',`Open contact ${item.displayName}`);
      const avatar=el('span','message-avatar',initials(item.displayName));
      const copy=el('span','extra-row-copy'); copy.append(el('strong','',item.displayName),el('span','',item.email)); open.append(avatar,copy);
      open.addEventListener('click',()=>contactDetail(item));
      const remove=el('button','message-action-button message-delete-action'); remove.type='button'; remove.title='Delete contact'; remove.setAttribute('aria-label',`Delete contact ${item.displayName}`);
      remove.append(icon('M3 6h18M8 6V4h8v2m-9 0 1 14h8l1-14M10 10v6m4-6v6'));
      remove.addEventListener('click',()=>void removeContact(item,row,remove)); const star=el('button',`message-action-button contact-star${item.starred?' active':''}`); star.type='button';
      star.title=item.starred?'Remove star':'Star contact'; star.setAttribute('aria-label',`${star.title} ${item.displayName}`); star.setAttribute('aria-pressed',String(item.starred));
      star.append(icon('m12 3 2.8 5.7 6.2.9-4.5 4.4 1.1 6.2-5.6-3-5.6 3 1.1-6.2L3 9.6l6.2-.9L12 3Z'));
      star.addEventListener('click',async()=>{
        star.disabled=true;
        try {
          const saved=await api<Contact>(`/api/mail/contacts/${item.id}/star`,{method:'PUT',body:JSON.stringify({starred:!item.starred})});
          ++contactRevision; contacts=contacts.map(contact=>contact.id===saved.id?saved:contact);
          if(options.view()==='contacts'){renderContacts();if(selected?.id===saved.id)selected=saved;}
        }catch(error){options.alert(errorText(error));}finally{star.disabled=false;}
      });
      row.append(open,star,remove); list.append(row);
    }
    incoming=null;
  }

  async function removeContact(item: Contact,row: HTMLElement,button: HTMLButtonElement): Promise<void> {
    if (pendingDeletes.has(item.id)) return;
    pendingDeletes.add(item.id); button.disabled=true; row.classList.add('removing');
    const current=revision;
    try {
      await Promise.all([api(`/api/mail/contacts/${item.id}`,{method:'DELETE'}),new Promise(resolve=>window.setTimeout(resolve,190))]);
      ++contactRevision; contacts=contacts.filter(contact=>contact.id!==item.id);
      if (selected?.id===item.id) {selected=null; options.clearDetail();}
      if (options.view()==='contacts' && current===revision) {
        renderContacts();
        options.undo('Contact deleted',async()=>{
          await api(`/api/mail/contacts/${item.id}/restore`,{method:'POST'});
          await fetchContacts(); incoming=item.id; renderContacts();
        });
      }
    } catch(error) { row.classList.remove('removing'); options.alert(errorText(error)); }
    finally {pendingDeletes.delete(item.id);button.disabled=false;}
  }

  function sentDetail(item: Sent): void {
    selected=item; const detail=showDetail();
    const heading=el('div','sent-detail-heading');heading.append(el('h2','',item.subject||'(No subject)'),badge(item.status)); detail.append(heading);
    for (const [label,value] of [['From',`${item.accountName} <${item.from}>`],['To',item.to],['Cc',item.cc],['Bcc',item.bcc],['Created',date(item.createdAt)],['Sent',date(item.sentAt)]]) {
      if (!value) continue; const line=el('p','sent-address');line.append(el('strong','',`${label}: `),document.createTextNode(value));detail.append(line);
    }
    const explanation=item.failureReason || ({QUEUED:'Waiting for the sending service.',SENDING:'Submitting this message to the SMTP server.',SENT:'The SMTP server accepted this message.'}[item.status]??'');
    detail.append(el('p',`sent-status-note status-${item.status.toLowerCase()}`,explanation),el('pre','sent-body',item.bodyText||'This message has no body text.'));
    document.querySelectorAll<HTMLElement>('[data-sent-id]').forEach(row=>row.classList.toggle('active',row.dataset.sentId===item.id));
  }

  async function renderSent(current: number, quiet: boolean): Promise<void> {
    const result=await api<Page<Sent>>(`/api/mail/outbound/sent?${new URLSearchParams({page:String(page),query})}`);
    if (current!==revision || options.view()!=='sent') return;
    const snapshot=JSON.stringify(result);if(quiet&&snapshot===sentSnapshot)return;sentSnapshot=snapshot;
    const scroll=list.scrollTop;
    list.replaceChildren();options.pagination(result.page,result.totalPages,result.totalElements);
    $('[data-message-empty]').classList.toggle('hidden',result.content.length>0);
    for (const item of result.content) {
      const row=el('article',`message-item sent-row${selected?.id===item.id?' active':''}`);row.dataset.sentId=item.id;
      const open=el('button','message-open');open.type='button';open.setAttribute('aria-label',`Open sent message ${item.subject||'(No subject)'}`);
      const copy=el('span','extra-row-copy');copy.append(el('strong','',item.subject||'(No subject)'),el('span','',`To: ${item.to}`));
      const meta=el('span','sent-row-meta');meta.append(el('time','',new Intl.DateTimeFormat('en',{month:'short',day:'numeric',hour:'2-digit',minute:'2-digit'}).format(new Date(item.createdAt))),badge(item.status));
      open.append(el('span','message-avatar',initials(item.accountName||item.from)),copy,meta);open.addEventListener('click',()=>sentDetail(item));row.append(open);list.append(row);
    }
    if (quiet && selected && typeof selected.id==='string') {
      const updated=result.content.find(item=>item.id===selected!.id); if(updated&&JSON.stringify(updated)!==JSON.stringify(selected))sentDetail(updated);
    }
    if(quiet)list.scrollTop=scroll;
  }

  async function load(nextQuery: string,nextPage: number,quiet=false): Promise<void> {
    query=nextQuery;page=nextPage;const current=++revision;window.clearTimeout(poll);
    if(!quiet)list.replaceChildren(el('p','quick-response-loading','Loading…'));
    try {
      if(options.view()==='contacts'){await fetchContacts();if(current===revision)renderContacts();}
      else if(options.view()==='sent')await renderSent(current,quiet);
    }catch(error){if(current===revision){list.replaceChildren(el('p','quick-response-loading',errorText(error)));}}
    finally {if(current===revision && options.view()==='sent')poll=window.setTimeout(()=>void load(query,page,true),5000);}
  }

  function openEditor(item?: Contact,prefill=''):void {
    closePicker();editing=item?.id??null;form.reset();
    (form.elements.namedItem('displayName') as HTMLInputElement).value=item?.displayName??'';
    (form.elements.namedItem('email') as HTMLInputElement).value=item?.email??prefill;
    $('[data-contact-title]').textContent=item?'Edit contact':'Add contact';$('[data-contact-error]').classList.add('hidden');modal.showModal();
  }

  form.addEventListener('submit',async event=>{
    event.preventDefault();if(saving||!form.reportValidity())return;saving=true;
    const data=Object.fromEntries(new FormData(form));
    const controls=[...form.querySelectorAll<HTMLInputElement|HTMLButtonElement>('input,button')];controls.forEach(control=>control.disabled=true);
    const submit=form.querySelector<HTMLButtonElement>('button[type=submit]')!;submit.disabled=true;submit.textContent='Saving…';
    try {
      const saved=await api<Contact>(`/api/mail/contacts${editing===null?'':`/${editing}`}`,{method:editing===null?'POST':'PUT',body:JSON.stringify(data)});
      ++contactRevision;contacts=[...contacts.filter(item=>item.id!==saved.id),saved].sort((a,b)=>a.displayName.localeCompare(b.displayName));
      modal.close();incoming=saved.id;
      if(options.view()==='contacts'){query='';page=0;options.resetSearch();renderContacts();contactDetail(saved);}
    }catch(error){$('[data-contact-error]').textContent=errorText(error);$('[data-contact-error]').classList.remove('hidden');}
    finally{saving=false;controls.forEach(control=>control.disabled=false);submit.textContent='Save contact';}
  });
  document.querySelectorAll('[data-close-contact]').forEach(button=>button.addEventListener('click',()=>{if(!saving)modal.close();}));
  modal.addEventListener('cancel',event=>{if(saving)event.preventDefault();});
  modal.addEventListener('click',event=>{if(event.target===modal&&!saving)modal.close();});
  $('[data-add-contact]').addEventListener('click',()=>openEditor());

  function closePicker():void {++pickerRevision;picker.classList.add('hidden');to.setAttribute('aria-expanded','false');$('[data-pick-contact]').setAttribute('aria-expanded','false');}
  function pickerRows(explicit=false):void {
    const last=to.value.split(',').at(-1)?.trim()??'';
    const address=last.match(/<([^<>]+)>/)?.[1]??last;
    const matches=contacts.filter(item=>`${item.displayName} ${item.email}`.toLowerCase().includes(last.toLowerCase())).slice(0,8);
    const canSave=/^[^\s@<>]+@[^\s@<>]+\.[^\s@<>]+$/.test(address)&&!contacts.some(item=>item.email.toLowerCase()===address.toLowerCase());
    if(!explicit&&(!last||(!matches.length&&!canSave))){closePicker();return;}
    picker.replaceChildren(el('strong','contact-picker-heading','Saved contacts'));
    for(const contact of matches){
      const button=el('button','contact-picker-item');button.type='button';button.append(el('strong','',contact.displayName),el('span','',contact.email));
      button.addEventListener('click',()=>{
        const recipients=to.value.split(',');recipients.pop();
        if(!recipients.some(value=>value.toLowerCase().includes(contact.email.toLowerCase())))recipients.push(contact.email);
        to.value=recipients.map(value=>value.trim()).filter(Boolean).join(', ')+', ';to.dispatchEvent(new Event('input',{bubbles:true}));closePicker();to.focus();closePicker();
      });picker.append(button);
    }
    if(!matches.length)picker.append(el('p','contact-picker-empty',contacts.length?'No matching contacts.':'No saved contacts yet.'));
    if(canSave){
      const add=el('button','contact-picker-add',`Save ${address} as a contact`);add.type='button';add.addEventListener('click',()=>openEditor(undefined,address));picker.append(add);
    }
    picker.classList.remove('hidden');to.setAttribute('aria-expanded','true');$('[data-pick-contact]').setAttribute('aria-expanded','true');
  }
  $('[data-pick-contact]').addEventListener('click',()=>{if(!picker.classList.contains('hidden')){closePicker();return;}const current=++pickerRevision;void fetchContacts().then(()=>{if(current===pickerRevision)pickerRows(true);}).catch(error=>options.alert(errorText(error)));});
  to.addEventListener('focus',()=>{void fetchContacts().catch(()=>{});});
  to.addEventListener('input',()=>{pickerRows();const value=to.value,current=++pickerRevision;void fetchContacts().then(()=>{if(current===pickerRevision&&document.activeElement===to&&to.value===value)pickerRows();}).catch(()=>{});});
  to.addEventListener('keydown',event=>{if(event.key==='Escape')closePicker();if(event.key==='ArrowDown'&&!picker.classList.contains('hidden')){event.preventDefault();picker.querySelector<HTMLButtonElement>('button')?.focus();}});
  picker.addEventListener('keydown',event=>{
    const keys=[...picker.querySelectorAll<HTMLButtonElement>('button')],index=keys.indexOf(document.activeElement as HTMLButtonElement);
    if(event.key==='Escape'){closePicker();to.focus();closePicker();}
    if(event.key==='ArrowDown'||event.key==='ArrowUp'){event.preventDefault();keys[(index+(event.key==='ArrowDown'?1:-1)+keys.length)%keys.length]?.focus();}
  });
  document.addEventListener('pointerdown',event=>{if(!(event.target as Element).closest('.compose-recipient-line'))closePicker();});

  function feedback(item: {status:string;failureReason?:string|null}):void {
    const root=$<HTMLElement>('[data-send-feedback]');root.classList.remove('hidden');root.dataset.status=item.status;
    $('[data-send-feedback-title]').textContent={QUEUED:'Message queued',SENDING:'Sending your message',SENT:'Message sent',FAILED:'Message could not be sent',UNCERTAIN:'Check delivery status'}[item.status]??item.status;
    $('[data-send-feedback-copy]').textContent=item.failureReason||{QUEUED:'You can follow its progress in Sent.',SENDING:'Connecting to your mail server…',SENT:'Your SMTP server accepted the message.'}[item.status]||'Open Sent for details.';
    $('[data-send-feedback-icon]').replaceChildren(icon(item.status==='SENT'?'m5 12 4 4L19 6':'m3 3 19 9-19 9 4-9-4-9ZM7 12h15'));
  }
  function queued(item: {id:string;status:string;failureReason?:string|null}):void {
    const current=++feedbackRevision;window.clearTimeout(feedbackTimer);feedback(item);
    async function check(){
      try{
        const latest=await api<Sent>(`/api/mail/outbound/sent/${item.id}`);if(current!==feedbackRevision)return;feedback(latest);
        if(['QUEUED','SENDING'].includes(latest.status))feedbackTimer=window.setTimeout(()=>void check(),3000);
        else if(latest.status==='SENT')feedbackTimer=window.setTimeout(()=> $('[data-send-feedback]').classList.add('hidden'),8000);
      }catch{if(current===feedbackRevision)feedbackTimer=window.setTimeout(()=>void check(),10000);}
    }
    feedbackTimer=window.setTimeout(()=>void check(),2000);
    if(options.view()==='sent')void load(query,page,true);
  }
  $('[data-close-send-feedback]').addEventListener('click',()=>{++feedbackRevision;window.clearTimeout(feedbackTimer);$('[data-send-feedback]').classList.add('hidden');});
  $('[data-view-sent]').addEventListener('click',()=>{$('[data-send-feedback]').classList.add('hidden');++feedbackRevision;window.clearTimeout(feedbackTimer);options.navigate('sent');});
  return {load,closePicker,queued,leave:()=>{++revision;window.clearTimeout(poll);selected=null;closePicker();}};
}
