// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from 'vitest';
import { initPreferences, setLanguage, translate } from './preferences';

describe('language and appearance preferences', () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.lang = 'en';
    document.documentElement.dataset.theme = 'dark';
    document.title = 'Features | Dispatch';
    document.body.dataset.page = 'public';
    document.body.innerHTML = '<header class="public-header"><div class="public-header-actions"></div><details class="public-mobile-menu"><nav></nav></details></header><main><h1>Features</h1><p>Mail accounts</p></main>';
  });

  it('translates page text, browser title, controls, and newly inserted labels while keeping the brand name', async () => {
    await initPreferences();
    await setLanguage('tr');
    expect(document.documentElement.lang).toBe('tr');
    expect(document.querySelector('h1')?.textContent).toBe('Özellikler');
    expect(document.querySelector('p')?.textContent).toBe('Posta hesapları');
    expect(document.title).toBe('Özellikler | Dispatch');
    expect(document.querySelector('[data-language="ru"]')?.textContent).toBe('🇷🇺 Rusça');
    expect(translate('Open draft Untitled')).toBe('Taslağı aç Untitled');
    const label = document.createElement('span');
    label.textContent = 'Open contact Alex';
    document.querySelector('main')!.append(label);
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(label.textContent).toContain('Alex');
    expect(label.textContent).not.toBe('Open contact Alex');
    await setLanguage('en');
    expect(document.querySelector('h1')?.textContent).toBe('Features');
    expect(document.title).toBe('Features | Dispatch');
  });

  it('switches the theme and saves the choice', async () => {
    await initPreferences();
    document.querySelector<HTMLButtonElement>('.public-header-actions .preference-theme')!.click();
    expect(document.documentElement.dataset.theme).toBe('light');
    expect(localStorage.getItem('dispatch-theme')).toBe('light');
    expect(document.querySelector<HTMLButtonElement>('.preference-theme')?.getAttribute('aria-label')).toBe('Switch to dark theme');
  });
});
