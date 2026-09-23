import Sortable from 'sortablejs';

type OrderOptions = {
  draggable?: string;
  dataIdAttr?: string;
  handle?: string;
  save: (ids: number[]) => Promise<void>;
  onError: (error: unknown) => void;
  onSettled: () => void;
};

export function initAccountOrder(list: HTMLElement, status: HTMLElement, options: OrderOptions) {
  const motion = window.matchMedia?.('(prefers-reduced-motion: reduce)');
  let dragging = false;
  let saving = false;
  let original: string[] = [];
  let suppressClickUntil = 0;
  const draggable=options.draggable??'.account-head',dataIdAttr=options.dataIdAttr??'data-account';
  const busy = () => dragging || saving;
  const announcePosition = (item: HTMLElement) => {
    const position = sortable.toArray().indexOf(item.getAttribute(dataIdAttr)!);
    const email = item.querySelector('.account-email')?.textContent ?? 'Account';
    status.textContent = `${email} moved to position ${position + 1} of ${sortable.toArray().length}.`;
  };

  async function commit(item: HTMLElement) {
    const order = sortable.toArray();
    if (order.every((id, index) => id === original[index])) {
      options.onSettled();
      return;
    }
    saving = true;
    sortable.option('disabled', true);
    list.setAttribute('aria-busy', 'true');
    try {
      await options.save(order.map(Number));
      announcePosition(item);
    } catch (error) {
      sortable.sort(original, !motion?.matches);
      status.textContent = 'Account order could not be saved. Previous order restored.';
      options.onError(error);
    } finally {
      saving = false;
      sortable.option('disabled', false);
      list.removeAttribute('aria-busy');
      options.onSettled();
      const current=list.querySelector<HTMLElement>(`[${dataIdAttr}="${item.getAttribute(dataIdAttr)}"]`);
      (current?.matches('button')?current:current?.querySelector<HTMLElement>('button'))?.focus({ preventScroll: true });
    }
  }

  const sortable = new Sortable(list, {
    draggable,
    handle: options.handle,
    filter: '.account-sharing-indicator',
    preventOnFilter: false,
    dataIdAttr,
    direction: 'vertical',
    animation: motion?.matches ? 0 : 220,
    easing: 'cubic-bezier(.2, .75, .25, 1)',
    forceFallback: true,
    fallbackOnBody: true,
    fallbackTolerance: 6,
    delay: 180,
    delayOnTouchOnly: true,
    touchStartThreshold: 5,
    ghostClass: 'account-sort-placeholder',
    chosenClass: 'account-sort-chosen',
    fallbackClass: 'account-sort-floating',
    scrollSensitivity: 45,
    scrollSpeed: 8,
    onStart: () => {
      original = sortable.toArray();
      dragging = true;
      document.body.classList.add('sorting-accounts');
    },
    onEnd: event => {
      dragging = false;
      suppressClickUntil = performance.now() + 300;
      document.body.classList.remove('sorting-accounts');
      void commit(event.item);
    }
  });

  const onClick = (event: MouseEvent) => {
    if (busy() || (event.detail > 0 && performance.now() < suppressClickUntil)) {
      event.preventDefault();
      event.stopImmediatePropagation();
    }
  };
  const onKey = (event: KeyboardEvent) => {
    if (!event.altKey || !['ArrowUp', 'ArrowDown'].includes(event.key)) return;
    const item = (event.target as HTMLElement).closest<HTMLElement>(draggable);
    if (!item) return;
    event.preventDefault();
    event.stopPropagation();
    if (busy()) return;
    original = sortable.toArray();
    const from = original.indexOf(item.getAttribute(dataIdAttr)!);
    const to = from + (event.key === 'ArrowUp' ? -1 : 1);
    if (to < 0 || to >= original.length) return;
    const order = [...original];
    [order[from], order[to]] = [order[to], order[from]];
    sortable.sort(order, !motion?.matches);
    void commit(item);
  };
  const onMotion=()=>sortable.option('animation',motion?.matches?0:220);
  list.addEventListener('click',onClick,true);list.addEventListener('keydown',onKey);
  motion?.addEventListener('change',onMotion);
  return { busy, destroy:()=>{sortable.destroy();list.removeEventListener('click',onClick,true);list.removeEventListener('keydown',onKey);motion?.removeEventListener('change',onMotion);} };
}
