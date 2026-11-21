// MonMon Erratic Balance Inspector (Plain JavaScript, Zero CoffeeScript)
// Displays transaction dates and supports live filtering by account name.
(function() {
    'use strict';

    var MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    var cachedJumps = [];
    var lastThreshold = '500';
    var hasLoaded = false;

    function escapeHtml(str) {
        return String(str == null ? '' : str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function pad2(n) {
        return n < 10 ? '0' + n : String(n);
    }

    function formatErraticDate(rawTs) {
        var num = Number(rawTs);
        if (!num || !isFinite(num)) return 'Unknown date';
        var ms = num > 100000000000 ? num : num * 1000;
        var d = new Date(ms);
        if (isNaN(d.getTime())) return 'Unknown date';
        return pad2(d.getDate()) + ' ' + MONTHS[d.getMonth()] + ' ' + d.getFullYear() + ', ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes());
    }

    function formatAmount(val) {
        var n = Number(val);
        if (!isFinite(n)) return '0.00';
        return n.toFixed(2);
    }

    function renderErraticBalances() {
        var container = document.getElementById('erratic-balances-container');
        var countLbl = document.getElementById('erratic-result-count');
        var searchInput = document.getElementById('erratic-account-search');
        var clearBtn = document.getElementById('erratic-search-clear');

        var query = searchInput ? String(searchInput.value || '').trim() : '';
        var queryLower = query.toLowerCase();

        if (clearBtn) {
            clearBtn.style.display = query ? 'inline-flex' : 'none';
        }

        if (!hasLoaded) return;

        var filtered = queryLower
            ? cachedJumps.filter(function(item) {
                return String(item.accountName || '').toLowerCase().indexOf(queryLower) !== -1;
            })
            : cachedJumps;

        if (countLbl) {
            if (queryLower) {
                countLbl.textContent = 'Showing ' + filtered.length + ' of ' + cachedJumps.length + ' jump(s)';
            } else {
                countLbl.textContent = 'Found ' + cachedJumps.length + ' jump(s)';
            }
        }

        if (!container) return;

        if (!cachedJumps.length) {
            container.innerHTML = '<div style="text-align: center; color: var(--success); padding: 2rem;"><i class="fas fa-check-circle" style="font-size: 1.5rem; margin-bottom: 6px; display: block;"></i> No erratic balance jumps found above £' + escapeHtml(lastThreshold) + '.</div>';
            return;
        }

        if (!filtered.length) {
            container.innerHTML = '<div style="text-align: center; color: var(--text-muted); padding: 2rem;"><i class="fas fa-search" style="font-size: 1.35rem; margin-bottom: 6px; display: block;"></i> No erratic jumps matching &ldquo;' + escapeHtml(query) + '&rdquo;.</div>';
            return;
        }

        var html = '<div style="display: flex; flex-direction: column; gap: 8px;">';
        filtered.forEach(function(item) {
            var dateStr = formatErraticDate(item.timestamp);
            var prevDateNote = item.prevTimestamp ? 'Previous balance: ' + formatErraticDate(item.prevTimestamp) : '';
            var accName = escapeHtml(item.accountName);
            var prevVal = formatAmount(item.prevValue);
            var currVal = formatAmount(item.currValue);
            var jumpAmt = formatAmount(item.jumpAmount);

            html += '<div class="item-card" style="padding: 10px 12px; background: var(--card-bg-subtle); border: 1px solid var(--border-color); border-radius: 8px; display: flex; flex-direction: column; gap: 6px;">';
            html += '  <div style="display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap;">';
            html += '    <strong style="color: var(--text-main); font-size: 0.92rem;">' + accName + '</strong>';
            html += '    <span class="badge-pill" style="background: var(--card-bg); color: var(--text-muted); border: 1px solid var(--border-color); font-size: 0.75rem; font-weight: 600;"' + (prevDateNote ? ' title="' + escapeHtml(prevDateNote) + '"' : '') + '><i class="far fa-calendar-alt" style="margin-right: 4px; color: var(--primary);"></i>' + escapeHtml(dateStr) + '</span>';
            html += '  </div>';
            html += '  <div style="display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap;">';
            html += '    <div style="font-size: 0.86rem; color: var(--text-main);">jumped <b>£' + prevVal + '</b> &rarr; <b>£' + currVal + '</b> <span style="color: #f59e0b; font-weight: 700;">(&Delta; £' + jumpAmt + ')</span></div>';
            html += '    <button type="button" data-method="DELETE" data-action="/balance/' + encodeURIComponent(item.currBalanceId) + '/delete" data-confirm="Delete this erratic balance jump on ' + escapeHtml(dateStr) + '?" class="w3-button w3-round w3-tiny btn-action" style="background: rgba(239, 68, 68, 0.1); color: #ef4444; border: 1px solid #ef4444; padding: 4px 10px; border-radius: 4px; cursor: pointer; font-weight: 600;">Delete Jump</button>';
            html += '  </div>';
            html += '</div>';
        });
        html += '</div>';
        container.innerHTML = html;
    }

    function openErraticBalancesModal() {
        var modal = document.getElementById('erratic-balances-modal');
        if (modal) {
            modal.style.display = 'block';
            loadErraticBalances();
        }
    }

    function closeErraticBalancesModal() {
        var modal = document.getElementById('erratic-balances-modal');
        if (modal) modal.style.display = 'none';
    }

    function loadErraticBalances() {
        var container = document.getElementById('erratic-balances-container');
        var thresholdInput = document.getElementById('erratic-threshold-input');
        var threshold = thresholdInput ? thresholdInput.value : '500';
        lastThreshold = threshold;

        if (container) {
            container.innerHTML = '<div style="text-align: center; color: var(--text-muted); padding: 2rem;"><i class="fas fa-spinner fa-spin"></i> Checking for erratic jumps...</div>';
        }

        fetch('/api/balances/erratic?threshold=' + encodeURIComponent(threshold))
            .then(function(res) { return res.json(); })
            .then(function(data) {
                cachedJumps = Array.isArray(data) ? data : [];
                hasLoaded = true;
                renderErraticBalances();
            })
            .catch(function(err) {
                hasLoaded = false;
                if (container) {
                    container.innerHTML = '<div style="color: #ef4444; padding: 1rem;">Failed to load erratic balances: ' + escapeHtml(err && err.message ? err.message : err) + '</div>';
                }
            });
    }

    function initErraticBalancesInspector() {
        var searchInput = document.getElementById('erratic-account-search');
        var clearBtn = document.getElementById('erratic-search-clear');

        if (searchInput) {
            searchInput.addEventListener('input', function() {
                renderErraticBalances();
            });
        }
        if (clearBtn) {
            clearBtn.addEventListener('click', function() {
                if (searchInput) {
                    searchInput.value = '';
                    if (typeof searchInput.focus === 'function') searchInput.focus();
                }
                renderErraticBalances();
            });
        }
    }

    window.openErraticBalancesModal = openErraticBalancesModal;
    window.closeErraticBalancesModal = closeErraticBalancesModal;
    window.loadErraticBalances = loadErraticBalances;

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initErraticBalancesInspector);
    } else {
        initErraticBalancesInspector();
    }
})();
