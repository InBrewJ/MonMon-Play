// LegMon Frontend Status & Log Viewer (Plain JavaScript)
(function() {
    'use strict';

    function initLegmon() {
        checkStatus();
        // Check if upload was recently triggered or pending statements exist
        var pendingCard = document.querySelector('[data-has-pending-statement="true"]');
        if (pendingCard) {
            // Check status immediately
            checkStatus();
        }
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
    }

    function drawPreviewChart(data) {
        var canvas = document.getElementById('statement-preview-canvas');
        if (!canvas) return;
        var ctx = canvas.getContext('2d');
        var dpr = window.devicePixelRatio || 1;
        var rect = canvas.getBoundingClientRect();
        var width = rect.width || 600;
        var height = rect.height || 260;

        canvas.width = width * dpr;
        canvas.height = height * dpr;
        ctx.scale(dpr, dpr);

        ctx.clearRect(0, 0, width, height);
        currentRenderedPoints = [];

        var points = data.points || [];
        if (!points.length) {
            ctx.fillStyle = '#94a3b8';
            ctx.font = '14px system-ui, -apple-system, sans-serif';
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            ctx.fillText('No balance points to reconstruct without an anchor balance', width / 2, height / 2);
            return;
        }

        var padding = { top: 30, right: 35, bottom: 40, left: 65 };
        var vals = points.map(function(p) { return p.balance; });
        var minVal = Math.min.apply(null, vals);
        var maxVal = Math.max.apply(null, vals);
        if (minVal === maxVal) { minVal = 0; maxVal = (maxVal * 1.2) || 100; }
        var span = maxVal - minVal;
        minVal = Math.min(0, minVal - span * 0.1);
        maxVal = maxVal + span * 0.15;

        var tMin = points[0].timestamp;
        var tMax = points[points.length - 1].timestamp;
        if (tMin === tMax) { tMin -= 86400; tMax += 86400; }

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

        canvas.onmousemove = function(e) {
            var rect = canvas.getBoundingClientRect();
            var mouseX = e.clientX - rect.left;
            var mouseY = e.clientY - rect.top;

            var hit = null;
            var minDist = 18;
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
        };

        canvas.onmouseleave = function() {
            tooltip.style.display = 'none';
        };
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
