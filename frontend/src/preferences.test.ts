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
    document.body.innerHTML = '<header class="public-header"><div class="public-header-actions"><span class="avatar-button" data-no-translate>BA</span><strong data-no-translate>Berke Akçen</strong></div><details class="public-mobile-menu"><nav></nav></details></header><main><h1>Features</h1><p>Mail accounts</p></main>';
  });

  it('translates page text, browser title, controls, and newly inserted labels while keeping the brand name', async () => {
    await initPreferences();
    await setLanguage('tr');
    expect(document.documentElement.lang).toBe('tr');
    expect(document.querySelector('h1')?.textContent).toBe('Özellikler');
    expect(document.querySelector('p')?.textContent).toBe('Posta hesapları');
    expect(document.title).toBe('Özellikler | Dispatch');
    expect(document.querySelector('[data-language="ru"]')?.textContent).toBe('Rusça');
    expect(document.querySelector('.preference-language-code')?.textContent).toBe('TR');
    expect(document.querySelector('.preference-globe')).not.toBeNull();
    expect(document.querySelector('[data-language="ru"] .preference-flag')).not.toBeNull();
    expect(document.querySelector('.avatar-button')?.textContent).toBe('BA');
    expect(document.querySelector('.public-header-actions strong')?.textContent).toBe('Berke Akçen');
    expect(translate('Open draft Untitled')).toBe('Taslağı aç Untitled');
    const label = document.createElement('span');
    label.textContent = 'Open contact Alex';
    document.querySelector('main')!.append(label);
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(label.textContent).toContain('Alex');
    expect(label.textContent).not.toBe('Open contact Alex');
    await setLanguage('ru');
    expect(document.querySelector('.avatar-button')?.textContent).toBe('BA');
    expect(document.querySelector('.public-header-actions strong')?.textContent).toBe('Berke Akçen');
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

  it('does not place appearance or language controls on sign-in cards', async () => {
    document.body.dataset.page = 'login';
    document.body.innerHTML = '<main class="auth-card"><h1>Sign in to Dispatch</h1></main>';
    await initPreferences();
    expect(document.querySelector('.auth-card .preference-controls')).toBeNull();
  });

  it('places narrow-screen controls in the profile menu instead of the sidebar', async () => {
    document.body.dataset.page = 'mail';
    document.body.innerHTML = '<header class="topbar"><div class="top-actions"></div></header><aside class="account-pane"><nav class="sidebar-primary"></nav></aside><div class="account-popover"></div>';
    await initPreferences();
    expect(document.querySelector('.account-popover > .preference-controls-mobile')).not.toBeNull();
    expect(document.querySelector('.sidebar-primary .preference-controls')).toBeNull();
  });

  it('translates administrator status reasons in every supported language', async () => {
    const expected = {
      tr: ['Ek tarayıcısı', 'Neden müdahale gerekiyor'],
      de: ['Anhangscanner', 'Warum Handlungsbedarf besteht'],
      fr: ['Analyse des pièces jointes', 'Pourquoi une intervention est nécessaire'],
      ru: ['Сканирование вложений', 'Почему требуется внимание'],
      pl: ['Skaner załączników', 'Dlaczego potrzebna jest interwencja']
    } as const;
    await initPreferences();
    for (const [language, [service, reason]] of Object.entries(expected)) {
      await setLanguage(language as keyof typeof expected);
      expect(translate('Attachment scanner')).toBe(service);
      expect(translate('Why attention is needed')).toBe(reason);
    }
  });
});
