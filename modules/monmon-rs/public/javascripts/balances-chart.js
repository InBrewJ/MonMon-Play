/**
 * Balances Trajectory Chart & Account Selection Controls (/myBalances)
 * Plain JavaScript (Zero CoffeeScript).
 */
(function() {
    'use strict';

    // 1. Interactive Chart Logic — Adaptive Colour-Wheel Spacing (fewer lines = wider hue separation)
    var LIGHTNESS_LEVELS = [60, 68, 54, 64, 58, 66];
    var SATURATION_LEVELS = [85, 90, 80, 88];
    // Exclude Payday dashed green (#10b981 ~ 160°) band [144°, 176°]; usable arc is [177°..359°] U [0°..143°]
    var PAYDAY_ARC_START = 177;
    var USABLE_HUE_SPAN = 326;
    var RANGE_ORDER = ['2m', '6m', '1y', 'all'];
    var RANGE_SECONDS = {
        '2m': 60 * 86400,
        '6m': 180 * 86400,
        '1y': 365 * 86400
    };
    var RANGE_LABELS = {
        '2m': 'Showing Last 2 Months',
        '6m': 'Showing Last 6 Months',
        '1y': 'Showing Last Year',
        'all': 'Showing All History'
    };

    function hashAccountTitle(title) {
        if (!title) return 0;
        var clean = String(title).trim().toLowerCase();
        var hash = 5381;
        for (var i = 0; i < clean.length; i++) {
            hash = ((hash << 5) + hash) + clean.charCodeAt(i);
            hash = hash & hash;
        }
        return Math.abs(hash);
    }

    function mapUsableHue(pos) {
        var wrapped = ((pos % USABLE_HUE_SPAN) + USABLE_HUE_SPAN) % USABLE_HUE_SPAN;
        return Math.round(PAYDAY_ARC_START + wrapped) % 360;
    }

    function computeDistinctColors(items) {
        var result = {};
        if (!items || !items.length) return result;
        var sorted = items.slice().sort(function(a, b) {
            return String(a.id).localeCompare(String(b.id));
        });
        var n = sorted.length;
        var anchor = hashAccountTitle(sorted[0].name || sorted[0].id) % USABLE_HUE_SPAN;
        var step = USABLE_HUE_SPAN / n;
        for (var i = 0; i < n; i++) {
            var hue = mapUsableHue(anchor + (i * step));
            var l = LIGHTNESS_LEVELS[i % LIGHTNESS_LEVELS.length];
            var s = SATURATION_LEVELS[i % SATURATION_LEVELS.length];
            result[String(sorted[i].id)] = 'hsl(' + hue + ', ' + s + '%, ' + l + '%)';
        }
        return result;
    }

    function escapeHtml(str) {
        if (!str) return '';
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#039;');
    }

    var selectedRange = '2m';
    var accountNamesById = {};

    var dots = document.querySelectorAll('.account-palette-dot');
    var accountList = [];
    dots.forEach(function(dot) {
        var id = String(dot.getAttribute('data-account-id'));
        var name = dot.getAttribute('data-account-name') || '';
        accountNamesById[id] = name;
        accountList.push({
            id: id,
            name: name,
            dot: dot
        });
    });

    var accountColors = computeDistinctColors(accountList);

    function syncAccountDots(overrideColors) {
        accountList.forEach(function(acc) {
            if (acc.dot) {
                acc.dot.style.background = (overrideColors && overrideColors[acc.id]) || accountColors[acc.id] || '#6366f1';
            }
        });
    }

    syncAccountDots(null);

    var timeBtns = document.querySelectorAll('.btn-time-filter');

    function setRange(range) {
        selectedRange = range;
        timeBtns.forEach(function(b) {
            var isMatch = b.getAttribute('data-range') === range;
            if (isMatch) {
                b.classList.add('active');
                b.style.background = 'var(--primary)';
                b.style.color = '#fff';
            } else {
                b.classList.remove('active');
                b.style.background = 'var(--card-bg-subtle)';
                b.style.color = 'var(--text-muted)';
            }
        });
        var rangeLbl = document.getElementById('chart-range-label');
        if (rangeLbl) {
            rangeLbl.textContent = RANGE_LABELS[range] || RANGE_LABELS['all'];
        }
    }

    function hasAccountPointInRange(accBals, range, nowSec) {
        if (!accBals.length) return false;
        if (range === 'all') return true;
        var span = RANGE_SECONDS[range] || RANGE_SECONDS['2m'];
        var minSec = nowSec - span;
        return accBals.some(function(b) {
            return b.timestamp >= minSec && b.timestamp <= nowSec;
        });
    }

    function ensureRangeIncludesAccount(accId) {
        var idStr = String(accId);
        var raw = window.rawBalances || [];
        var accBals = raw.filter(function(b) { return String(b.accountId) === idStr; });
        if (!accBals.length) return;

        var nowSec = Math.floor(Date.now() / 1000);
        if (hasAccountPointInRange(accBals, selectedRange, nowSec)) return;

        for (var i = 0; i < RANGE_ORDER.length; i++) {
            var candidate = RANGE_ORDER[i];
            if (hasAccountPointInRange(accBals, candidate, nowSec)) {
                setRange(candidate);
                return;
            }
        }
        setRange('all');
    }

    var accSelect = document.getElementById('account');
    var valInput = document.getElementById('balance-value');
    var selectedAccountIds = new Set((window.defaultAccountIds || []).map(String));

    if (window.preselectedAccountId !== null && window.preselectedAccountId !== undefined && String(window.preselectedAccountId) !== '') {
        var preId = String(window.preselectedAccountId);
        selectedAccountIds = new Set([preId]);
        if (accSelect) accSelect.value = preId;
        ensureRangeIncludesAccount(preId);
        if (valInput) {
            setTimeout(function() { valInput.focus(); }, 150);
        }
    }

    function updateMultiselectLabel() {
        var chks = document.querySelectorAll('.account-filter-chk');
        var totalAccounts = chks.length;
        var count = selectedAccountIds.size;
        var lbl = document.getElementById('multiselect-label');
        if (!lbl) return;
        if (count === 0) {
            lbl.textContent = 'No Accounts';
        } else if (count === 1) {
            var onlyId = Array.from(selectedAccountIds)[0];
            lbl.textContent = accountNamesById[onlyId] || '1 Account';
        } else if (count === totalAccounts && totalAccounts > 0) {
            lbl.textContent = 'All Accounts (' + count + ')';
        } else {
            lbl.textContent = count + ' Accounts';
        }
    }

    function syncCheckboxes() {
        var chks = document.querySelectorAll('.account-filter-chk');
        chks.forEach(function(chk) {
            chk.checked = selectedAccountIds.has(String(chk.value));
        });
        updateMultiselectLabel();
    }

    function selectSingleAccount(accId) {
        var idStr = String(accId || '');
        if (!idStr) return;
        selectedAccountIds = new Set([idStr]);
        ensureRangeIncludesAccount(idStr);
        syncCheckboxes();
        renderChart();
    }

    syncCheckboxes();

    // Selecting an account in the Record Available Balance form focuses the graph on that account
    if (accSelect) {
        accSelect.addEventListener('change', function() {
            selectSingleAccount(accSelect.value);
        });
    }

    // Dropdown toggling
    var msBtn = document.getElementById('btn-account-multiselect');
    var msDropdown = document.getElementById('multiselect-dropdown');
    if (msBtn && msDropdown) {
        msBtn.addEventListener('click', function(e) {
            e.stopPropagation();
            msDropdown.style.display = msDropdown.style.display === 'none' ? 'block' : 'none';
        });
        document.addEventListener('click', function() {
            msDropdown.style.display = 'none';
        });
        msDropdown.addEventListener('click', function(e) {
            e.stopPropagation();
        });
    }

    // Checkbox changes
    var chks = document.querySelectorAll('.account-filter-chk');
    chks.forEach(function(chk) {
        chk.addEventListener('change', function() {
            var accId = String(chk.value);
            if (chk.checked) selectedAccountIds.add(accId);
            else selectedAccountIds.delete(accId);
            updateMultiselectLabel();
            renderChart();
        });
    });

    // Quick select buttons
    var potBtn = document.getElementById('select-pot-btn');
    if (potBtn) {
        potBtn.addEventListener('click', function() {
            selectedAccountIds = new Set((window.defaultAccountIds || []).map(String));
            syncCheckboxes();
            renderChart();
        });
    }
    var allBtn = document.getElementById('select-all-btn');
    if (allBtn) {
        allBtn.addEventListener('click', function() {
            chks.forEach(function(chk) { selectedAccountIds.add(String(chk.value)); });
            syncCheckboxes();
            renderChart();
        });
    }
    var noneBtn = document.getElementById('select-none-btn');
    if (noneBtn) {
        noneBtn.addEventListener('click', function() {
            selectedAccountIds.clear();
            syncCheckboxes();
            renderChart();
        });
    }

    // Range buttons
    timeBtns.forEach(function(btn) {
        btn.addEventListener('click', function() {
            setRange(btn.getAttribute('data-range'));
            renderChart();
        });
    });

    // Canvas drawing
    var renderedPoints = [];
    function renderChart() {
        var canvas = document.getElementById('balance-chart');
        if (!canvas) return;
        var ctx = canvas.getContext('2d');
        var container = canvas.parentElement;
        var rect = container.getBoundingClientRect();
        var dpr = window.devicePixelRatio || 1;
        var width = rect.width || 600;
        var height = rect.height || 280;

        canvas.width = width * dpr;
        canvas.height = height * dpr;
        ctx.scale(dpr, dpr);
        ctx.clearRect(0, 0, width, height);
        renderedPoints = [];

        var padding = { top: 25, right: 30, bottom: 35, left: 65 };
        var nowSec = Math.floor(Date.now() / 1000);
        var minSec = (selectedRange === 'all') ? 0 : (nowSec - (RANGE_SECONDS[selectedRange] || RANGE_SECONDS['2m']));

        var raw = window.rawBalances || [];
        var filtered = raw.filter(function(b) {
            var inTime = (selectedRange === 'all') || (b.timestamp >= minSec && b.timestamp <= nowSec);
            var inAcc = selectedAccountIds.has(String(b.accountId));
            return inTime && inAcc;
        });

        filtered.sort(function(a, b) { return a.timestamp - b.timestamp; });

        var emptyMsg = document.getElementById('empty-chart-msg');
        var legendAccountsContainer = document.getElementById('chart-legend-accounts');
        if (!filtered.length) {
            ctx.fillStyle = '#94a3b8';
            ctx.font = '14px system-ui, -apple-system, sans-serif';
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            if (selectedAccountIds.size === 0) {
                ctx.fillText('No accounts selected. Use the filter above to select accounts.', width / 2, height / 2);
                if (emptyMsg) {
                    emptyMsg.textContent = 'No accounts selected. Use the filter above to select accounts.';
                    emptyMsg.style.display = 'flex';
                }
            } else {
                ctx.fillText('No balance entries recorded for selected accounts in this range.', width / 2, height / 2);
                if (emptyMsg) {
                    emptyMsg.textContent = 'No balance entries recorded for selected accounts in this range.';
                    emptyMsg.style.display = 'flex';
                }
            }
            if (legendAccountsContainer) {
                legendAccountsContainer.innerHTML = '<span><span style="display: inline-block; width: 12px; height: 3px; background: var(--primary); vertical-align: middle; margin-right: 4px;"></span> Balance Line</span>';
            }
            return;
        }
        if (emptyMsg) emptyMsg.style.display = 'none';

        var rawMin = Math.min.apply(null, filtered.map(function(d) { return d.value; }));
        var rawMax = Math.max.apply(null, filtered.map(function(d) { return d.value; }));
        var minVal = Math.min(0, rawMin);
        var maxVal = Math.max(0, rawMax);
        if (minVal === maxVal) {
            maxVal = 100;
        } else {
            if (minVal < 0) minVal = minVal * 1.1;
            if (maxVal > 0) maxVal = maxVal * 1.1;
        }

        var tMin = (selectedRange === 'all') ? filtered[0].timestamp : minSec;
        var tMax = Math.max(nowSec, filtered[filtered.length - 1].timestamp);
        if (tMin === tMax) tMin = tMax - 86400;

        function getX(t) { return padding.left + ((t - tMin) / (tMax - tMin)) * (width - padding.left - padding.right); }
        function getY(v) { return height - padding.bottom - ((v - minVal) / (maxVal - minVal)) * (height - padding.top - padding.bottom); }

        // Gridlines & Y-Axis
        var ySteps = 4;
        ctx.strokeStyle = 'rgba(148, 163, 184, 0.2)';
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

        // Paydays
        var paydays = window.paydays || [];
        paydays.forEach(function(pd) {
            if (pd.timestamp >= tMin && pd.timestamp <= tMax) {
                var px = getX(pd.timestamp);
                ctx.strokeStyle = '#10b981';
                ctx.lineWidth = 1.5;
                ctx.setLineDash([4, 4]);
                ctx.beginPath();
                ctx.moveTo(px, padding.top);
                ctx.lineTo(px, height - padding.bottom);
                ctx.stroke();
                ctx.setLineDash([]);
            }
        });

        // Group points by accountId
        var groups = {};
        filtered.forEach(function(pt) {
            var accKey = String(pt.accountId);
            if (!groups[accKey]) groups[accKey] = { id: accKey, name: pt.accountName, points: [] };
            groups[accKey].points.push(pt);
        });

        // Distribute visible line colours maximally far apart around the colour wheel
        var visibleGroupList = Object.keys(groups).map(function(accId) {
            return groups[accId];
        });
        var activeColors = computeDistinctColors(visibleGroupList);
        syncAccountDots(activeColors);

        // Update Legend under chart with colored indicators for each active account
        if (legendAccountsContainer) {
            var legendHtml = '';
            var groupKeys = Object.keys(groups);
            if (groupKeys.length === 0) {
                legendHtml = '<span><span style="display: inline-block; width: 12px; height: 3px; background: var(--primary); vertical-align: middle; margin-right: 4px;"></span> Balance Line</span>';
            } else {
                groupKeys.forEach(function(accId) {
                    var grp = groups[accId];
                    var col = activeColors[accId] || '#6366f1';
                    legendHtml += '<span><span style="display: inline-block; width: 12px; height: 3px; background: ' + col + '; vertical-align: middle; margin-right: 4px; border-radius: 1px;"></span> ' + escapeHtml(grp.name) + '</span>';
                });
            }
            legendAccountsContainer.innerHTML = legendHtml;
        }

        Object.keys(groups).forEach(function(accId) {
            var grp = groups[accId];
            var col = activeColors[accId] || '#6366f1';
            ctx.strokeStyle = col;
            ctx.lineWidth = 2.5;
            ctx.lineJoin = 'round';
            ctx.lineCap = 'round';

            if (grp.points.length === 1) {
                var pt = grp.points[0];
                var py = getY(pt.value);
                ctx.setLineDash([4, 4]);
                ctx.beginPath();
                ctx.moveTo(padding.left, py);
                ctx.lineTo(width - padding.right, py);
                ctx.stroke();
                ctx.setLineDash([]);

                var px = getX(pt.timestamp);
                ctx.beginPath();
                ctx.arc(px, py, 5, 0, Math.PI * 2);
                ctx.fillStyle = col;
                ctx.fill();
                renderedPoints.push({ x: px, y: py, pt: pt, color: col });
            } else {
                ctx.beginPath();
                for (var j = 0; j < grp.points.length; j++) {
                    var px = getX(grp.points[j].timestamp);
                    var py = getY(grp.points[j].value);
                    if (j === 0) ctx.moveTo(px, py);
                    else ctx.lineTo(px, py);
                }
                ctx.stroke();

                for (var k = 0; k < grp.points.length; k++) {
                    var ptx = getX(grp.points[k].timestamp);
                    var pty = getY(grp.points[k].value);
                    ctx.beginPath();
                    ctx.arc(ptx, pty, 4, 0, Math.PI * 2);
                    ctx.fillStyle = col;
                    ctx.fill();
                    renderedPoints.push({ x: ptx, y: pty, pt: grp.points[k], color: col });
                }
            }
        });
    }

    window.addEventListener('resize', renderChart);
    renderChart();

    // Tooltip handling
    var chartCanvas = document.getElementById('balance-chart');
    var tooltip = document.getElementById('chart-tooltip');
    if (chartCanvas && tooltip) {
        chartCanvas.addEventListener('mousemove', function(e) {
            var rect = chartCanvas.getBoundingClientRect();
            var mx = e.clientX - rect.left;
            var my = e.clientY - rect.top;
            var closest = null;
            var closestDist = 20;

            renderedPoints.forEach(function(p) {
                var d = Math.hypot(p.x - mx, p.y - my);
                if (d < closestDist) {
                    closestDist = d;
                    closest = p;
                }
            });

            if (closest) {
                tooltip.style.display = 'block';
                var tipW = tooltip.offsetWidth || 140;
                var posX = closest.x + 10;
                if (posX + tipW > chartCanvas.offsetWidth) {
                    posX = Math.max(10, closest.x - tipW - 10);
                }
                var posY = Math.max(5, closest.y - 35);
                tooltip.style.left = posX + 'px';
                tooltip.style.top = posY + 'px';
                tooltip.innerHTML = '<strong style="color: ' + closest.color + ';">' + escapeHtml(closest.pt.accountName) + '</strong>: £' + closest.pt.value + '<br/><span style="font-size: 0.72rem; color: var(--text-muted);">' + escapeHtml(closest.pt.dateStr) + '</span>';
            } else {
                tooltip.style.display = 'none';
            }
        });
        chartCanvas.addEventListener('mouseleave', function() {
            tooltip.style.display = 'none';
        });
    }

    // 3. Search Filter across recorded balances table and mobile cards
    var searchInput = document.getElementById('balance-search-input');
    var clearSearchBtn = document.getElementById('balance-search-clear');
    if (searchInput) {
        searchInput.addEventListener('input', function() {
            var query = searchInput.value.toLowerCase().trim();
            if (clearSearchBtn) clearSearchBtn.style.display = query ? 'block' : 'none';

            var rows = document.querySelectorAll('.balance-data-row');
            rows.forEach(function(row) {
                var acc = row.getAttribute('data-account-name') || '';
                var val = row.getAttribute('data-balance-val') || '';
                var match = acc.includes(query) || val.includes(query);
                row.style.display = match ? '' : 'none';
            });

            var mCards = document.querySelectorAll('.balance-mobile-card');
            mCards.forEach(function(card) {
                var acc = card.getAttribute('data-account-name') || '';
                var val = card.getAttribute('data-balance-val') || '';
                var match = acc.includes(query) || val.includes(query);
                card.style.display = match ? 'flex' : 'none';
            });
        });
        if (clearSearchBtn) {
            clearSearchBtn.addEventListener('click', function() {
                searchInput.value = '';
                clearSearchBtn.style.display = 'none';
                searchInput.dispatchEvent(new Event('input'));
                searchInput.focus();
            });
        }
    }

    // 4. Payday quick-fill button for Effective Date & Time
    function bindPaydayButton() {
        var btnPayday = document.getElementById('btn-balance-payday');
        var dtInput = document.getElementById('balance-datetime');
        if (btnPayday && dtInput) {
            btnPayday.addEventListener('click', function() {
                var nowSec = Math.floor(Date.now() / 1000);
                var paydays = window.paydays || [];
                var pastPaydays = paydays.filter(function(p) { return p.timestamp <= nowSec; });
                if (pastPaydays.length > 0) {
                    var lastP = pastPaydays[pastPaydays.length - 1];
                    var d = new Date(lastP.timestamp * 1000);
                    var y = d.getFullYear();
                    var m = String(d.getMonth() + 1).padStart(2, '0');
                    var day = String(d.getDate()).padStart(2, '0');
                    dtInput.value = y + '-' + m + '-' + day + 'T12:00';
                } else {
                    var now = new Date();
                    var ny = now.getFullYear();
                    var nm = String(now.getMonth() + 1).padStart(2, '0');
                    var nday = String(now.getDate()).padStart(2, '0');
                    dtInput.value = ny + '-' + nm + '-' + nday + 'T12:00';
                }
            });
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', bindPaydayButton);
    } else {
        bindPaydayButton();
    }
})();
