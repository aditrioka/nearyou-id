/*
 * NearYouID admin panel behaviour. Vendored and served same-origin so the
 * panel runs under CSP script-src 'self' with no inline scripts
 * (admin-security-headers-and-login-throttle design D3). Loaded deferred by
 * layout.peb on every admin page; each hook is a no-op where its markup is
 * absent. Pinned in htmx.min.js.SHA256SUMS: re-pin after every edit.
 */
(function () {
    'use strict';

    // CSRF: copy the session's csrf-token meta into X-CSRF-Token on every
    // HTMX request (admin-login "Authenticated layout includes CSRF meta tag
    // and HTMX configRequest hook"). The meta renders only on authenticated
    // pages, so the login page registers no listener. Read at request time so
    // a per-login rotation propagates without a page reload.
    if (document.querySelector('meta[name="csrf-token"]')) {
        document.addEventListener('htmx:configRequest', function (evt) {
            var meta = document.querySelector('meta[name="csrf-token"]');
            if (meta) {
                evt.detail.headers['X-CSRF-Token'] = meta.content;
            }
        });
    }

    // Login: password visibility toggle (design.md D3 of admin-mockup-parity).
    // Without JS the button is inert and the input stays masked (safe default).
    var btn = document.getElementById('pw-toggle');
    var input = document.getElementById('password');
    if (btn && input) {
        var eyeOn = btn.querySelector('[data-icon="visibility"]');
        var eyeOff = btn.querySelector('[data-icon="visibility_off"]');
        btn.addEventListener('click', function () {
            var show = input.type === 'password';
            input.type = show ? 'text' : 'password';
            eyeOn.style.display = show ? 'none' : '';
            eyeOff.parentElement.hidden = !show;
            btn.setAttribute('aria-pressed', show ? 'true' : 'false');
            btn.setAttribute('aria-label', show ? 'Hide password' : 'Show password');
        });
    }

    // Wordlist editor: client-side entry filter (graceful: the list renders
    // fully without JS). Delegated from document so it survives HTMX swaps of
    // #wl-content without re-binding.
    document.addEventListener('input', function (evt) {
        var filter = evt.target;
        if (!filter.matches || !filter.matches('#wl-content .wl-filter')) {
            return;
        }
        var q = filter.value.toLowerCase();
        document.querySelectorAll('#wl-content .wl-entry').forEach(function (li) {
            li.style.display = li.textContent.toLowerCase().indexOf(q) === -1 ? 'none' : '';
        });
    });
})();
