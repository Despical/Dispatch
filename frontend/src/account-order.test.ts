// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';
import Sortable from 'sortablejs';
import { initAccountOrder } from './account-order';

let list: HTMLElement;
let status: HTMLElement;
const ids = () => [...list.querySelectorAll<HTMLElement>('[data-account]')].map(item => Number(item.dataset.account));
const key = (id: number, direction: string) => list.querySelector(`[data-account="${id}"]`)!
  .dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, altKey: true, key: direction }));
const flush = async () => { await Promise.resolve(); await Promise.resolve(); };

beforeEach(() => {
  document.body.innerHTML = '<div id="accounts">' + [1, 2, 3].map(id =>
    `<button class="account-head" data-account="${id}"><span class="account-email">${id}@example.com</span></button>`).join('') + '</div><span id="status"></span>';
  list = document.querySelector('#accounts')!;
  status = document.querySelector('#status')!;
});

describe('account reordering', () => {
  it('saves keyboard moves, retains focus and announces the new position', async () => {
    const save = vi.fn().mockResolvedValue(undefined);
    initAccountOrder(list, status, { save, onError: vi.fn(), onSettled: vi.fn() });
    key(3, 'ArrowUp');
    await flush();
    expect(ids()).toEqual([1, 3, 2]);
    expect(save).toHaveBeenCalledWith([1, 3, 2]);
    expect(document.activeElement?.getAttribute('data-account')).toBe('3');
    expect(status.textContent).toContain('position 2 of 3');
    Sortable.get(list)!.destroy();
  });

  it('restores the previous order on save failure and unlocks subsequent moves', async () => {
    const failure = new Error('Connection lost');
    const onError = vi.fn();
    const control = initAccountOrder(list, status, { save: vi.fn().mockRejectedValue(failure), onError, onSettled: vi.fn() });
    key(1, 'ArrowDown');
    expect(control.busy()).toBe(true);
    await flush();
    expect(ids()).toEqual([1, 2, 3]);
    expect(control.busy()).toBe(false);
    expect(onError).toHaveBeenCalledWith(failure);
    expect(status.textContent).toContain('Previous order restored');
    Sortable.get(list)!.destroy();
  });

  it('keeps ordinary clicks working and ignores boundary moves and repeated moves while saving', async () => {
    let finish!: () => void;
    const save = vi.fn(() => new Promise<void>(resolve => { finish = resolve; }));
    initAccountOrder(list, status, { save, onError: vi.fn(), onSettled: vi.fn() });
    const clicked = vi.fn();
    list.querySelector('button')!.addEventListener('click', clicked);
    list.querySelector('button')!.click();
    expect(clicked).toHaveBeenCalledOnce();
    key(1, 'ArrowUp');
    expect(save).not.toHaveBeenCalled();
    key(1, 'ArrowDown');
    key(1, 'ArrowDown');
    expect(save).toHaveBeenCalledOnce();
    expect(ids()).toEqual([2, 1, 3]);
    finish(); await flush();
    Sortable.get(list)!.destroy();
  });

  it('commits a completed drag without turning the release into a mailbox click', async () => {
    const save = vi.fn().mockResolvedValue(undefined);
    const clicked = vi.fn();
    const control = initAccountOrder(list, status, { save, onError: vi.fn(), onSettled: vi.fn() });
    const sortable = Sortable.get(list)!;
    const item = list.querySelector<HTMLElement>('[data-account="1"]')!;
    item.addEventListener('click', clicked);
    sortable.options.onStart!.call(sortable, { item } as Sortable.SortableEvent);
    expect(control.busy()).toBe(true);
    sortable.sort(['2', '3', '1']);
    sortable.options.onEnd!.call(sortable, { item } as Sortable.SortableEvent);
    await flush();
    item.dispatchEvent(new MouseEvent('click', { bubbles: true, detail: 1 }));
    expect(clicked).not.toHaveBeenCalled();
    expect(save).toHaveBeenCalledWith([2, 3, 1]);
    expect(control.busy()).toBe(false);
    sortable.destroy();
  });
});
