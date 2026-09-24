window.dataLayer = window.dataLayer || [];
function gtag(){dataLayer.push(arguments);}
gtag('consent', 'default', {
    analytics_storage: 'denied',
    ad_storage: 'denied',
    ad_user_data: 'denied',
    ad_personalization: 'denied'
});

function enableDispatchAnalytics() {
    if (window.dispatchAnalyticsLoaded) return;
    window.dispatchAnalyticsLoaded = true;
    gtag('consent', 'update', { analytics_storage: 'granted' });
    gtag('js', new Date());
    gtag('config', 'G-27S55TMZXW');
    var script = document.createElement('script');
    script.async = true;
    script.src = 'https://www.googletagmanager.com/gtag/js?id=G-27S55TMZXW';
    document.head.appendChild(script);
}

window.addEventListener('dispatch:analytics-accepted', enableDispatchAnalytics);
window.addEventListener('dispatch:analytics-denied', function () {
    gtag('consent', 'update', { analytics_storage: 'denied' });
});
try {
    if (localStorage.getItem('dispatch.analytics-consent.v1') === 'accepted') enableDispatchAnalytics();
} catch (ignored) { /* Analytics stays off when storage is unavailable. */ }
