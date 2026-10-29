(function () {
    'use strict';

    function checkLiveWebhooks(silent) {
        var refreshBtn = document.getElementById('btn-refresh-webhooks');
        var refreshIcon = document.getElementById('refresh-webhooks-icon');
        if (refreshIcon) {
            refreshIcon.classList.add('fa-spin');
        }

        fetch('/bank/monzo/webhooks/status', {
            method: 'GET',
            headers: {
                'Accept': 'application/json'
            }
        })
        .then(function (res) {
            if (!res.ok) throw new Error('HTTP ' + res.status);
            return res.json();
        })
        .then(function (data) {
            if (refreshIcon) {
                refreshIcon.classList.remove('fa-spin');
            }
            if (!data || !data.connected) return;

            var card = document.getElementById('monzo-status-card');
            var badge = document.getElementById('monzo-webhook-badge');
            var alertBox = document.getElementById('monzo-status-alert');
            var urlWrap = document.getElementById('monzo-webhook-url-wrap');
            var urlVal = document.getElementById('monzo-webhook-url-val');
            var webhooksWrap = document.getElementById('monzo-active-webhooks-wrap');
            var debitBadge = document.getElementById('monzo-debit-badge');
            var debitVal = document.getElementById('monzo-debit-val');
            var flexBadge = document.getElementById('monzo-flex-badge');
            var flexVal = document.getElementById('monzo-flex-val');
            var receivedPill = document.getElementById('monzo-received-pill');

            if (data.hasActiveWebhooks) {
                if (card) card.style.borderColor = 'rgba(52, 211, 153, 0.3)';
                if (badge) {
                    badge.style.color = 'var(--success, #10b981)';
                    badge.innerHTML = '<i class="fas fa-satellite-dish" style="color: var(--success); margin-right: 4px;"></i> Live Webhooks Active on Monzo';
                }

                if (urlVal && data.webhookUrl) {
                    urlVal.textContent = data.webhookUrl;
                    if (urlWrap) urlWrap.style.display = 'block';
                }

                if (webhooksWrap) webhooksWrap.style.display = 'block';
                if (debitBadge && debitVal) {
                    if (data.webhookDebitId) {
                        debitVal.textContent = data.webhookDebitId;
                        debitBadge.style.display = 'inline-block';
                    } else {
                        debitBadge.style.display = 'none';
                    }
                }
                if (flexBadge && flexVal) {
                    if (data.webhookFlexId) {
                        flexVal.textContent = data.webhookFlexId;
                        flexBadge.style.display = 'inline-block';
                    } else {
                        flexBadge.style.display = 'none';
                    }
                }

                if (receivedPill && data.lastReceivedText) {
                    receivedPill.innerHTML = '<i class="fas fa-clock"></i> ' + data.lastReceivedText;
                    receivedPill.style.color = 'var(--success, #10b981)';
                    receivedPill.style.background = 'var(--success-light, rgba(16, 185, 129, 0.15))';
                    receivedPill.style.borderColor = 'rgba(52, 211, 153, 0.3)';
                }

                if (alertBox) {
                    if (!data.lastReceivedAt) {
                        alertBox.innerHTML = '<div style="margin-top: 8px; color: var(--text-muted); font-size: 0.82rem; background: var(--card-bg); border: 1px solid rgba(52, 211, 153, 0.25); border-radius: 6px; padding: 8px 12px; line-height: 1.5;">' +
                            '<strong style="color: var(--text-main);"><i class="fas fa-check-circle" style="color: var(--success);"></i> Webhooks exist on Monzo:</strong> ' +
                            'Your webhooks are registered on Monzo and listening. Monzo has not pushed any transaction events yet. When you spend on your card or make a transaction, events will stream in automatically.' +
                            '</div>';
                    } else {
                        alertBox.innerHTML = '';
                    }
                }
            }
        })
        .catch(function (err) {
            if (refreshIcon) {
                refreshIcon.classList.remove('fa-spin');
            }
            console.warn('Could not refresh Monzo webhook status:', err);
        });
    }

    document.addEventListener('DOMContentLoaded', function () {
        var refreshBtn = document.getElementById('btn-refresh-webhooks');
        if (refreshBtn) {
            refreshBtn.addEventListener('click', function () {
                checkLiveWebhooks(false);
            });
            // Automatically query on page load to discover newly registered webhooks
            checkLiveWebhooks(true);
        }
    });
})();
