// @vitest-environment jsdom
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { beforeEach, afterEach, expect, it, vi } from 'vitest';
import { initMailExtras, type ExtraView } from './mail-extras';
const { api } = vi.hoisted(()=>({api:vi.fn()}));
vi.mock('./api',()=>({api}));
const template=readFileSync(resolve(dirname(fileURLToPath(import.meta.url)),'../../src/main/resources/templates/mail.html'),'utf8');
const $=<T extends HTMLElement>(selector:string)=>document.querySelector<T>(selector)!;
let view:ExtraView, filter:string, extras:ReturnType<typeof initMailExtras>;
let contacts:{id:number;displayName:string;email:string;starred:boolean}[];
const settle=()=>vi.advanceTimersByTimeAsync(0);
beforeEach(()=>{
  vi.useFakeTimers();document.body.innerHTML=template;view='contacts';filter='all';
  contacts=[{id:1,displayName:'Ada Lovelace',email:'ada@example.test',starred:false},{id:2,displayName:'Grace Hopper',email:'grace@example.test',starred:true}];
  api.mockReset();api.mockImplementation(async(path:string,options?:RequestInit)=>{
    if(path==='/api/mail/contacts')return structuredClone(contacts);
    if(path==='/api/mail/contacts/1/star'){contacts[0].starred=JSON.parse(String(options?.body)).starred;return {...contacts[0]};}
    if(path.startsWith('/api/mail/outbound/sent?'))return {content:[{id:'mail',accountName:'Ada Lovelace',from:'ada@example.test',to:'test@example.test',subject:'Test',status:'SENT',createdAt:'2026-09-22T09:00:00Z'}],page:0,totalPages:1,totalElements:1};
    throw Error(path);
  });
  extras=initMailExtras({view:()=>view,filter:()=>filter,navigate:vi.fn(),pagination:vi.fn(),resetSearch:vi.fn(),clearDetail:vi.fn(),compose:vi.fn(),alert:vi.fn(),undo:vi.fn()});
});
afterEach(()=>{extras.leave();vi.useRealTimers();});
it('persists stars and limits the Starred filter to favorite contacts',async()=>{
  await extras.load('',0);
  $('[data-contact-id="1"] .contact-star').click();await settle();
  expect(api).toHaveBeenCalledWith('/api/mail/contacts/1/star',expect.objectContaining({method:'PUT',body:'{"starred":true}'}));
  expect($('[data-contact-id="1"] .contact-star').getAttribute('aria-pressed')).toBe('true');
  filter='starred';await extras.load('',0);expect(document.querySelectorAll('[data-contact-id]')).toHaveLength(2);
  $('[data-contact-id="1"] .contact-star').click();await settle();
  expect(document.querySelector('[data-contact-id="1"]')).toBeNull();
  expect(document.querySelector('[data-contact-id="2"]')).not.toBeNull();
});
it('keeps focus quiet, suggests matching typed names and hides empty or unmatched input',async()=>{
  const input=$<HTMLInputElement>('[data-compose-form] [name=to]');input.focus();await settle();
  expect($('[data-contact-picker]').classList.contains('hidden')).toBe(true);
  input.value='Ada';input.dispatchEvent(new Event('input'));await settle();
  expect($('[data-contact-picker]').classList.contains('hidden')).toBe(false);
  expect(document.querySelectorAll('.contact-picker-item')).toHaveLength(1);
  input.value='unmatched';input.dispatchEvent(new Event('input'));await settle();
  expect($('[data-contact-picker]').classList.contains('hidden')).toBe(true);
  input.value='';input.dispatchEvent(new Event('input'));await settle();
  expect($('[data-contact-picker]').classList.contains('hidden')).toBe(true);
});
it('offers to save a valid unknown address, but not an already saved address',async()=>{
  const input=$<HTMLInputElement>('[data-compose-form] [name=to]');input.focus();await settle();
  input.value='new@example.test';input.dispatchEvent(new Event('input'));await settle();
  expect($('.contact-picker-add').textContent).toContain('new@example.test');
  input.value='ada@example.test';input.dispatchEvent(new Event('input'));await settle();
  expect(document.querySelector('.contact-picker-add')).toBeNull();
});
it('still allows explicitly browsing all saved contacts with the contacts button',async()=>{
  $('[data-pick-contact]').click();await settle();expect(document.querySelectorAll('.contact-picker-item')).toHaveLength(2);
});
it('shows sender initials on Sent with time before the status badge',async()=>{
  view='sent';await extras.load('',0);
  expect($('.sent-row .message-avatar').textContent).toBe('AL');
  expect($('.sent-row-meta').firstElementChild?.tagName).toBe('TIME');
  expect($('.sent-row-meta').lastElementChild?.textContent).toBe('Sent');
});
