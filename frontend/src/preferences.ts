export type Language = 'en' | 'tr' | 'de' | 'fr' | 'ru' | 'pl';
type Theme = 'dark' | 'light';

const languages: Language[] = ['en', 'tr', 'de', 'fr', 'ru', 'pl'];
const flags: Record<Language, string> = { en: '🇬🇧', tr: '🇹🇷', de: '🇩🇪', fr: '🇫🇷', ru: '🇷🇺', pl: '🇵🇱' };
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
  });
  const picker = document.createElement('details');
  picker.className = 'preference-language';
  const trigger = document.createElement('summary');
  trigger.className = 'preference-language-trigger';
  const menu = document.createElement('div');
  menu.className = 'preference-language-menu'; menu.setAttribute('role', 'menu');
  for (const language of languages) {
    const option = document.createElement('button');
    option.type = 'button'; option.dataset.language = language; option.setAttribute('role', 'menuitemradio');
    option.addEventListener('click', () => { void setLanguage(language); picker.open = false; trigger.focus(); });
    menu.append(option);
  }
  picker.append(trigger, menu);
  picker.addEventListener('toggle', () => {
    if (picker.open) document.querySelectorAll<HTMLDetailsElement>('.preference-language').forEach(other => { if (other !== picker) other.open = false; });
  });
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
    trigger.textContent = `${flags[language]} ${names[language][language]}`;
    trigger.setAttribute('aria-label', `${controlLabels[language].language}: ${names[language][language]}`);
    picker.querySelectorAll<HTMLButtonElement>('[data-language]').forEach(option => {
      const target = option.dataset.language as Language;
      option.textContent = `${flags[target]} ${names[language][target]}`;
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
  const sideNav = document.querySelector<HTMLElement>('.account-pane .sidebar-primary');
  if (sideNav) sideNav.append(controls(true));
  const authCard = document.querySelector<HTMLElement>('.auth-card');
  if (authCard) authCard.prepend(controls());
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
