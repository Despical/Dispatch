import { api } from './api';
import { initAccountOrder } from './account-order';
export type Permissions = {canView:boolean;canSend:boolean;canOrganize:boolean;canDelete:boolean};
export type SharedAccount = {id:number;email:string;displayName:string;authProvider:'GOOGLE'|'PASSWORD';unreadCount:number};
export type Grant = Permissions & {id:number;email:string;displayName:string;enabled:boolean;hidden:boolean;accountIds:number[];accounts:SharedAccount[];accountPermissions:(Permissions&{accountId:number})[]};
type Overview = {outgoing:Grant[];incoming:Grant[];ownAccounts:SharedAccount[]};
type Options = {closeMenu:()=>void;changed:()=>void;select:(id:number,email:string)=>void;selected:()=>number|null;confirm:(title:string,message:string,submit:string)=>Promise<boolean>};
const $=<T extends HTMLElement>(selector:string)=>document.querySelector<T>(selector)!;
const el=<K extends keyof HTMLElementTagNameMap>(tag:K,css='',text='')=>{const node=document.createElement(tag);node.className=css;node.textContent=text;return node;};
const people='M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8M17 5a4 4 0 0 1 0 8M22 21v-2a4 4 0 0 0-3-3.9';
const eye='M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Zm10-3a3 3 0 1 0 0 6 3 3 0 0 0 0-6';
function icon(path:string,css=''){const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');svg.setAttribute('viewBox','0 0 24 24');svg.setAttribute('aria-hidden','true');svg.setAttribute('class',css);const line=document.createElementNS(svg.namespaceURI,'path');line.setAttribute('d',path);svg.append(line);return svg;}
function gmailIcon(){const svg=icon('','account-gmail-icon');svg.replaceChildren();for(const [d,color] of [
  ['M3.5 18V6.5','#4285f4'],['M3.5 6.5 12 13','#ea4335'],['M12 13 20.5 6.5','#ea4335'],
  ['M20.5 6.5V10','#fbbc04'],['M20.5 10v8','#34a853']]){const line=document.createElementNS(svg.namespaceURI,'path');line.setAttribute('d',d);line.setAttribute('stroke',color);line.setAttribute('stroke-width','2.4');line.setAttribute('fill','none');line.setAttribute('stroke-linecap','round');line.setAttribute('stroke-linejoin','round');svg.append(line);}return svg;}
const error=(value:unknown)=>value instanceof Error?value.message:'Sharing could not be updated.';
export function initMailSharing(options:Options){
  let data:Overview={outgoing:[],incoming:[],ownAccounts:[]}, revision=0, snapshot='',tab='outgoing',editing:Grant|null=null,saving=false,accountFilter:number|null=null;
  const chosen=new Set<number>();
  const perAccount=new Map<number,Permissions>();
  const storageOwner=document.body.dataset.adminId??'local';
  const orderKey=`dispatch:shared-order:${storageOwner}`,expandedKey=`dispatch:shared-expanded:${storageOwner}`;
  let orders:Record<string,number[]>={};
  try{const saved=JSON.parse(localStorage.getItem(orderKey)??'{}');if(saved&&typeof saved==='object'&&!Array.isArray(saved))for(const [key,value] of Object.entries(saved))if(Array.isArray(value))orders[key]=value.filter(id=>Number.isSafeInteger(id)&&id>0);}catch{/* Browser storage may be unavailable. */}
  const expanded=new Set<number>();
  try{const saved=JSON.parse(localStorage.getItem(expandedKey)??'[]');if(Array.isArray(saved))for(const id of saved)if(Number.isSafeInteger(id)&&id>0)expanded.add(id);}catch{/* Browser storage may be unavailable. */}
  function saveExpanded(){try{localStorage.setItem(expandedKey,JSON.stringify([...expanded]));}catch{/* Keep disclosure controls usable when storage is blocked. */}}
  const sorters:ReturnType<typeof initAccountOrder>[]=[];let pendingSidebar=false;
  const ordered=<T extends {id:number}>(items:T[],key:string)=>{const positions=new Map((orders[key]??[]).map((id,index)=>[id,index]));return [...items].sort((a,b)=>(positions.get(a.id)??Infinity)-(positions.get(b.id)??Infinity));};
  function attachOrder(list:HTMLElement,key:string,draggable:string,dataIdAttr:string,handle?:string){
    sorters.push(initAccountOrder(list,$('[data-account-order-status]'),{draggable,dataIdAttr,handle,
      save:async ids=>{const next={...orders,[key]:ids};localStorage.setItem(orderKey,JSON.stringify(next));orders=next;},
      onError:()=>status($('[data-global-alert]'),'Mailbox order could not be saved.'),
      onSettled:()=>{if(pendingSidebar)renderSidebar();}}));
  }
  const modal=$<HTMLDialogElement>('[data-sharing-modal]'),editor=$<HTMLDialogElement>('[data-sharing-editor]'),form=$<HTMLFormElement>('[data-sharing-form]');
  const accountModal=$<HTMLDialogElement>('[data-sharing-accounts-modal]'),accountForm=$<HTMLFormElement>('[data-sharing-accounts-form]');
  const field=(name:string)=>form.elements.namedItem(name) as HTMLInputElement;
  const available=()=>data.incoming.filter(s=>s.enabled&&!s.hidden);
  function permission(accountId:number|null):Permissions|null {
    if(accountId===null)return null;
    const grant=available().find(s=>s.accounts.some(a=>a.id===accountId));
    return grant?.accountPermissions.find(p=>p.accountId===accountId)??null;
  }
  function status(node:HTMLElement,text:string){node.textContent=text;node.classList.toggle('hidden',!text);}
  async function refresh(){const current=++revision;const result=await api<Overview>('/api/mail/sharing');if(current!==revision)return;const next=JSON.stringify(result);if(next===snapshot)return;snapshot=next;data=result;render();renderSidebar();options.changed();}
  function setTab(next:string){tab=next;$('[data-add-share]').hidden=tab!=='outgoing';document.querySelectorAll<HTMLElement>('[data-sharing-tab]').forEach(button=>{const active=button.dataset.sharingTab===tab;button.setAttribute('aria-selected',String(active));button.tabIndex=active?0:-1;});document.querySelectorAll<HTMLElement>('[data-sharing-panel]').forEach(panel=>panel.hidden=panel.dataset.sharingPanel!==tab);}
  function render(){
    for(const direction of ['outgoing','incoming'] as const){
      const list=$(`[data-sharing-${direction}]`);list.replaceChildren();
      const grants=data[direction].filter(g=>direction==='incoming'||accountFilter===null||g.accountIds.includes(accountFilter));
      if(!grants.length){list.append(el('p','sharing-empty',direction==='outgoing'?'No shared mailboxes.':'No mailboxes shared with you.'));continue;}
      for(const grant of grants){
        const row=el('article',`sharing-row${grant.hidden&&direction==='incoming'?' is-hidden':''}`),copy=el('div','sharing-person');
        const initials=(grant.displayName||grant.email.split('@')[0]).trim().split(/\s+/).map(part=>Array.from(part)[0]).slice(0,2).join('').toUpperCase();
        const avatar=el('span','sharing-avatar',initials);avatar.setAttribute('aria-hidden','true');
        copy.append(el('strong','',grant.email));if(grant.displayName&&grant.displayName!==grant.email)copy.append(el('span','',grant.displayName));
        const permissions=[...(grant.canView?['View']:[]),...(grant.canSend?['Send']:[]),...(grant.canOrganize?['Organize']:[]),...(grant.canDelete?['Trash']:[])];
        row.append(avatar,copy);
        const details=el('div','sharing-details'),count=grant.accountIds.length;
        const mailboxCount=el('span','sharing-mailbox-count',`${count} ${count===1?'mailbox':'mailboxes'}`);
        mailboxCount.prepend(icon('M4 6.5h16v11H4zM4.5 7l7.5 6 7.5-6'));
        const accounts=direction==='outgoing'?(data.ownAccounts??[]).filter(a=>grant.accountIds.includes(a.id)):grant.accounts;
        mailboxCount.title=accounts.map(a=>a.email).join('\n');details.append(mailboxCount);
        const badges=el('div','sharing-permission-list');badges.setAttribute('aria-label','Permissions');
        for(const label of !grant.enabled?['Account disabled']:permissions.length?permissions:['No permissions'])badges.append(el('span','sharing-permission',label));
        details.append(badges);
        const actions=el('div','sharing-actions');
        if(direction==='outgoing'){
          const edit=el('button','button button-ghost','Edit');edit.type='button';edit.addEventListener('click',()=>openEditor(grant));
          const remove=el('button','icon-button sharing-remove');remove.type='button';remove.title='Remove access';remove.setAttribute('aria-label',`Remove access for ${grant.email}`);remove.append(icon('M3 6h18M9 6V4h6v2M5 6l1 14h12l1-14M10 10v6M14 10v6'));
          remove.addEventListener('click',async()=>{if(!await options.confirm('Remove mailbox access?',`${grant.email} will lose access to your connected mail accounts.`,'Remove access'))return;await mutate(remove,()=>api(`/api/mail/sharing/${grant.id}`,{method:'DELETE'}));});actions.append(edit,remove);
        }else{
          const visibility=el('button','button button-ghost sharing-visibility');visibility.type='button';visibility.title=grant.hidden?'Show mailboxes':'Hide mailboxes';visibility.setAttribute('aria-label',`${visibility.title} from ${grant.email}`);visibility.setAttribute('aria-pressed',String(!grant.hidden));visibility.append(icon(eye+(grant.hidden?'M3 3l18 18':'')),el('span','',grant.hidden?'Hidden':'Visible'));
          visibility.addEventListener('click',()=>void mutate(visibility,()=>api(`/api/mail/sharing/${grant.id}/visibility`,{method:'PUT',body:JSON.stringify({value:grant.hidden})})));actions.append(visibility);
        }
        row.append(actions,details);list.append(row);
      }
    }
  }
  async function mutate(button:HTMLButtonElement,action:()=>Promise<unknown>){button.disabled=true;try{await action();await refresh();status($('[data-sharing-error]'),'');}catch(e){status($('[data-sharing-error]'),error(e));}finally{button.disabled=false;}}
  function open(accountId:number|null=null,accountEmail=''){
    accountFilter=accountId;options.closeMenu();setTab('outgoing');
    status($('[data-sharing-context]'),accountEmail);status($('[data-sharing-error]'),'');render();modal.showModal();
    void refresh().catch(e=>status($('[data-sharing-error]'),error(e)));
  }
  function openEditor(grant:Grant|null){
    editing=grant;form.reset();field('email').value=grant?.email??'';field('email').disabled=!!grant;
    for(const key of ['canView','canSend','canOrganize','canDelete'] as const)field(key).checked=grant?.[key]??(key==='canView');
    chosen.clear();perAccount.clear();for(const id of grant?.accountIds??[])chosen.add(id);
    for(const item of grant?.accountPermissions??[])perAccount.set(item.accountId,item);
    $('[id=share-editor-title]').textContent=grant?'Edit permissions':'Share mailboxes';
    status($('[data-sharing-editor-error]'),'');editor.showModal();
  }
  function renderAccountChoices(){
    const list=$('[data-sharing-account-choices]');list.replaceChildren();
    const owned=data.ownAccounts??[];
    if(!owned.length)list.append(el('p','sharing-empty','No mail accounts connected.'));
    for(const account of owned){
      const label=el('label','sharing-account-choice'),checkbox=el('input');checkbox.type='checkbox';checkbox.value=String(account.id);checkbox.dataset.shareAccount=String(account.id);checkbox.checked=chosen.has(account.id);
      checkbox.addEventListener('change',()=>{if(checkbox.checked)chosen.add(account.id);else chosen.delete(account.id);permissionControls.classList.toggle('hidden',!checkbox.checked);$<HTMLButtonElement>('[data-share-save]').disabled=!chosen.size;});
      const copy=el('span');copy.append(el('strong','',account.email));if(account.displayName!==account.email)copy.append(el('small','',account.displayName));label.append(checkbox,copy);list.append(label);
      const permissionControls=el('div',`sharing-account-permissions${checkbox.checked?'':' hidden'}`);
      const defaults:Permissions={canView:field('canView').checked,canSend:field('canSend').checked,canOrganize:field('canOrganize').checked,canDelete:field('canDelete').checked};
      const selected=perAccount.get(account.id)??defaults;perAccount.set(account.id,{...selected});
      for(const [key,title] of [['canView','View'],['canSend','Send'],['canOrganize','Organize'],['canDelete','Trash']] as const){
        const option=el('label','sharing-account-permission'),toggle=el('input');toggle.type='checkbox';toggle.checked=selected[key];
        toggle.addEventListener('change',()=>{perAccount.set(account.id,{...perAccount.get(account.id)!,[key]:toggle.checked});});
        option.append(toggle,el('span','',title));permissionControls.append(option);
      }
      list.append(permissionControls);
    }
    for(const id of chosen)if(!owned.some(a=>a.id===id))chosen.delete(id);
    $<HTMLButtonElement>('[data-share-save]').disabled=!chosen.size;
  }
  function renderSidebar(){
    if(sorters.some(sorter=>sorter.busy())){pendingSidebar=true;return;}pendingSidebar=false;
    sorters.splice(0).forEach(sorter=>sorter.destroy());
    const list=$('[data-shared-account-list]');list.replaceChildren();
    for(const grant of ordered(available().filter(s=>s.canView),'groups')){
      const group=el('section',`shared-account-group${expanded.has(grant.id)?' expanded':''}`),head=el('button','nav-item shared-owner');head.type='button';head.setAttribute('aria-expanded',String(expanded.has(grant.id)));head.setAttribute('aria-controls',`shared-accounts-${grant.id}`);head.title=`Mailboxes shared by ${grant.email}`;
      head.append(icon(people,'nav-icon'),el('span','account-email',grant.email),icon('m7 10 5 5 5-5','shared-chevron'));
      group.dataset.sharedGroup=String(grant.id);head.setAttribute('aria-describedby','account-order-help');head.setAttribute('aria-keyshortcuts','Alt+ArrowUp Alt+ArrowDown');
      const collapse=el('div','shared-accounts-collapse'),children=el('div','shared-accounts-inner');collapse.id=`shared-accounts-${grant.id}`;children.inert=!expanded.has(grant.id);
      if(!grant.accounts.length)children.append(el('p','sharing-empty','No connected accounts.'));
        for(const account of ordered(grant.accounts.filter(a=>grant.accountPermissions.find(p=>p.accountId===a.id)?.canView),`accounts:${grant.id}`)){const button=el('button',`nav-item shared-account${options.selected()===account.id?' active':''}`);button.type='button';button.dataset.sharedAccount=String(account.id);button.title=account.displayName;button.setAttribute('aria-describedby','account-order-help');button.setAttribute('aria-keyshortcuts','Alt+ArrowUp Alt+ArrowDown');button.append(account.authProvider==='GOOGLE'?gmailIcon():icon('M4 6.5h16v11H4zM4.5 7l7.5 6 7.5-6','nav-icon'),el('span','account-email',account.email),el('span','nav-count',account.unreadCount?String(account.unreadCount):''));button.addEventListener('click',()=>options.select(account.id,account.email));children.append(button);}
      head.addEventListener('click',()=>{const isOpen=!expanded.has(grant.id);if(isOpen)expanded.add(grant.id);else expanded.delete(grant.id);saveExpanded();group.classList.toggle('expanded',isOpen);head.setAttribute('aria-expanded',String(isOpen));children.inert=!isOpen;});collapse.append(children);group.append(head,collapse);list.append(group);
      attachOrder(children,`accounts:${grant.id}`,'.shared-account','data-shared-account');
    }
    attachOrder(list,'groups','.shared-account-group','data-shared-group','.shared-owner');
  }
  function sharingButton(accountId:number,email:string){
    if(!data.outgoing.some(g=>g.accountPermissions.some(p=>p.accountId===accountId&&(p.canView||p.canSend))))return null;
    const button=el('button','account-sharing-indicator');button.type='button';button.title='Mailbox access';button.setAttribute('aria-label',`See who can access ${email}`);button.append(icon(people));
    button.addEventListener('pointerdown',e=>e.stopPropagation());button.addEventListener('click',e=>{e.stopPropagation();open(accountId,email);});return button;
  }
  form.addEventListener('submit',event=>{
    event.preventDefault();if(saving||!form.reportValidity())return;
    renderAccountChoices();status($('[data-sharing-accounts-error]'),'');editor.close();accountModal.showModal();
  });
  accountForm.addEventListener('submit',async event=>{
    event.preventDefault();if(saving||!chosen.size)return;saving=true;
    const submit=$<HTMLButtonElement>('[data-share-save]');submit.textContent='Saving…';
    const permissions=Object.fromEntries(['canView','canSend','canOrganize','canDelete'].map(key=>[key,field(key).checked]));
    const request={...(editing?{}:{email:field('email').value}),...permissions,accountIds:[...chosen],
      accountPermissions:[...chosen].map(accountId=>({accountId,...perAccount.get(accountId)!}))};
    const controls=[...accountForm.querySelectorAll<HTMLInputElement|HTMLButtonElement>('input,button')];controls.forEach(c=>c.disabled=true);
    try{
      await api(`/api/mail/sharing${editing?`/${editing.id}`:''}`,{method:editing?'PUT':'POST',body:JSON.stringify(request)});
      accountModal.close();await refresh();
    }catch(e){status(accountModal.open?$('[data-sharing-accounts-error]'):$('[data-sharing-error]'),error(e));}
    finally{saving=false;controls.forEach(c=>c.disabled=false);submit.disabled=!chosen.size;submit.textContent='Save access';}
  });
  $('[data-share-back]').addEventListener('click',()=>{if(!saving){accountModal.close();editor.showModal();}});
  $('[data-close-share-accounts]').addEventListener('click',()=>{if(!saving)accountModal.close();});
  accountModal.addEventListener('cancel',e=>{if(saving)e.preventDefault();});
  accountModal.addEventListener('click',e=>{if(e.target===accountModal&&!saving)accountModal.close();});
  $('[data-open-mail-sharing]').addEventListener('click',()=>open());$('[data-add-share]').addEventListener('click',()=>openEditor(null));$('[data-close-sharing]').addEventListener('click',()=>modal.close());
  document.querySelectorAll('[data-close-share-editor]').forEach(button=>button.addEventListener('click',()=>{if(!saving)editor.close();}));editor.addEventListener('cancel',e=>{if(saving)e.preventDefault();});editor.addEventListener('click',e=>{if(e.target===editor&&!saving)editor.close();});
  document.querySelectorAll<HTMLButtonElement>('[data-sharing-tab]').forEach(button=>{button.addEventListener('click',()=>setTab(button.dataset.sharingTab!));button.addEventListener('keydown',e=>{if(['ArrowLeft','ArrowRight','Home','End'].includes(e.key)){e.preventDefault();setTab(e.key==='Home'?'outgoing':e.key==='End'?'incoming':tab==='outgoing'?'incoming':'outgoing');$(`[data-sharing-tab=${tab}]`).focus();}});});
  return {refresh,permission,renderSidebar,sharingButton,reveal:(id:number|null)=>{const grant=available().find(g=>g.accountPermissions.some(p=>p.accountId===id&&p.canView));if(grant&&!expanded.has(grant.id)){expanded.add(grant.id);saveExpanded();}},accounts:()=>available().flatMap(s=>s.accounts.filter(a=>s.accountPermissions.find(p=>p.accountId===a.id)?.canView)),senderAccounts:()=>available().flatMap(s=>s.accounts.filter(a=>s.accountPermissions.find(p=>p.accountId===a.id)?.canSend))};
}
