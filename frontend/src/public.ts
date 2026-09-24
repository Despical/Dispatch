import { api, ApiError } from './api';

const revealSelector = '[data-reveal]';

async function initPublicProfile(): Promise<void> {
    const profile = document.querySelector<HTMLAnchorElement>('[data-public-profile]');
    const signIn = document.querySelector<HTMLAnchorElement>('.public-header-signin');
    if (!profile || !signIn) return;
    if (profile.classList.contains('hidden') && !signIn.classList.contains('hidden')) return;
    const showSignIn = (): void => {
        profile.classList.add('hidden');
        signIn.classList.remove('hidden');
    };
    type Profile = { displayName: string; role: 'ADMIN' | 'USER' };
    let user: Profile;
    try {
        user = await api<Profile>('/api/auth/me');
    } catch (error) {
        if (!(error instanceof ApiError) || error.status !== 401) { showSignIn(); return; }
        try {
            await api('/api/auth/refresh', { method: 'POST' });
            user = await api<Profile>('/api/auth/me');
        } catch { showSignIn(); return; }
    }
    const name = user.displayName.trim();
    if (!name) { showSignIn(); return; }
    const parts = name.split(/\s+/);
    profile.querySelector('[data-public-initials]')!.textContent = (parts[0][0] + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toLocaleUpperCase('en-US');
    profile.querySelector('[data-public-name]')!.textContent = name;
    profile.querySelector('[data-public-role]')!.textContent = user.role === 'ADMIN' ? 'Administrator' : 'User';
    profile.setAttribute('aria-label', `Open ${name}'s workspace`);
    signIn.classList.add('hidden');
    profile.classList.remove('hidden');
}

export function initPublicPage(): void {
    void initPublicProfile();
    const previewOpen = document.querySelector<HTMLButtonElement>('[data-preview-open]');
    const preview = document.querySelector<HTMLDialogElement>('[data-preview-dialog]');
    if (previewOpen && preview) {
        const close = (): void => {
            if (!preview.open || preview.classList.contains('is-closing')) return;
            preview.classList.add('is-closing');
            window.setTimeout(() => { preview.close(); preview.classList.remove('is-closing'); previewOpen.focus(); }, 200);
        };
        previewOpen.addEventListener('click', () => {
            const image = preview.querySelector<HTMLImageElement>('[data-preview-src]');
            if (image && !image.hasAttribute('src')) image.src = image.dataset.previewSrc!;
            preview.showModal();
        });
        preview.querySelector('[data-preview-close]')?.addEventListener('click', close);
        preview.addEventListener('click', event => { if (event.target === preview) close(); });
        preview.addEventListener('cancel', event => { event.preventDefault(); close(); });
    }
    const mobileMenu = document.querySelector<HTMLDetailsElement>('.public-mobile-menu');
    mobileMenu?.addEventListener('click', event => {
        if (event.target instanceof Element && event.target.closest('a')) mobileMenu.open = false;
    });
    document.addEventListener('keydown', event => {
        if (event.key === 'Escape' && mobileMenu?.open) {
            mobileMenu.open = false;
            mobileMenu.querySelector('summary')?.focus();
        }
    });
    document.addEventListener('click', event => {
        if (mobileMenu?.open && event.target instanceof Node && !mobileMenu.contains(event.target)) {
            mobileMenu.open = false;
        }
    });

    if (!('IntersectionObserver' in window) || typeof window.matchMedia !== 'function') return;

    const motionPreference = window.matchMedia('(prefers-reduced-motion: reduce)');
    if (motionPreference.matches) return;

    const elements = [...document.querySelectorAll<HTMLElement>(revealSelector)];
    const pending = new Set<HTMLElement>();
    let observer: IntersectionObserver | undefined;

    const hashTarget = (): HTMLElement | null => {
        if (!window.location.hash) return null;
        try {
            return document.getElementById(decodeURIComponent(window.location.hash.slice(1)));
        } catch {
            return null;
        }
    };

    const sharesTarget = (element: HTMLElement, target: Node | null): boolean =>
        target !== null && (element.contains(target) || target.contains(element));

    const cleanup = (): void => {
        observer?.disconnect();
        window.removeEventListener('hashchange', revealHashTarget);
        document.removeEventListener('focusin', revealFocusedTarget);
        motionPreference.removeEventListener?.('change', revealForReducedMotion);
    };

    const reveal = (element: HTMLElement, immediately = false): void => {
        if (immediately) element.classList.remove('reveal-pending');
        element.classList.add('is-revealed');
        pending.delete(element);
        observer?.unobserve(element);
        if (pending.size === 0) cleanup();
    };

    const revealHashTarget = (): void => {
        const target = hashTarget();
        for (const element of pending) {
            if (sharesTarget(element, target)) reveal(element, true);
        }
    };

    const revealFocusedTarget = (event: FocusEvent): void => {
        if (!(event.target instanceof Node)) return;
        for (const element of pending) {
            if (element.contains(event.target)) reveal(element, true);
        }
    };

    const revealForReducedMotion = (event: MediaQueryListEvent): void => {
        if (event.matches) {
            for (const element of pending) reveal(element, true);
        }
    };

    try {
        observer = new IntersectionObserver(entries => {
            for (const entry of entries) {
                if (entry.isIntersecting) reveal(entry.target as HTMLElement);
            }
        }, { threshold: 0.08, rootMargin: '0px 0px -24px 0px' });

        const target = hashTarget();
        for (const element of elements) {
            if (element.getBoundingClientRect().top <= window.innerHeight
                || element.contains(document.activeElement)
                || sharesTarget(element, target)) continue;

            pending.add(element);
            observer.observe(element);
            element.classList.add('reveal-pending');
        }

        if (pending.size === 0) {
            cleanup();
            return;
        }

        window.addEventListener('hashchange', revealHashTarget);
        document.addEventListener('focusin', revealFocusedTarget);
        motionPreference.addEventListener?.('change', revealForReducedMotion);
    } catch {
        for (const element of elements) element.classList.remove('reveal-pending', 'is-revealed');
        cleanup();
    }
}
