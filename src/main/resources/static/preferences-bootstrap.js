(function () {
    try {
        var theme = localStorage.getItem('dispatch-theme');
        document.documentElement.dataset.theme = theme === 'light' ? 'light' : 'dark';
        var stored = localStorage.getItem('dispatch-language');
        var language = /^(en|tr|de|fr|ru|pl)$/.test(stored || '') ? stored
            : (navigator.language || 'en').split('-')[0].toLowerCase();
        document.documentElement.lang = /^(en|tr|de|fr|ru|pl)$/.test(language) ? language : 'en';
    } catch (_) {
        document.documentElement.dataset.theme = 'dark';
        document.documentElement.lang = 'en';
    }
}());
