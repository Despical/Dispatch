export function initComposeAccountPicker(root: HTMLElement) {
  const select = root.querySelector<HTMLSelectElement>('[data-compose-account]')!;
  const trigger = root.querySelector<HTMLButtonElement>('[data-compose-account-trigger]')!;
  const label = root.querySelector<HTMLElement>('[data-compose-account-value]')!;
  const menu = root.querySelector<HTMLElement>('[data-compose-account-options]')!;
  let active = 0;
  let search = '';
  let searchAt = 0;
  const isOpen = () => trigger.getAttribute('aria-expanded') === 'true';
  const close = () => {
    menu.classList.add('hidden');
    trigger.setAttribute('aria-expanded', 'false');
    trigger.removeAttribute('aria-activedescendant');
    search = '';
  };
  function highlight(index: number) {
    const items = [...menu.querySelectorAll<HTMLElement>('[role="option"]')];
    active = Math.max(0, Math.min(index, items.length - 1));
    items.forEach((item, position) => item.classList.toggle('is-highlighted', position === active));
    if (items[active]) {
      trigger.setAttribute('aria-activedescendant', items[active].id);
      items[active].scrollIntoView?.({ block: 'nearest' });
    }
  }
  function choose(index: number) {
    if (select.disabled || trigger.disabled) return;
    const changed = select.selectedIndex !== index;
    select.selectedIndex = index;
    refresh();
    trigger.focus();
    if (changed) {
      select.dispatchEvent(new Event('input', { bubbles: true }));
      select.dispatchEvent(new Event('change', { bubbles: true }));
    }
  }
  function refresh() {
    close();
    label.textContent = select.options[select.selectedIndex]?.textContent ?? 'Choose an account';
    trigger.disabled = select.disabled || select.options.length === 0;
    menu.replaceChildren();
    [...select.options].forEach((option, index) => {
      const item = document.createElement('button');
      item.type = 'button'; item.tabIndex = -1;
      item.className = 'compose-account-option'; item.id = `compose-account-option-${index}`;
      item.setAttribute('role', 'option');
      item.setAttribute('aria-selected', String(option.selected));
      const text = document.createElement('span'); text.textContent = option.textContent;
      const check = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      check.setAttribute('viewBox', '0 0 24 24'); check.setAttribute('aria-hidden', 'true');
      const path = document.createElementNS('http://www.w3.org/2000/svg', 'path'); path.setAttribute('d', 'm5 12 4 4L19 6'); check.append(path);
      item.append(text, check);
      item.addEventListener('pointerdown', event => event.preventDefault());
      item.addEventListener('click', () => choose(index));
      menu.append(item);
    });
  }
  function open() {
    if (trigger.disabled || select.disabled || !select.options.length) return;
    menu.classList.remove('hidden');
    trigger.setAttribute('aria-expanded', 'true');
    highlight(select.selectedIndex);
  }
  trigger.addEventListener('click', () => isOpen() ? close() : open());
  trigger.addEventListener('keydown', event => {
    if (event.key === 'Escape' && isOpen()) { event.preventDefault(); event.stopPropagation(); close(); return; }
    if (event.key === 'Tab') { close(); return; }
    if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
      event.preventDefault();
      if (!isOpen()) { open(); return; }
      highlight(event.key === 'Home' ? 0 : event.key === 'End' ? select.options.length - 1 : active + (event.key === 'ArrowDown' ? 1 : -1));
    } else if ((event.key === 'Enter' || event.key === ' ') && isOpen()) {
      event.preventDefault(); choose(active);
    } else if (event.key.length === 1 && !event.ctrlKey && !event.altKey && !event.metaKey && event.key !== ' ') {
      event.preventDefault();
      if (!isOpen()) open();
      const now = Date.now();
      search = (now - searchAt < 700 ? search : '') + event.key.toLocaleLowerCase(); searchAt = now;
      const index = [...select.options].findIndex(option => option.text.toLocaleLowerCase().startsWith(search));
      if (index >= 0) highlight(index);
    }
  });
  const dismissOutside = (event: Event) => { if (!root.contains(event.target as Node)) close(); };
  document.addEventListener('pointerdown', dismissOutside, true);
  document.addEventListener('click', dismissOutside, true);
  document.addEventListener('focusin', dismissOutside);
  select.addEventListener('change', refresh);
  refresh();
  return { refresh, close };
}
