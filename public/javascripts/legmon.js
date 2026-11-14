// LegMon Frontend Status & Log Viewer (Plain JavaScript)
(function() {
    'use strict';

    function initLegmon() {
        checkStatus();
        initUploadForm();
        // Check if upload was recently triggered or pending statements exist
        var pendingCard = document.querySelector('[data-has-pending-statement="true"]');
        if (pendingCard) {
            // Check status immediately
            checkStatus();
        }
    }

    var pollingIntervalId = null;
    var pollingTimeoutId = null;

    function getAvailableAccounts() {
        if (window.availableAccounts && window.availableAccounts.length) {
            return window.availableAccounts;
        }
        var jsonEl = document.getElementById('accounts-json');
        if (jsonEl && jsonEl.textContent) {
            try {
                window.availableAccounts = JSON.parse(jsonEl.textContent);
                return window.availableAccounts;
            } catch (e) {}
        }
        var select = document.getElementById('legmon-target-account') || document.querySelector('select[name="accountId"]');
        if (select) {
            var list = [];
            for (var i = 0; i < select.options.length; i++) {
                var opt = select.options[i];
                if (opt.value) {
                    list.push({ id: parseInt(opt.value, 10), name: opt.textContent.trim(), accountType: '', ledgerMode: '' });
                }
            }
            window.availableAccounts = list;
            return list;
        }
        return [];
    }

    function renderPendingStatementCard(stmt) {
        var container = document.getElementById('pending-statements-container');
        if (!container) return;

        var existingCard = document.getElementById('pending-statement-card-' + stmt.id);
        if (existingCard) return;

        var isTxList = !!stmt.isTxList;
        var accounts = getAvailableAccounts();

        var borderCol = isTxList ? 'var(--info)' : 'var(--warning)';
        var badgeBg = isTxList ? 'rgba(59,130,246,0.15)' : 'var(--warning-light)';
        var badgeCol = isTxList ? 'var(--info)' : 'var(--warning)';
        var badgeIcon = isTxList ? 'fa-list-ol' : 'fa-file-invoice-dollar';
        var badgeText = isTxList ? 'Transaction List Export Detected' : 'Statement Processed — Action Required';
        var btnBg = isTxList ? 'var(--primary)' : 'var(--danger)';
        var btnIcon = isTxList ? 'fa-calculator' : 'fa-chart-line';
        var btnText = isTxList ? 'Review &amp; Reconstruct Balances' : 'Dangerously Overwrite ABL Balances &amp; Import';

        var paidInHtml = (stmt.totalPaidIn && stmt.totalPaidIn > 0)
            ? ' &bull; Paid In: <strong style="color: var(--success);">£' + stmt.totalPaidIn.toFixed(2) + '</strong>'
            : '';
        var paidOutHtml = (stmt.totalPaidOut && stmt.totalPaidOut > 0)
            ? ' &bull; Paid Out: <strong style="color: var(--danger);">£' + stmt.totalPaidOut.toFixed(2) + '</strong>'
            : '';
        var closingHtml = (!isTxList && stmt.closingBalance != null)
            ? ' &bull; Closing Balance: <strong style="color: var(--text-main);">£' + stmt.closingBalance.toFixed(2) + '</strong>'
            : '';
        var openingHtml = (!isTxList && stmt.openingBalance != null)
            ? ' &bull; Opening Balance: <strong style="color: var(--text-main);">£' + stmt.openingBalance.toFixed(2) + '</strong>'
            : '';

        var txListNoticeHtml = isTxList
            ? '<div style="margin-top: 10px; padding: 10px 14px; background: rgba(59, 130, 246, 0.1); border: 1px solid rgba(59, 130, 246, 0.3); border-radius: 8px; font-size: 0.85rem; color: var(--text-main); line-height: 1.5;">' +
              '<i class="fas fa-calculator" style="color: var(--primary); margin-right: 6px;"></i>' +
              '<strong>Discrete Delta Sequence:</strong> This document contains a transaction list without official balance totals. Enter an <strong>Anchor Balance</strong> below (or <a href="/myBalances" style="color: var(--primary); text-decoration: underline; font-weight: 700;">set one on the Balances screen</a>) to mathematically reconstruct full opening, closing, and running balances via forward/backward delta accumulation. If no balance is provided or found, confirming will safely import all transactions and automatically steer you to the <strong>Balances</strong> screen to anchor your history.' +
              '</div>'
            : '';

        var optionsHtml = '<option value="">-- Choose Account to Match --</option>';
        var bankLower = (stmt.bankName || '').toLowerCase();
        for (var i = 0; i < accounts.length; i++) {
            var acc = accounts[i];
            var accName = acc.name || '';
            var accLower = accName.toLowerCase();
            var isSelected = (stmt.matchedAccountId && stmt.matchedAccountId === acc.id) ||
                             (bankLower && accLower && (accLower.indexOf(bankLower) !== -1 || bankLower.indexOf(accLower) !== -1));
            var typeInfo = acc.accountType ? (' (' + acc.accountType + ' - ' + (acc.ledgerMode || '') + ')') : '';
            optionsHtml += '<option value="' + acc.id + '"' + (isSelected ? ' selected' : '') + '>' +
                           escapeHtml(accName) + escapeHtml(typeInfo) + '</option>';
        }

        var anchorInputsHtml = isTxList
            ? '<div style="display: flex; gap: 8px; flex-wrap: wrap; flex: 2 1 300px;">' +
              '<input type="number" step="0.01" id="stmt-anchor-bal-' + stmt.id + '" name="anchorBalance" placeholder="Anchor Bal (£) e.g. 845.20" class="w3-input w3-border w3-round" style="flex: 1 1 140px; background: var(--input-bg); color: var(--text-main); border-color: var(--input-border); padding: 10px 12px; font-size: 0.9rem;">' +
              '<input type="date" id="stmt-anchor-date-' + stmt.id + '" name="anchorDate" value="' + escapeHtml(stmt.endDate || '') + '" class="w3-input w3-border w3-round" style="flex: 1 1 140px; background: var(--input-bg); color: var(--text-main); border-color: var(--input-border); padding: 10px 12px; font-size: 0.9rem;">' +
              '</div>'
            : '';

        var cardHtml =
            '<div data-has-pending-statement="true" class="pending-statement-card" id="pending-statement-card-' + stmt.id + '" style="background: var(--card-bg-subtle); border: 2px solid ' + borderCol + '; border-radius: 12px; padding: 18px; margin-bottom: 20px; box-shadow: 0 4px 14px rgba(245, 158, 11, 0.15);">' +
                '<div style="display: flex; justify-content: space-between; align-items: flex-start; flex-wrap: wrap; gap: 10px;">' +
                    '<div>' +
                        '<div style="display: inline-flex; align-items: center; gap: 6px; background: ' + badgeBg + '; color: ' + badgeCol + '; padding: 4px 10px; border-radius: 9999px; font-weight: 700; font-size: 0.78rem; text-transform: uppercase; border: 1px solid rgba(251, 191, 36, 0.4);">' +
                            '<i class="fas ' + badgeIcon + '"></i> ' + badgeText +
                        '</div>' +
                        '<h3 style="margin: 8px 0 4px 0; font-size: 1.25rem; font-weight: 800; color: var(--text-main);">' +
                            'Extracted Bank: <span style="color: ' + badgeCol + '; text-decoration: underline;">' + escapeHtml(stmt.bankName) + '</span>' +
                        '</h3>' +
                        '<p style="margin: 0; color: var(--text-muted); font-size: 0.9rem; line-height: 1.5;">' +
                            'Period: <strong style="color: var(--text-main);">' + escapeHtml(stmt.startDate) + '</strong> to <strong style="color: var(--text-main);">' + escapeHtml(stmt.endDate) + '</strong> &bull; ' +
                            'Transactions: <strong style="color: var(--text-main);">' + (stmt.transactionCount || 0) + '</strong>' +
                            paidInHtml + paidOutHtml + closingHtml + openingHtml +
                        '</p>' +
                        txListNoticeHtml +
                    '</div>' +
                '</div>' +
                '<div style="margin-top: 14px; background: var(--card-bg); border: 1px solid var(--border-color); border-radius: 8px; padding: 14px;">' +
                    '<form id="stmt-form-' + stmt.id + '" action="/bank/statement/' + stmt.id + '/confirm" method="POST" style="margin: 0;">' +
                        '<label style="display: block; font-weight: 700; font-size: 0.88rem; color: var(--text-main); margin-bottom: 6px;">' +
                            'Match to MonMon Account:' +
                        '</label>' +
                        '<div style="display: flex; gap: 10px; flex-wrap: wrap; align-items: stretch;">' +
                            '<select id="stmt-account-' + stmt.id + '" name="accountId" class="w3-select w3-border w3-round" style="flex: 1 1 240px; min-width: 220px; background: var(--input-bg); color: var(--text-main); border-color: var(--input-border); padding: 10px 12px; font-size: 0.9rem;" required>' +
                                optionsHtml +
                            '</select>' +
                            anchorInputsHtml +
                            '<button type="button" class="w3-button w3-round btn-mobile-full" ' +
                                    'style="background: ' + btnBg + '; color: #ffffff; font-weight: 700; padding: 10px 18px; line-height: 1.35; white-space: normal; text-align: center; border: 1px solid rgba(255,255,255,0.2);" ' +
                                    'onclick="window.openStatementPreview(' + stmt.id + ', ' + isTxList + ')">' +
                                '<i class="fas ' + btnIcon + '" style="margin-right: 4px;"></i> ' + btnText +
                            '</button>' +
                        '</div>' +
                    '</form>' +
                    '<div style="margin-top: 10px; text-align: right;">' +
                        '<form action="/bank/statement/' + stmt.id + '/discard" method="POST" style="display: inline;" onsubmit="return confirm(\'Discard this extracted statement?\');">' +
                            '<button type="submit" class="w3-button w3-small" style="background: none; border: none; padding: 4px 8px; color: var(--text-muted); cursor: pointer; text-decoration: underline;">' +
                                '<i class="fas fa-trash-alt"></i> Discard Statement' +
                            '</button>' +
                        '</form>' +
                    '</div>' +
                '</div>' +
            '</div>';

        container.insertAdjacentHTML('afterbegin', cardHtml);
    }

    function initUploadForm() {
        var form = document.getElementById('legmon-upload-form') || document.querySelector('#statement-upload form');
        if (!form) return;

        form.addEventListener('submit', function(e) {
            var fileInput = document.getElementById('legmon-pdf-input') || form.querySelector('input[type="file"]');
            if (!fileInput || !fileInput.files || !fileInput.files[0]) {
                return;
            }

            e.preventDefault();

            var processingContainer = document.getElementById('legmon-processing-container');
            var processingText = document.getElementById('legmon-processing-text');
            var errorBanner = document.getElementById('legmon-error-banner');
            var errorText = document.getElementById('legmon-error-text');
            var successBanner = document.getElementById('legmon-success-banner');
            var submitBtn = document.getElementById('legmon-upload-btn') || form.querySelector('button[type="submit"]');

            if (errorBanner) errorBanner.style.display = 'none';
            if (successBanner) successBanner.style.display = 'none';
            if (processingContainer) {
                processingContainer.style.display = 'flex';
                if (processingText) processingText.textContent = 'Uploading statement PDF to LegMon...';
            }
            if (submitBtn) {
                submitBtn.disabled = true;
                submitBtn.innerHTML = '<i class="fas fa-circle-notch fa-spin"></i> Processing...';
            }

            var existingIds = new Set();
            document.querySelectorAll('.pending-statement-card').forEach(function(el) {
                var cardId = el.id.replace('pending-statement-card-', '');
                if (cardId) existingIds.add(cardId);
            });

            var formData = new FormData(form);

            fetch(form.action || '/bank/statement/upload', {
                method: 'POST',
                body: formData,
                headers: {
                    'Accept': 'application/json',
                    'X-Requested-With': 'XMLHttpRequest'
                }
            })
            .then(function(res) {
                if (!res.ok) {
                    return res.text().then(function(t) {
                        var errMsg = 'Upload failed';
                        try {
                            var p = JSON.parse(t);
                            errMsg = p.error || p.message || errMsg;
                        } catch (ex) {
                            errMsg = t || errMsg;
                        }
                        throw new Error(errMsg);
                    });
                }
                return res.json().catch(function() { return { status: 'processing' }; });
            })
            .then(function(data) {
                if (processingText) {
                    processingText.textContent = 'Statement accepted. LegMon is running OCR & analyzing transactions...';
                }
                startPollingForStatement(existingIds);
            })
            .catch(function(err) {
                if (processingContainer) processingContainer.style.display = 'none';
                if (errorBanner) {
                    errorBanner.style.display = 'flex';
                    if (errorText) errorText.textContent = err.message || 'Statement upload failed.';
                }
                if (submitBtn) {
                    submitBtn.disabled = false;
                    submitBtn.innerHTML = '<i class="fas fa-upload"></i> Upload &amp; Run LegMon';
                }
            });
        });
    }

    function startPollingForStatement(existingIds) {
        if (pollingIntervalId) clearInterval(pollingIntervalId);
        if (pollingTimeoutId) clearTimeout(pollingTimeoutId);

        var processingContainer = document.getElementById('legmon-processing-container');
        var errorBanner = document.getElementById('legmon-error-banner');
        var errorText = document.getElementById('legmon-error-text');
        var successBanner = document.getElementById('legmon-success-banner');
        var submitBtn = document.getElementById('legmon-upload-btn') || document.querySelector('#statement-upload button[type="submit"]');
        var fileInput = document.getElementById('legmon-pdf-input') || document.querySelector('#statement-upload input[type="file"]');

        var attempts = 0;
        var maxAttempts = 40; // 60 seconds (1.5s interval)

        pollingTimeoutId = setTimeout(function() {
            if (pollingIntervalId) clearInterval(pollingIntervalId);
            if (processingContainer) processingContainer.style.display = 'none';
            if (errorBanner) {
                errorBanner.style.display = 'flex';
                if (errorText) errorText.textContent = 'Statement extraction timed out. Please check LegMon Service Logs.';
            }
            if (submitBtn) {
                submitBtn.disabled = false;
                submitBtn.innerHTML = '<i class="fas fa-upload"></i> Upload &amp; Run LegMon';
            }
        }, 60000);

        pollingIntervalId = setInterval(function() {
            attempts++;
            if (attempts > maxAttempts) {
                clearInterval(pollingIntervalId);
                return;
            }

            fetch('/api/bank/statement/pending')
                .then(function(res) {
                    if (!res.ok) throw new Error('HTTP ' + res.status);
                    return res.json();
                })
                .then(function(data) {
                    var list = data.pendingStatements || [];
                    var newStmt = null;
                    for (var i = 0; i < list.length; i++) {
                        var stmt = list[i];
                        if (!existingIds.has(String(stmt.id)) && stmt.status === 'PENDING') {
                            newStmt = stmt;
                            break;
                        }
                    }

                    if (newStmt) {
                        clearInterval(pollingIntervalId);
                        clearTimeout(pollingTimeoutId);

                        if (processingContainer) processingContainer.style.display = 'none';
                        if (successBanner) successBanner.style.display = 'flex';
                        if (submitBtn) {
                            submitBtn.disabled = false;
                            submitBtn.innerHTML = '<i class="fas fa-upload"></i> Upload &amp; Run LegMon';
                        }
                        if (fileInput) fileInput.value = '';

                        renderPendingStatementCard(newStmt);
                        checkStatus();
                    } else if (attempts % 3 === 0) {
                        fetch('/api/legmon/logs')
                            .then(function(r) { return r.json(); })
                            .then(function(logData) {
                                if (logData.hasError && logData.lastError) {
                                    clearInterval(pollingIntervalId);
                                    clearTimeout(pollingTimeoutId);

                                    if (processingContainer) processingContainer.style.display = 'none';
                                    if (errorBanner) {
                                        errorBanner.style.display = 'flex';
                                        if (errorText) errorText.textContent = 'LegMon Error: ' + logData.lastError;
                                    }
                                    if (submitBtn) {
                                        submitBtn.disabled = false;
                                        submitBtn.innerHTML = '<i class="fas fa-upload"></i> Upload &amp; Run LegMon';
                                    }
                                }
                            })
                            .catch(function() {});
                    }
                })
                .catch(function(err) {});
        }, 1500);
    }

    function checkStatus() {
        var badge = document.getElementById('legmon-status-badge');
        if (!badge) return;

        fetch('/api/legmon/status')
            .then(function(res) { return res.json(); })
            .then(function(data) {
                if (data.status === 'ok') {
                    badge.style.background = '#dcfce7';
                    badge.style.color = '#15803d';
                    badge.innerHTML = '<i class="fas fa-check-circle"></i> LegMon Online';
                } else if (data.status === 'warning') {
                    badge.style.background = '#fef3c7';
                    badge.style.color = '#b45309';
                    badge.innerHTML = '<i class="fas fa-exclamation-circle"></i> LegMon Warning';
                } else {
                    badge.style.background = '#fee2e2';
                    badge.style.color = '#b91c1c';
                    badge.innerHTML = '<i class="fas fa-times-circle"></i> LegMon Offline';
                }
            })
            .catch(function(err) {
                badge.style.background = '#fee2e2';
                badge.style.color = '#b91c1c';
                badge.innerHTML = '<i class="fas fa-times-circle"></i> LegMon Offline';
            });
    }

    window.toggleLegmonLogs = function() {
        var container = document.getElementById('legmon-logs-container');
        if (!container) return;
        if (container.style.display === 'none' || container.style.display === '') {
            container.style.display = 'block';
            window.fetchLegmonLogs();
        } else {
            container.style.display = 'none';
        }
    };

    window.fetchLegmonLogs = function() {
        var pre = document.getElementById('legmon-logs-output');
        var errBanner = document.getElementById('legmon-error-banner');
        var errText = document.getElementById('legmon-error-text');
        if (!pre) return;

        pre.textContent = 'Fetching LegMon service logs...';

        fetch('/api/legmon/logs')
            .then(function(res) { return res.json(); })
            .then(function(data) {
                if (data.hasError && data.lastError) {
                    if (errBanner && errText) {
                        errBanner.style.display = 'block';
                        errText.textContent = data.lastError;
                    }
                } else if (errBanner) {
                    errBanner.style.display = 'none';
                }

                if (data.logs && data.logs.length > 0) {
                    var lines = data.logs.map(function(item) {
                        var color = '#94a3b8';
                        if (item.level === 'ERROR') color = '#f87171';
                        else if (item.level === 'WARN') color = '#fbbf24';
                        else if (item.level === 'INFO') color = '#e2e8f0';
                        return '[' + item.time.substring(11, 19) + '] ' + item.level + ' - ' + item.msg;
                    });
                    pre.textContent = lines.join('\n');
                    pre.scrollTop = pre.scrollHeight;
                } else {
                    pre.textContent = data.error ? ('Service reported: ' + data.error) : 'No logs recorded yet.';
                }
            })
            .catch(function(err) {
                pre.textContent = 'Could not retrieve LegMon logs: ' + err.message;
            });
    };

    function initTestIngestToggle() {
        var toggleBtn = document.getElementById('toggle-test-btn');
        var testPanel = document.getElementById('test-ingest-panel');
        if (toggleBtn && testPanel) {
            toggleBtn.addEventListener('click', function() {
                var isHidden = testPanel.style.display === 'none' || testPanel.style.display === '';
                testPanel.style.display = isHidden ? 'flex' : 'none';
                toggleBtn.style.color = isHidden ? 'var(--primary)' : 'var(--text-muted)';
                toggleBtn.style.borderColor = isHidden ? 'var(--primary)' : 'var(--border-color)';
            });
        }
    }

    var currentPreviewData = null;
    var currentRenderedPoints = [];

    function escapeHtml(str) {
        if (!str) return '';
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#039;');
    }

    window.openStatementPreview = function(stmtId, isTxList) {
        var accSelect = document.getElementById('stmt-account-' + stmtId);
        if (!accSelect || !accSelect.value) {
            alert('Please select an account to match with this statement first.');
            if (accSelect) accSelect.focus();
            return;
        }

        var accountId = accSelect.value;
        var anchorBalInput = document.getElementById('stmt-anchor-bal-' + stmtId);
        var anchorDateInput = document.getElementById('stmt-anchor-date-' + stmtId);
        var anchorBal = anchorBalInput ? anchorBalInput.value : '';
        var anchorDate = anchorDateInput ? anchorDateInput.value : '';

        var modal = document.getElementById('statement-preview-modal');
        var loading = document.getElementById('preview-modal-loading');
        var content = document.getElementById('preview-modal-content');
        if (!modal) return;

        modal.style.display = 'block';
        if (loading) {
            loading.style.display = 'block';
            loading.innerHTML = '<i class="fas fa-circle-notch fa-spin fa-2x" style="color: var(--primary, #6366f1); margin-bottom: 12px;"></i><p style="margin: 0; font-size: 0.95rem;">Reconstructing balance curve and calculating anchor deltas...</p>';
        }
        if (content) content.style.display = 'none';

        var query = '?accountId=' + encodeURIComponent(accountId);
        if (anchorBal) query += '&anchorBalance=' + encodeURIComponent(anchorBal);
        if (anchorDate) query += '&anchorDate=' + encodeURIComponent(anchorDate);

        fetch('/api/bank/statement/' + stmtId + '/preview' + query)
            .then(function(res) {
                if (!res.ok) throw new Error('HTTP ' + res.status);
                return res.json();
            })
            .then(function(data) {
                if (data.status !== 'ok') {
                    throw new Error(data.error || 'Failed to load preview');
                }
                currentPreviewData = data;
                renderPreviewModal(stmtId, data);
            })
            .catch(function(err) {
                if (loading) {
                    loading.innerHTML = '<div style="color: var(--danger);"><i class="fas fa-exclamation-circle fa-2x"></i><p style="margin-top: 10px;">Failed to load statement preview: ' + escapeHtml(err.message) + '</p><button type="button" class="w3-button w3-round w3-small" onclick="window.closeStatementPreviewModal()">Close</button></div>';
                }
            });
    };

    window.closeStatementPreviewModal = function() {
        var modal = document.getElementById('statement-preview-modal');
        if (modal) modal.style.display = 'none';
        currentPreviewData = null;
    };

    window.confirmStatementOverwrite = function(stmtId) {
        var form = document.getElementById('stmt-form-' + stmtId);
        if (form) {
            var btn = document.getElementById('btn-final-confirm-overwrite');
            if (btn) {
                btn.disabled = true;
                btn.innerHTML = '<i class="fas fa-circle-notch fa-spin"></i> Overwriting & Importing...';
            }
            form.submit();
        }
    };

    function renderPreviewModal(stmtId, data) {
        var loading = document.getElementById('preview-modal-loading');
        var content = document.getElementById('preview-modal-content');
        if (loading) loading.style.display = 'none';
        if (content) content.style.display = 'block';

        var accEl = document.getElementById('preview-account-name');
        if (accEl) accEl.textContent = data.accountName || 'Selected Account';

        var dateEl = document.getElementById('preview-date-range');
        if (dateEl) dateEl.textContent = (data.startDate || '') + ' → ' + (data.endDate || '');

        var anchorEl = document.getElementById('preview-anchor-bal');
        if (anchorEl) {
            if (data.hasAnchor) {
                anchorEl.innerHTML = '£' + (data.anchorBalance != null ? data.anchorBalance.toFixed(2) : '0.00') + ' <span style="font-size:0.75rem; color:var(--text-muted); font-weight:400;">(' + (data.anchorDateStr || '') + ')</span>';
            } else {
                anchorEl.innerHTML = '<span style="color:var(--warning);">None (Transactions Only)</span>';
            }
        }

        var netEl = document.getElementById('preview-net-delta');
        if (netEl) {
            var net = data.netDelta != null ? data.netDelta : 0;
            var sign = net >= 0 ? '+' : '';
            netEl.textContent = sign + '£' + net.toFixed(2) + ' (' + (data.transactionCount || 0) + ' txs)';
        }

        var warnEl = document.getElementById('preview-overwrite-warning-text');
        if (warnEl) {
            if (data.existingBalancesToOverwrite > 0) {
                warnEl.innerHTML = '⚠️ <strong>Danger:</strong> This will delete <strong>' + data.existingBalancesToOverwrite + ' existing balance records</strong> for <em>' + escapeHtml(data.accountName) + '</em> between ' + data.startDate + ' and ' + data.endDate + ' and overwrite them with ' + (data.points ? data.points.length : 1) + ' verified balance entries, plus import ' + data.transactionCount + ' transactions.';
            } else {
                warnEl.innerHTML = 'ℹ️ <strong>Fresh History:</strong> No existing balance entries in this range will be deleted. This will insert the verified balance trajectory and import ' + data.transactionCount + ' transactions into ' + escapeHtml(data.accountName) + '.';
            }
        }

        var confirmBtn = document.getElementById('btn-final-confirm-overwrite');
        if (confirmBtn) {
            confirmBtn.onclick = function() {
                window.confirmStatementOverwrite(stmtId);
            };
        }

        setTimeout(function() {
            drawPreviewChart(data);
            initCanvasTooltip();
        }, 50);
        requestAnimationFrame(function() {
            drawPreviewChart(data);
        });
        setTimeout(function() {
            drawPreviewChart(data);
        }, 200);
    }

    function drawPreviewChart(data) {
        var canvas = document.getElementById('statement-preview-canvas');
        if (!canvas) return;
        var ctx = canvas.getContext('2d');
        var dpr = window.devicePixelRatio || 1;
        var rect = canvas.getBoundingClientRect();
        var width = rect.width || canvas.clientWidth || (canvas.parentElement ? canvas.parentElement.clientWidth : 0) || 600;
        var height = rect.height || canvas.clientHeight || 260;
        if (width <= 0) width = 600;
        if (height <= 0) height = 260;

        canvas.width = width * dpr;
        canvas.height = height * dpr;
        ctx.scale(dpr, dpr);

        ctx.clearRect(0, 0, width, height);
        currentRenderedPoints = [];

        var rawPoints = data.points || [];
        if (!rawPoints.length) {
            ctx.fillStyle = '#94a3b8';
            ctx.font = '14px system-ui, -apple-system, sans-serif';
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            ctx.fillText('No balance points to reconstruct without an anchor balance', width / 2, height / 2);
            return;
        }

        // Ensure points are sorted chronologically
        var points = rawPoints.slice().sort(function(a, b) {
            return (a.timestamp || 0) - (b.timestamp || 0);
        });

        var padding = { top: 30, right: 35, bottom: 40, left: 65 };
        var vals = points.map(function(p) { return p.balance; });
        var minVal = Math.min.apply(null, vals);
        var maxVal = Math.max.apply(null, vals);
        if (minVal === maxVal) {
            minVal = Math.max(0, minVal - 50);
            maxVal = maxVal + 50;
        }
        var span = maxVal - minVal;
        minVal = Math.min(0, minVal - span * 0.1);
        maxVal = maxVal + span * 0.15;
        if (maxVal <= minVal) maxVal = minVal + 10;

        var tMin = points[0].timestamp || 0;
        var tMax = points[points.length - 1].timestamp || 0;
        if (tMin === tMax || tMax <= tMin) {
            tMin -= 86400;
            tMax = tMin + 86400 * 2;
        }

        function getX(t) { return padding.left + ((t - tMin) / (tMax - tMin)) * (width - padding.left - padding.right); }
        function getY(v) { return height - padding.bottom - ((v - minVal) / (maxVal - minVal)) * (height - padding.top - padding.bottom); }

        // Grid lines & Y axis
        var ySteps = 4;
        ctx.strokeStyle = 'rgba(148, 163, 184, 0.15)';
        ctx.fillStyle = '#94a3b8';
        ctx.font = '11px system-ui, -apple-system, sans-serif';
        ctx.textAlign = 'right';
        ctx.textBaseline = 'middle';
        ctx.setLineDash([3, 3]);

        for (var i = 0; i <= ySteps; i++) {
            var val = minVal + ((maxVal - minVal) / ySteps) * i;
            var yPos = getY(val);
            ctx.beginPath();
            ctx.moveTo(padding.left, yPos);
            ctx.lineTo(width - padding.right, yPos);
            ctx.stroke();
            ctx.fillText('£' + Math.round(val), padding.left - 8, yPos);
        }
        ctx.setLineDash([]);

        // Area gradient fill
        var gradient = ctx.createLinearGradient(0, padding.top, 0, height - padding.bottom);
        gradient.addColorStop(0, 'rgba(99, 102, 241, 0.35)');
        gradient.addColorStop(1, 'rgba(99, 102, 241, 0.0)');

        ctx.beginPath();
        points.forEach(function(p, idx) {
            var px = getX(p.timestamp);
            var py = getY(p.balance);
            if (idx === 0) ctx.moveTo(px, py);
            else ctx.lineTo(px, py);
        });
        var lastPx = getX(points[points.length - 1].timestamp);
        var firstPx = getX(points[0].timestamp);
        var basePy = getY(minVal);
        ctx.lineTo(lastPx, basePy);
        ctx.lineTo(firstPx, basePy);
        ctx.closePath();
        ctx.fillStyle = gradient;
        ctx.fill();

        // Line stroke
        ctx.beginPath();
        points.forEach(function(p, idx) {
            var px = getX(p.timestamp);
            var py = getY(p.balance);
            if (idx === 0) ctx.moveTo(px, py);
            else ctx.lineTo(px, py);
        });
        ctx.strokeStyle = '#6366f1';
        ctx.lineWidth = 2.5;
        ctx.stroke();

        // Points
        points.forEach(function(p) {
            var px = getX(p.timestamp);
            var py = getY(p.balance);
            currentRenderedPoints.push({
                x: px,
                y: py,
                point: p
            });

            if (p.isAnchor) {
                // Highlight anchor balance
                ctx.beginPath();
                ctx.arc(px, py, 9, 0, Math.PI * 2);
                ctx.fillStyle = 'rgba(16, 185, 129, 0.3)';
                ctx.fill();

                ctx.beginPath();
                ctx.arc(px, py, 6, 0, Math.PI * 2);
                ctx.fillStyle = '#10b981';
                ctx.fill();
                ctx.strokeStyle = '#ffffff';
                ctx.lineWidth = 2;
                ctx.stroke();

                ctx.font = 'bold 10px system-ui, -apple-system, sans-serif';
                ctx.fillStyle = '#10b981';
                ctx.textAlign = 'center';
                ctx.fillText('⚓ Anchor', px, py - 14);
            } else {
                ctx.beginPath();
                ctx.arc(px, py, 4, 0, Math.PI * 2);
                ctx.fillStyle = '#38bdf8';
                ctx.fill();
                ctx.strokeStyle = '#1e293b';
                ctx.lineWidth = 1.5;
                ctx.stroke();
            }
        });

        // X Axis Date labels
        ctx.font = '10px system-ui, -apple-system, sans-serif';
        ctx.fillStyle = '#94a3b8';
        ctx.textBaseline = 'top';

        if (points.length > 0) {
            ctx.textAlign = 'left';
            ctx.fillText(points[0].dateStr, padding.left, height - padding.bottom + 8);

            ctx.textAlign = 'right';
            ctx.fillText(points[points.length - 1].dateStr, width - padding.right, height - padding.bottom + 8);

            if (points.length > 2) {
                var midIdx = Math.floor(points.length / 2);
                var midPx = getX(points[midIdx].timestamp);
                ctx.textAlign = 'center';
                ctx.fillText(points[midIdx].dateStr, midPx, height - padding.bottom + 8);
            }
        }
    }

    function initCanvasTooltip() {
        var canvas = document.getElementById('statement-preview-canvas');
        var tooltip = document.getElementById('statement-preview-tooltip');
        if (!canvas || !tooltip) return;

        function handlePointer(clientX, clientY) {
            var rect = canvas.getBoundingClientRect();
            var mouseX = clientX - rect.left;
            var mouseY = clientY - rect.top;

            var hit = null;
            var minDist = 24;
            for (var i = 0; i < currentRenderedPoints.length; i++) {
                var p = currentRenderedPoints[i];
                var dist = Math.hypot(p.x - mouseX, p.y - mouseY);
                if (dist < minDist) {
                    minDist = dist;
                    hit = p;
                }
            }

            if (hit) {
                var pt = hit.point;
                var deltaHtml = '';
                if (pt.delta !== 0) {
                    var sign = pt.delta > 0 ? '+' : '';
                    var deltaColor = pt.delta > 0 ? '#10b981' : '#f87171';
                    deltaHtml = ' <span style="color:' + deltaColor + '; font-weight:700;">(' + sign + '£' + pt.delta.toFixed(2) + ')</span>';
                }
                var anchorBadge = pt.isAnchor ? ' <span style="color:#10b981; font-weight:700;">⚓ Anchor</span>' : '';
                tooltip.innerHTML = '<div style="font-weight:700; color:#f8fafc;">' + escapeHtml(pt.description || 'Balance Point') + anchorBadge + '</div>' +
                    '<div style="color:#94a3b8; font-size:0.75rem;">' + escapeHtml(pt.dateStr) + '</div>' +
                    '<div style="font-size:0.85rem; font-weight:800; color:#38bdf8; margin-top:2px;">£' + pt.balance.toFixed(2) + deltaHtml + '</div>';
                tooltip.style.left = Math.min(rect.width - 160, Math.max(10, hit.x - 70)) + 'px';
                tooltip.style.top = Math.max(10, hit.y - 65) + 'px';
                tooltip.style.display = 'block';
            } else {
                tooltip.style.display = 'none';
            }
        }

        canvas.onmousemove = function(e) {
            handlePointer(e.clientX, e.clientY);
        };

        canvas.ontouchstart = function(e) {
            if (e.touches && e.touches.length > 0) {
                handlePointer(e.touches[0].clientX, e.touches[0].clientY);
            }
        };

        canvas.ontouchmove = function(e) {
            if (e.touches && e.touches.length > 0) {
                handlePointer(e.touches[0].clientX, e.touches[0].clientY);
            }
        };

        canvas.onmouseleave = function() {
            tooltip.style.display = 'none';
        };

        window.addEventListener('resize', function() {
            var modal = document.getElementById('statement-preview-modal');
            if (currentPreviewData && modal && modal.style.display === 'block') {
                drawPreviewChart(currentPreviewData);
            }
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function() {
            initLegmon();
            initTestIngestToggle();
        });
    } else {
        initLegmon();
        initTestIngestToggle();
    }
})();
