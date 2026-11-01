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

    function verifyMonzoAndTbl(e) {
        if (e) e.preventDefault();
        var form = document.getElementById('form-verify-monzo-tbl');
        var icon = document.getElementById('verify-monzo-icon');
        var panel = document.getElementById('monzo-verification-panel');
        if (!form || !panel) return;

        if (icon) {
            icon.className = 'fas fa-circle-notch fa-spin';
        }
        panel.style.display = 'block';
        panel.style.borderColor = 'var(--border-color)';
        panel.innerHTML = '<div style="font-size: 0.85rem; color: var(--text-muted);"><i class="fas fa-circle-notch fa-spin"></i> Verifying Monzo OAuth client &amp; querying live TBL account balances...</div>';

        var formData = new FormData(form);
        fetch('/bank/monzo/verify', {
            method: 'POST',
            headers: {
                'Accept': 'application/json'
            },
            body: formData
        })
        .then(function (res) {
            if (!res.ok) throw new Error('HTTP ' + res.status);
            return res.json();
        })
        .then(function (data) {
            if (icon) {
                icon.className = 'fas fa-check-double';
            }
            var isOk = data.apiPermissionsGranted;
            panel.style.borderColor = isOk ? 'rgba(52, 211, 153, 0.45)' : 'rgba(234, 179, 8, 0.5)';

            var html = '<div style="display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 8px;">';
            html += '<strong style="font-size: 0.92rem; color: ' + (isOk ? 'var(--success, #10b981)' : '#eab308') + ';">';
            html += '<i class="fas ' + (isOk ? 'fa-check-circle' : 'fa-exclamation-triangle') + '"></i> ';
            html += isOk ? 'OAuth Client Verified &amp; TBL Accounts Active' : (data.awaitingInAppApproval ? 'Awaiting Monzo Mobile App Approval (SCA)' : 'OAuth Verification Status');
            html += '</strong>';
            html += '<span style="font-family: monospace; font-size: 0.78rem; color: var(--text-muted);">Client: ' + (data.clientId || 'N/A') + ' | User: ' + (data.monzoUserId || 'N/A') + '</span>';
            html += '</div>';

            html += '<div style="font-size: 0.84rem; color: var(--text-main); margin-bottom: 10px;">' + (data.summaryMessage || '') + '</div>';

            if (data.tblAccounts && data.tblAccounts.length > 0) {
                html += '<div style="display: grid; gap: 8px;">';
                for (var i = 0; i < data.tblAccounts.length; i++) {
                    var t = data.tblAccounts[i];
                    var rowBorder = t.working ? 'rgba(52, 211, 153, 0.35)' : 'var(--border-color)';
                    var badgeBg = t.working ? 'var(--success-light, rgba(16, 185, 129, 0.15))' : 'rgba(234, 179, 8, 0.15)';
                    var badgeColor = t.working ? 'var(--success, #10b981)' : '#eab308';
                    var badgeLabel = t.working ? 'TBL Working' : (t.matchedToMonzo ? t.ledgerMode + ' Mode' : 'Non-Monzo');

                    html += '<div style="background: var(--card-bg); border: 1px solid ' + rowBorder + '; border-radius: 6px; padding: 8px 12px; display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px;">';
                    html += '<div>';
                    html += '<strong style="font-size: 0.86rem; color: var(--text-main);">' + t.monmonAccountName + '</strong> ';
                    html += '<span style="font-size: 0.76rem; color: var(--text-muted);">(' + t.accountType + ' • ' + t.ledgerMode + ')</span>';
                    html += '<div style="font-size: 0.79rem; color: var(--text-muted); margin-top: 2px;">' + t.statusDetail + '</div>';
                    html += '</div>';
                    html += '<div style="display: flex; align-items: center; gap: 8px;">';
                    if (t.availableBalance !== null && t.availableBalance !== undefined) {
                        html += '<strong style="font-family: monospace; font-size: 0.9rem; color: var(--text-main);">£' + Number(t.availableBalance).toFixed(2) + '</strong>';
                    }
                    html += '<span style="background: ' + badgeBg + '; color: ' + badgeColor + '; border: 1px solid ' + rowBorder + '; padding: 2px 8px; border-radius: 9999px; font-size: 0.75rem; font-weight: 700;">' + badgeLabel + '</span>';
                    html += '</div>';
                    html += '</div>';
                }
                html += '</div>';
            }

            panel.innerHTML = html;
            checkLiveWebhooks(true);
        })
        .catch(function (err) {
            if (icon) {
                icon.className = 'fas fa-check-double';
            }
            panel.style.borderColor = 'var(--danger, #ef4444)';
            panel.innerHTML = '<div style="font-size: 0.84rem; color: var(--danger, #ef4444);"><i class="fas fa-times-circle"></i> Verification request failed: ' + err.message + '</div>';
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
        var verifyForm = document.getElementById('form-verify-monzo-tbl');
        if (verifyForm) {
            verifyForm.addEventListener('submit', verifyMonzoAndTbl);
        }
    });
})();
