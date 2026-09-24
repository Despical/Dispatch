export type Language = 'en' | 'tr' | 'de' | 'fr' | 'ru' | 'pl';
type Theme = 'dark' | 'light';

const languages: Language[] = ['en', 'tr', 'de', 'fr', 'ru', 'pl'];
const names: Record<Language, Record<Language, string>> = {
  en: { en: 'English', tr: 'Turkish', de: 'German', fr: 'French', ru: 'Russian', pl: 'Polish' },
  tr: { en: 'İngilizce', tr: 'Türkçe', de: 'Almanca', fr: 'Fransızca', ru: 'Rusça', pl: 'Lehçe' },
  de: { en: 'Englisch', tr: 'Türkisch', de: 'Deutsch', fr: 'Französisch', ru: 'Russisch', pl: 'Polnisch' },
  fr: { en: 'Anglais', tr: 'Turc', de: 'Allemand', fr: 'Français', ru: 'Russe', pl: 'Polonais' },
  ru: { en: 'Английский', tr: 'Турецкий', de: 'Немецкий', fr: 'Французский', ru: 'Русский', pl: 'Польский' },
  pl: { en: 'Angielski', tr: 'Turecki', de: 'Niemiecki', fr: 'Francuski', ru: 'Rosyjski', pl: 'Polski' }
};
const controlLabels: Record<Language, { language: string; light: string; dark: string }> = {
  en: { language: 'Language', light: 'Switch to light theme', dark: 'Switch to dark theme' },
  tr: { language: 'Dil', light: 'Açık temaya geç', dark: 'Koyu temaya geç' },
  de: { language: 'Sprache', light: 'Zum hellen Design wechseln', dark: 'Zum dunklen Design wechseln' },
  fr: { language: 'Langue', light: 'Passer au thème clair', dark: 'Passer au thème sombre' },
  ru: { language: 'Язык', light: 'Включить светлую тему', dark: 'Включить тёмную тему' },
  pl: { language: 'Język', light: 'Włącz jasny motyw', dark: 'Włącz ciemny motyw' }
};

const originalText = new WeakMap<Text, string>();
const appliedText = new WeakMap<Text, string>();
const originalAttributes = new WeakMap<Element, Map<string, string>>();
const appliedAttributes = new WeakMap<Element, Map<string, string>>();
const translatedAttributes = ['aria-label', 'title', 'placeholder', 'alt'];
let englishPageTitle = '';
let description: HTMLMetaElement | null = null;
let englishDescription: string | undefined;
let dictionary: Record<string, string> = {};
let languageRevision = 0;
let patterns: Array<{ regex: RegExp; translation: string }> = [];

function setDictionary(entries: Record<string, string>): void {
  dictionary = entries;
  patterns = Object.entries(entries).filter(([source]) => source.includes('{0}'))
    .sort(([left], [right]) => right.length - left.length).map(([source, translation]) => {
    const regex = source.split(/(\{\d+\})/g).map(part => /^\{\d+\}$/.test(part)
      ? '(.+?)' : part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('');
    return { regex: new RegExp(`^${regex}$`), translation };
  });
}

async function loadDictionary(language: Language): Promise<Record<string, string>> {
  switch (language) {
    case 'tr': return (await import('./i18n/tr.json')).default;
    case 'de': return (await import('./i18n/de.json')).default;
    case 'fr': return (await import('./i18n/fr.json')).default;
    case 'ru': return (await import('./i18n/ru.json')).default;
    case 'pl': return (await import('./i18n/pl.json')).default;
    default: return {};
  }
}

export function currentLanguage(): Language {
  const value = document.documentElement.lang.split('-')[0].toLowerCase();
  return languages.includes(value as Language) ? value as Language : 'en';
}

export function translate(source: string): string {
  if (dictionary[source]) return dictionary[source];
  for (const pattern of patterns) {
    const match = pattern.regex.exec(source);
    if (match) return pattern.translation.replace(/\{(\d+)\}/g, (_, index: string) => match[Number(index) + 1] ?? '');
  }
  return source;
}

function normalize(value: string): string { return value.replace(/\s+/g, ' ').trim(); }

function translateHead(): void {
  if (document.body.dataset.page !== 'mail') {
    const title = englishPageTitle.endsWith(' | Dispatch') ? englishPageTitle.slice(0, -11) : englishPageTitle;
    document.title = `${translate(title)} | Dispatch`;
  }
  if (description && englishDescription) description.content = translate(englishDescription);
}

function excluded(element: Element | null): boolean {
  return !!element?.closest('script,style,svg,code,pre,[data-no-translate],.preference-controls,.message-copy,.message-avatar,.message-side,.extra-row-copy,.sent-row-meta,.managed-account-info,[data-detail-subject],[data-detail-from-name],[data-detail-from-address],[data-detail-to],[data-message-frame]');
}

function translateNode(node: Text): void {
  if (excluded(node.parentElement)) return;
  const current = node.nodeValue ?? '';
  const source = current === appliedText.get(node) ? originalText.get(node) ?? current : current;
  originalText.set(node, source);
  const key = normalize(source);
  const translated = key ? translate(key) : key;
  const next = translated !== key
    ? (source.match(/^\s*/)?.[0] ?? '') + translated + (source.match(/\s*$/)?.[0] ?? '')
    : source;
  if (current !== next) node.nodeValue = next;
  appliedText.set(node, next);
}

function translateElement(element: Element): void {
  if (excluded(element)) return;
  let sources = originalAttributes.get(element);
  let applied = appliedAttributes.get(element);
  if (!sources) { sources = new Map(); originalAttributes.set(element, sources); }
  if (!applied) { applied = new Map(); appliedAttributes.set(element, applied); }
  for (const attribute of translatedAttributes) {
    const current = element.getAttribute(attribute);
    if (current === null) continue;
    const source = current === applied.get(attribute) ? sources.get(attribute) ?? current : current;
    sources.set(attribute, source);
    const next = translate(normalize(source));
    if (current !== next) element.setAttribute(attribute, next);
    applied.set(attribute, next);
  }
}

function translateTree(root: Node): void {
  if (root.nodeType === Node.TEXT_NODE) { translateNode(root as Text); return; }
  if (!(root instanceof Element) || excluded(root)) return;
  translateElement(root);
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT);
  while (walker.nextNode()) {
    if (walker.currentNode.nodeType === Node.TEXT_NODE) translateNode(walker.currentNode as Text);
    else translateElement(walker.currentNode as Element);
  }
}

function themeIcon(kind: 'sun' | 'moon'): SVGSVGElement {
  const icon = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  icon.setAttribute('viewBox', '0 0 24 24'); icon.setAttribute('aria-hidden', 'true');
  icon.classList.add(`preference-${kind}`);
  const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  path.setAttribute('d', kind === 'sun'
    ? 'M12 3v2m0 14v2M3 12h2m14 0h2M5.6 5.6 7 7m10 10 1.4 1.4M18.4 5.6 17 7M7 17l-1.4 1.4M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z'
    : 'M20 15.5A8.5 8.5 0 0 1 8.5 4 8.5 8.5 0 1 0 20 15.5Z');
  icon.append(path);
  return icon;
}

function globeIcon(): SVGSVGElement {
  const icon = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  icon.setAttribute('viewBox', '0 0 24 24');
  icon.setAttribute('aria-hidden', 'true');
  icon.classList.add('preference-globe');
  for (const data of ['M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20Z', 'M2 12h20', 'M12 2c2.5 2.7 3.8 6 3.8 10S14.5 19.3 12 22', 'M12 2C9.5 4.7 8.2 8 8.2 12S9.5 19.3 12 22']) {
    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute('d', data);
    icon.append(path);
  }
  return icon;
}

function flagIcon(language: Language): SVGSVGElement {
  const icon = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  icon.setAttribute('viewBox', '0 0 24 16');
  icon.setAttribute('aria-hidden', 'true');
  icon.classList.add('preference-flag');
  const artwork: Record<Language, string> = {
    en: '<path fill="#012169" d="M0 0h24v16H0z"/><path stroke="#fff" stroke-width="4" d="M0 0l24 16M24 0L0 16"/><path stroke="#c8102e" stroke-width="1.6" d="M0 0l24 16M24 0L0 16"/><path stroke="#fff" stroke-width="5" d="M12 0v16M0 8h24"/><path stroke="#c8102e" stroke-width="2.5" d="M12 0v16M0 8h24"/>',
    tr: '<path fill="#e30a17" d="M0 0h24v16H0z"/><circle cx="10" cy="8" r="4.5" fill="#fff"/><circle cx="11.4" cy="8" r="3.6" fill="#e30a17"/><path fill="#fff" d="m16 5.1.8 2.1 2.2.1-1.7 1.4.6 2.1L16 9.6l-1.8 1.2.6-2.1-1.7-1.4 2.2-.1z"/>',
    de: '<path fill="#161616" d="M0 0h24v5.33H0z"/><path fill="#dd0000" d="M0 5.33h24v5.34H0z"/><path fill="#ffce00" d="M0 10.67h24V16H0z"/>',
    fr: '<path fill="#0055a4" d="M0 0h8v16H0z"/><path fill="#fff" d="M8 0h8v16H8z"/><path fill="#ef4135" d="M16 0h8v16h-8z"/>',
    ru: '<path fill="#fff" d="M0 0h24v5.33H0z"/><path fill="#0039a6" d="M0 5.33h24v5.34H0z"/><path fill="#d52b1e" d="M0 10.67h24V16H0z"/>',
    pl: '<path fill="#fff" d="M0 0h24v8H0z"/><path fill="#dc143c" d="M0 8h24v8H0z"/>'
  };
  icon.innerHTML = artwork[language];
  return icon;
}

function controls(mobile = false): HTMLElement {
  const group = document.createElement('div');
  group.className = `preference-controls${mobile ? ' preference-controls-mobile' : ''}`;
  group.dataset.noTranslate = '';
  const toggle = document.createElement('button');
  toggle.type = 'button'; toggle.className = 'preference-theme';
  toggle.append(themeIcon('moon'), themeIcon('sun'));
  toggle.addEventListener('click', () => {
    const next: Theme = document.documentElement.dataset.theme === 'light' ? 'dark' : 'light';
    document.documentElement.dataset.theme = next;
    try { localStorage.setItem('dispatch-theme', next); } catch { /* Storage may be unavailable. */ }
    updateControls();
    document.dispatchEvent(new Event('dispatch:themechange'));
  });
  const picker = document.createElement('details');
  picker.className = 'preference-language';
  const trigger = document.createElement('summary');
  trigger.className = 'preference-language-trigger';
  const code = document.createElement('span');
  code.className = 'preference-language-code';
  trigger.append(globeIcon(), code);
  const menu = document.createElement('div');
  menu.className = 'preference-language-menu'; menu.setAttribute('role', 'menu');
  for (const language of languages) {
    const option = document.createElement('button');
    option.type = 'button'; option.dataset.language = language; option.setAttribute('role', 'menuitemradio');
    option.addEventListener('click', () => { void setLanguage(language); picker.open = false; trigger.focus(); });
    menu.append(option);
  }
  picker.append(trigger, menu);
  const positionMenu = (): void => {
    if (!picker.open) return;
    const anchor = trigger.getBoundingClientRect();
    const viewportWidth = Math.max(document.documentElement.clientWidth || window.innerWidth, 240);
    const viewportHeight = Math.max(document.documentElement.clientHeight || window.innerHeight, 240);
    const width = Math.min(Math.max(menu.scrollWidth, 180), viewportWidth - 24);
    const desiredHeight = Math.min(menu.scrollHeight + 4, 410, viewportHeight - 24);
    const below = viewportHeight - anchor.bottom - 12;
    const above = anchor.top - 12;
    const placeBelow = below >= desiredHeight || below >= above;
    const height = Math.max(0, Math.min(desiredHeight, placeBelow ? below : above));
    menu.style.left = `${Math.max(12, Math.min(anchor.left, viewportWidth - width - 12))}px`;
    menu.style.top = `${placeBelow ? anchor.bottom + 8 : Math.max(12, anchor.top - height - 8)}px`;
    menu.style.width = `${width}px`;
    menu.style.maxHeight = `${height}px`;
  };
  picker.addEventListener('toggle', () => {
    if (picker.open) {
      document.querySelectorAll<HTMLDetailsElement>('.preference-language').forEach(other => { if (other !== picker) other.open = false; });
      positionMenu();
    }
  });
  window.addEventListener('resize', positionMenu);
  document.addEventListener('scroll', event => { if (!menu.contains(event.target as Node)) positionMenu(); }, true);
  group.append(toggle, picker);
  return group;
}

function updateControls(): void {
  const language = currentLanguage();
  const light = document.documentElement.dataset.theme === 'light';
  document.querySelectorAll<HTMLElement>('.preference-controls').forEach(group => {
    const toggle = group.querySelector<HTMLButtonElement>('.preference-theme')!;
    toggle.setAttribute('aria-label', controlLabels[language][light ? 'dark' : 'light']);
    toggle.title = toggle.getAttribute('aria-label')!;
    const picker = group.querySelector<HTMLDetailsElement>('.preference-language')!;
    const trigger = picker.querySelector<HTMLElement>('summary')!;
    trigger.querySelector<HTMLElement>('.preference-language-code')!.textContent = language.toUpperCase();
    trigger.setAttribute('aria-label', `${controlLabels[language].language}: ${names[language][language]}`);
    picker.querySelectorAll<HTMLButtonElement>('[data-language]').forEach(option => {
      const target = option.dataset.language as Language;
      const label = document.createElement('span');
      label.textContent = names[language][target];
      option.replaceChildren(flagIcon(target), label);
      option.setAttribute('aria-checked', String(target === language));
    });
  });
  document.querySelector<HTMLMetaElement>('meta[name="color-scheme"]')?.setAttribute('content', light ? 'light' : 'dark');
}

export async function setLanguage(language: Language): Promise<void> {
  const revision = ++languageRevision;
  let nextDictionary: Record<string, string>;
  try { nextDictionary = await loadDictionary(language); }
  catch { return; }
  if (revision !== languageRevision) return;
  setDictionary(nextDictionary);
  document.documentElement.lang = language;
  try { localStorage.setItem('dispatch-language', language); } catch { /* Storage may be unavailable. */ }
  updateControls();
  if (document.body) translateTree(document.body);
  translateHead();
  document.dispatchEvent(new Event('dispatch:languagechange'));
}

export async function initPreferences(): Promise<void> {
  englishPageTitle = document.title;
  description = document.querySelector<HTMLMetaElement>('meta[name="description"]');
  englishDescription = description?.content;
  try { setDictionary(await loadDictionary(currentLanguage())); }
  catch { document.documentElement.lang = 'en'; setDictionary({}); }
  const publicHeader = document.querySelector<HTMLElement>('.public-header-actions');
  if (publicHeader) publicHeader.prepend(controls());
  const publicMobile = document.querySelector<HTMLElement>('.public-mobile-menu nav');
  if (publicMobile) publicMobile.append(controls(true));
  const topActions = document.querySelector<HTMLElement>('.top-actions');
  if (topActions) topActions.prepend(controls());
  const accountMenu = document.querySelector<HTMLElement>('.account-popover');
  if (accountMenu) accountMenu.prepend(controls(true));
  updateControls();
  if (document.body) translateTree(document.body);
  translateHead();
  const observer = new MutationObserver(records => {
    for (const record of records) {
      if (record.type === 'characterData') translateNode(record.target as Text);
      else if (record.type === 'attributes') translateElement(record.target as Element);
      else for (const node of record.addedNodes) translateTree(node);
    }
  });
  observer.observe(document.body, { childList: true, characterData: true, subtree: true,
    attributes: true, attributeFilter: translatedAttributes });
  document.addEventListener('click', event => {
    if (event.target instanceof Element && event.target.closest('.preference-language')) return;
    document.querySelectorAll<HTMLDetailsElement>('.preference-language').forEach(picker => { picker.open = false; });
  });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape') document.querySelectorAll<HTMLDetailsElement>('.preference-language').forEach(picker => { picker.open = false; });
  });
}
