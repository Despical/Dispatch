import './styles.css';
import './status.css';
import './preferences.css';
import './light.css';
import { initCookieNotice } from './cookie-notice';
import { initPreferences } from './preferences';

const page = document.body.dataset.page;
await initPreferences();
if (page === 'home' || page === 'public' || page === 'login' || page === 'bootstrap') initCookieNotice();
if (page === 'login' || page === 'bootstrap') {
    const { initBootstrap, initLogin } = await import('./auth');
    if (page === 'login') initLogin();
    else initBootstrap();
}
if (page === 'mail') {
    const { initMail } = await import('./mail');
    void initMail();
}
if (page === 'home' || page === 'public') {
    const { initPublicPage } = await import('./public');
    initPublicPage();
}
if (page === 'status' || page === 'admin-users') {
    const { initAdminShell, initAdminUsers } = await import('./admin');
    initAdminShell();
    if (page === 'admin-users') initAdminUsers();
}
if (page === 'status') {
    const { initStatusPage } = await import('./status');
    initStatusPage();
}
