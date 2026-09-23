// @vitest-environment jsdom
import {beforeEach,it,expect} from 'vitest';
import {initStorageCard} from './storage-card';
beforeEach(()=>{localStorage.clear();document.body.dataset.adminId='42';document.body.innerHTML='<div data-storage-card><button data-storage-toggle></button><div data-storage-content>Usage</div></div>';});
it('changes only on click and remembers the per-user choice',()=>{
  initStorageCard();const card=document.querySelector('[data-storage-card]')!,button=document.querySelector<HTMLButtonElement>('button')!;
  card.dispatchEvent(new Event('pointerenter'));expect(button.getAttribute('aria-expanded')).toBe('true');
  expect(localStorage.getItem('dispatch:storage-collapsed:42')).toBe('false');
  button.click();expect(button.getAttribute('aria-expanded')).toBe('false');
  expect(localStorage.getItem('dispatch:storage-collapsed:42')).toBe('true');
  button.click();expect(button.getAttribute('aria-expanded')).toBe('true');
  card.dispatchEvent(new Event('pointerleave'));card.dispatchEvent(new Event('pointerenter'));
  expect(button.getAttribute('aria-expanded')).toBe('true');
});
