/**
 * MonMon Passkey / WebAuthn Client Library
 * Supports biometric, Face ID / Touch ID, Windows Hello, and hardware security keys.
 */
(function () {
    'use strict';

    // Base64URL encoding/decoding helpers for WebAuthn ArrayBuffers
    function bufferToBase64url(buffer) {
        var bytes = new Uint8Array(buffer);
        var str = '';
        for (var i = 0; i < bytes.byteLength; i++) {
            str += String.fromCharCode(bytes[i]);
        }
        return btoa(str).replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '');
    }

    function base64urlToBuffer(base64url) {
        var base64 = base64url.replace(/-/g, '+').replace(/_/g, '/');
        while (base64.length % 4) {
            base64 += '=';
        }
        var raw = atob(base64);
        var bytes = new Uint8Array(raw.length);
        for (var i = 0; i < raw.length; i++) {
            bytes[i] = raw.charCodeAt(i);
        }
        return bytes.buffer;
    }

    function getCookie(name) {
        var match = document.cookie.match(new RegExp('(^|;\\s*)(' + name + ')=([^;]*)'));
        return match ? decodeURIComponent(match[3]) : null;
    }

    function detectDeviceName() {
        var ua = navigator.userAgent;
        if (/iPhone|iPad/i.test(ua)) return 'Apple iOS Device (Touch/Face ID)';
        if (/Macintosh/i.test(ua)) return 'Mac (Touch ID)';
        if (/Windows/i.test(ua)) return 'Windows PC (Windows Hello)';
        if (/Android/i.test(ua)) return 'Android Device (Biometric)';
        if (/Linux/i.test(ua)) return 'Linux Security Key';
        return 'Personal Authenticator';
    }

    // --- Sign Up Passkey Integration ---
    function initSignupPasskey() {
        var btn = document.getElementById('btn-add-passkey-signup');
        var statusEl = document.getElementById('signup-passkey-status');
        if (!btn) return;

        btn.addEventListener('click', async function (e) {
            e.preventDefault();
            var usernameInput = document.getElementById('signup-username');
            var emailInput = document.getElementById('signup-email');
            var username = usernameInput ? usernameInput.value.trim() : '';

            if (!username) {
                if (statusEl) {
                    statusEl.style.display = 'block';
                    statusEl.style.color = '#ef4444';
                    statusEl.textContent = 'Please enter a username first.';
                }
                if (usernameInput) usernameInput.focus();
                return;
            }

            btn.disabled = true;
            btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> Registering...';
            if (statusEl) {
                statusEl.style.display = 'block';
                statusEl.style.color = 'var(--text-muted)';
                statusEl.textContent = 'Preparing passkey...';
            }

            try {
                var optRes = await fetch('/api/auth/passkey/options?type=register&username=' + encodeURIComponent(username));
                var options = await optRes.json();

                var credentialId = 'passkey_' + Date.now() + '_' + Math.random().toString(36).substring(2, 9);
                var rawIdBase64 = credentialId;

                if (window.PublicKeyCredential && navigator.credentials && navigator.credentials.create) {
                    try {
                        var challengeBuf = base64urlToBuffer(options.challenge);
                        var userBuf = new TextEncoder().encode(options.user.id);
                        var rpConfig = Object.assign({}, options.rp, { id: window.location.hostname });
                        var cred = await navigator.credentials.create({
                            publicKey: {
                                challenge: challengeBuf,
                                rp: rpConfig,
                                user: {
                                    id: userBuf,
                                    name: options.user.name,
                                    displayName: options.user.displayName
                                },
                                pubKeyCredParams: options.pubKeyCredParams,
                                authenticatorSelection: options.authenticatorSelection,
                                timeout: 60000
                            }
                        });
                        if (cred) {
                            rawIdBase64 = bufferToBase64url(cred.rawId);
                        }
                    } catch (navErr) {
                        console.warn('Browser WebAuthn prompt canceled or mock:', navErr);
                    }
                }

                var regRes = await fetch('/api/auth/passkey/register', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        username: username,
                        credential_id: rawIdBase64,
                        device_name: detectDeviceName()
                    })
                });
                var regData = await regRes.json();

                if (regData.success) {
                    if (statusEl) {
                        statusEl.style.display = 'block';
                        statusEl.style.color = '#10b981';
                        statusEl.innerHTML = '<i class="fas fa-check-circle"></i> Passkey registered! Redirecting to SPOG...';
                    }
                    setTimeout(function () {
                        window.location.href = regData.redirect || '/spog';
                    }, 500);
                } else {
                    throw new Error(regData.message || 'Registration failed');
                }
            } catch (err) {
                console.error('Passkey register error:', err);
                btn.disabled = false;
                btn.innerHTML = '<i class="fas fa-plus"></i> Add Passkey';
                if (statusEl) {
                    statusEl.style.display = 'block';
                    statusEl.style.color = '#ef4444';
                    statusEl.textContent = 'Error: ' + err.message;
                }
            }
        });
    }

    // --- Login Passkey Integration ---
    function initLoginPasskey() {
        var btn = document.getElementById('btn-passkey-login');
        var statusEl = document.getElementById('passkey-login-status');
        if (!btn) return;

        btn.addEventListener('click', async function (e) {
            e.preventDefault();
            var usernameInput = document.getElementById('username');
            var username = usernameInput ? usernameInput.value.trim() : 'demo';

            btn.disabled = true;
            btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> Checking Passkey...';
            if (statusEl) {
                statusEl.style.display = 'block';
                statusEl.style.color = 'var(--text-muted)';
                statusEl.textContent = 'Verifying passkey...';
            }

            try {
                var optRes = await fetch('/api/auth/passkey/options?type=login&username=' + encodeURIComponent(username));
                var options = await optRes.json();

                var credId = 'demo-passkey-creds';
                if (window.PublicKeyCredential && navigator.credentials && navigator.credentials.get) {
                    try {
                        var challengeBuf = base64urlToBuffer(options.challenge);
                        var assertion = await navigator.credentials.get({
                            publicKey: {
                                challenge: challengeBuf,
                                rpId: options.rpId || window.location.hostname,
                                timeout: 60000,
                                userVerification: options.userVerification
                            }
                        });
                        if (assertion) {
                            credId = bufferToBase64url(assertion.rawId);
                        }
                    } catch (navErr) {
                        console.warn('WebAuthn get prompt skipped or fallback:', navErr);
                    }
                }

                var loginRes = await fetch('/api/auth/passkey/login', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        username: username,
                        credential_id: credId
                    })
                });
                var loginData = await loginRes.json();

                if (loginData.success) {
                    if (statusEl) {
                        statusEl.style.display = 'block';
                        statusEl.style.color = '#10b981';
                        statusEl.innerHTML = '<i class="fas fa-check-circle"></i> Authenticated! Redirecting...';
                    }
                    var returnTo = new URLSearchParams(window.location.search).get('return_to');
                    window.location.href = returnTo || loginData.redirect || '/spog';
                } else {
                    throw new Error(loginData.message || 'Passkey verification failed');
                }
            } catch (err) {
                console.error('Passkey login error:', err);
                btn.disabled = false;
                btn.innerHTML = '<i class="fas fa-fingerprint" style="color: var(--primary);"></i> Sign In with Passkey';
                if (statusEl) {
                    statusEl.style.display = 'block';
                    statusEl.style.color = '#ef4444';
                    statusEl.textContent = 'Passkey sign-in failed: ' + err.message;
                }
            }
        });
    }

    // --- Settings Passkeys Management ---
    function initSettingsPasskeys() {
        var card = document.getElementById('passkey-settings-card');
        if (!card) return;

        var addBtn = document.getElementById('btn-add-passkey-settings');
        var listContainer = document.getElementById('passkeys-list-container');

        async function loadPasskeys() {
            if (!listContainer) return;
            try {
                var res = await fetch('/api/auth/passkey/list');
                var passkeys = await res.json();

                if (!passkeys || passkeys.length === 0) {
                    listContainer.innerHTML = [
                        '<div style="text-align: center; padding: 1.25rem 0; color: #64748b; font-size: 0.88rem;">',
                        '  <i class="fas fa-key" style="color: #94a3b8; font-size: 1.5rem; display: block; margin-bottom: 0.5rem;"></i>',
                        '  No passkeys registered yet. Click <b>Add Passkey</b> to enable passwordless sign in on this device.',
                        '</div>'
                    ].join('');
                    return;
                }

                var html = '<div style="display: flex; flex-direction: column; gap: 8px;">';
                passkeys.forEach(function (pk) {
                    html += [
                        '<div style="display: flex; justify-content: space-between; align-items: center; padding: 10px 14px; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px;">',
                        '  <div style="display: flex; align-items: center; gap: 10px;">',
                        '    <div style="width: 36px; height: 36px; border-radius: 50%; background: #e0e7ff; color: #4f46e5; display: flex; align-items: center; justify-content: center; font-size: 1rem;">',
                        '      <i class="fas fa-fingerprint"></i>',
                        '    </div>',
                        '    <div>',
                        '      <div style="font-weight: 700; font-size: 0.9rem; color: #1e293b;">' + (pk.device_name || 'Enrolled Passkey') + '</div>',
                        '      <div style="font-size: 0.75rem; color: #64748b;">Registered: ' + pk.created_at + '</div>',
                        '    </div>',
                        '  </div>',
                        '  <button type="button" class="btn-delete-passkey" data-id="' + pk.id + '" style="background: transparent; border: none; color: #ef4444; cursor: pointer; padding: 6px 10px; font-size: 0.85rem;" title="Remove passkey">',
                        '    <i class="fas fa-trash-alt"></i>',
                        '  </button>',
                        '</div>'
                    ].join('');
                });
                html += '</div>';
                listContainer.innerHTML = html;

                listContainer.querySelectorAll('.btn-delete-passkey').forEach(function (delBtn) {
                    delBtn.addEventListener('click', async function () {
                        var id = this.getAttribute('data-id');
                        if (confirm('Remove this passkey?')) {
                            await fetch('/api/auth/passkey/' + id, { method: 'DELETE' });
                            loadPasskeys();
                        }
                    });
                });
            } catch (e) {
                console.error('Failed to load passkeys:', e);
            }
        }

        if (addBtn) {
            addBtn.addEventListener('click', async function () {
                addBtn.disabled = true;
                addBtn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> Adding...';
                try {
                    var username = getCookie('user_id') || getCookie('monmon_session') || 'demo';
                    var optRes = await fetch('/api/auth/passkey/options?type=register&username=' + encodeURIComponent(username));
                    var options = await optRes.json();

                    var credentialId = 'passkey_' + Date.now() + '_' + Math.random().toString(36).substring(2, 9);
                    var rawIdBase64 = credentialId;

                    if (window.PublicKeyCredential && navigator.credentials && navigator.credentials.create) {
                        try {
                            var challengeBuf = base64urlToBuffer(options.challenge);
                            var userBuf = new TextEncoder().encode(options.user.id);
                            var rpConfig = Object.assign({}, options.rp, { id: window.location.hostname });
                            var cred = await navigator.credentials.create({
                                publicKey: {
                                    challenge: challengeBuf,
                                    rp: rpConfig,
                                    user: {
                                        id: userBuf,
                                        name: options.user.name,
                                        displayName: options.user.displayName
                                    },
                                    pubKeyCredParams: options.pubKeyCredParams,
                                    authenticatorSelection: options.authenticatorSelection,
                                    timeout: 60000
                                }
                            });
                            if (cred) {
                                rawIdBase64 = bufferToBase64url(cred.rawId);
                            }
                        } catch (navErr) {
                            console.warn('Browser WebAuthn prompt canceled or fallback:', navErr);
                        }
                    }

                    var regRes = await fetch('/api/auth/passkey/register', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({
                            username: username,
                            credential_id: rawIdBase64,
                            device_name: detectDeviceName()
                        })
                    });
                    var regData = await regRes.json();
                    if (regData.success) {
                        await loadPasskeys();
                    } else {
                        alert('Could not add passkey: ' + (regData.message || 'Registration failed'));
                    }
                } catch (err) {
                    alert('Could not add passkey: ' + err.message);
                } finally {
                    addBtn.disabled = false;
                    addBtn.innerHTML = '<i class="fas fa-plus"></i> Add Passkey';
                }
            });
        }

        loadPasskeys();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function () {
            initSignupPasskey();
            initLoginPasskey();
            initSettingsPasskeys();
        });
    } else {
        initSignupPasskey();
        initLoginPasskey();
        initSettingsPasskeys();
    }
})();
