/**
 * Spending Pace 5-Day Histogram Chart
 * Interactive HTML5 Canvas histogram with constant target pace baseline.
 */
(function () {
    'use strict';

    var currentMode = 'chunk'; // 'chunk' | 'cumulative' | 'spent'
    var renderedBars = [];

    function formatCurrency(val) {
        if (val === null || val === undefined || isNaN(val)) return '£0.00';
        return '£' + Number(val).toFixed(2);
    }

    function isDarkMode() {
        return document.documentElement.getAttribute('data-theme') !== 'light';
    }

    function renderChart() {
        var canvas = document.getElementById('pace-chart');
        if (!canvas) return;
        var ctx = canvas.getContext('2d');
        var container = canvas.parentElement;
        var rect = container.getBoundingClientRect();
        var dpr = window.devicePixelRatio || 1;
        var width = rect.width || 600;
        var height = rect.height || 320;

        canvas.width = width * dpr;
        canvas.height = height * dpr;
        ctx.scale(dpr, dpr);

        ctx.clearRect(0, 0, width, height);
        renderedBars = [];

        var data = window.spendingPaceData;
        if (!data || !data.chunks || data.chunks.length === 0) {
            ctx.fillStyle = '#94a3b8';
            ctx.font = '14px system-ui, -apple-system, sans-serif';
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            ctx.fillText('No spending pace data recorded for this cycle', width / 2, height / 2);
            return;
        }

        var chunks = data.chunks;
        var maxPerDay = Number(data.maxPerDay) || 0.0;
        var dark = isDarkMode();

        // Target baseline depends on mode
        var targetLineVal = maxPerDay;
        var targetLineLabel = 'MAX Target: ' + formatCurrency(maxPerDay) + ' / day';
        if (currentMode === 'spent') {
            targetLineVal = maxPerDay * 5;
            targetLineLabel = 'MAX 5-Day: ' + formatCurrency(targetLineVal);
        }

        // Determine max Y scale
        var maxVal = targetLineVal;
        for (var i = 0; i < chunks.length; i++) {
            var c = chunks[i];
            if (c.isFuture) continue;
            var val = 0;
            if (currentMode === 'chunk') val = Number(c.spendingPace) || 0;
            else if (currentMode === 'cumulative') val = Number(c.cumulativePace) || 0;
            else if (currentMode === 'spent') val = Number(c.totalSpent) || 0;
            if (val > maxVal) maxVal = val;
        }

        // Add 25% headroom on Y axis
        var yCeil = Math.max(10.0, maxVal * 1.25);

        // Padding: left for Y axis, bottom for X axis, right for target line label
        var padding = { top: 35, right: 95, bottom: 50, left: 60 };
        var plotWidth = width - padding.left - padding.right;
        var plotHeight = height - padding.top - padding.bottom;

        function getY(val) {
            return padding.top + plotHeight - (val / yCeil) * plotHeight;
        }

        // Draw Y-Axis Grid Lines & Ticks (4 steps)
        var steps = 4;
        ctx.textAlign = 'right';
        ctx.textBaseline = 'middle';
        ctx.font = '11px system-ui, -apple-system, sans-serif';

        for (var s = 0; s <= steps; s++) {
            var stepVal = (yCeil / steps) * s;
            var py = getY(stepVal);

            // Horizontal grid line
            ctx.strokeStyle = dark ? 'rgba(255, 255, 255, 0.08)' : 'rgba(0, 0, 0, 0.06)';
            ctx.lineWidth = 1;
            ctx.setLineDash([]);
            ctx.beginPath();
            ctx.moveTo(padding.left, py);
            ctx.lineTo(width - padding.right + 10, py);
            ctx.stroke();

            // Y label
            ctx.fillStyle = dark ? '#94a3b8' : '#64748b';
            var lbl = currentMode === 'spent' ? formatCurrency(stepVal) : formatCurrency(stepVal) + '/d';
            ctx.fillText(lbl, padding.left - 8, py);
        }

        // Draw Histogram Bars
        var numChunks = chunks.length;
        var colWidth = plotWidth / numChunks;
        var barPadding = Math.max(6, colWidth * 0.18);
        var barWidth = colWidth - barPadding * 2;

        for (var bIdx = 0; bIdx < numChunks; bIdx++) {
            var chunk = chunks[bIdx];
            var bx = padding.left + bIdx * colWidth + barPadding;
            var val = 0;
            if (currentMode === 'chunk') val = Number(chunk.spendingPace) || 0;
            else if (currentMode === 'cumulative') val = Number(chunk.cumulativePace) || 0;
            else if (currentMode === 'spent') val = Number(chunk.totalSpent) || 0;

            var by = getY(val);
            var bh = Math.max(0, getY(0) - by);

            var isOver = val > targetLineVal;

            if (chunk.isFuture) {
                // Future chunk: dashed outline box
                ctx.strokeStyle = dark ? 'rgba(148, 163, 184, 0.3)' : 'rgba(148, 163, 184, 0.4)';
                ctx.lineWidth = 1.5;
                ctx.setLineDash([4, 4]);
                ctx.strokeRect(bx, getY(targetLineVal), barWidth, getY(0) - getY(targetLineVal));
                ctx.setLineDash([]);

                // "Upcoming" label inside outline
                ctx.fillStyle = dark ? '#64748b' : '#94a3b8';
                ctx.font = '10px system-ui, -apple-system, sans-serif';
                ctx.textAlign = 'center';
                ctx.textBaseline = 'middle';
                ctx.fillText('Upcoming', bx + barWidth / 2, (getY(0) + getY(targetLineVal)) / 2);
            } else {
                // Gradient Fill for Bar
                var grad = ctx.createLinearGradient(bx, by, bx, getY(0));
                if (isOver) {
                    grad.addColorStop(0, '#ef4444');
                    grad.addColorStop(1, '#b91c1c');
                } else {
                    grad.addColorStop(0, '#10b981');
                    grad.addColorStop(1, '#059669');
                }

                ctx.fillStyle = grad;

                // Rounded Top Bar
                var radius = Math.min(6, barWidth / 2);
                ctx.beginPath();
                ctx.moveTo(bx, getY(0));
                ctx.lineTo(bx, by + radius);
                ctx.quadraticCurveTo(bx, by, bx + radius, by);
                ctx.lineTo(bx + barWidth - radius, by);
                ctx.quadraticCurveTo(bx + barWidth, by, bx + barWidth, by + radius);
                ctx.lineTo(bx + barWidth, getY(0));
                ctx.closePath();
                ctx.fill();

                // Highlight border if current chunk
                if (chunk.isCurrent) {
                    ctx.strokeStyle = '#6366f1';
                    ctx.lineWidth = 2.5;
                    ctx.stroke();
                }

                // Value Label above Bar
                ctx.fillStyle = dark ? '#f1f5f9' : '#0f172a';
                ctx.font = 'bold 10px system-ui, -apple-system, sans-serif';
                ctx.textAlign = 'center';
                ctx.textBaseline = 'bottom';
                var barLabel = currentMode === 'spent' ? formatCurrency(val) : formatCurrency(val) + '/d';
                ctx.fillText(barLabel, bx + barWidth / 2, by - 4);
            }

            // Save bounding box for hover tooltips
            renderedBars.push({
                x: bx,
                y: by,
                w: barWidth,
                h: bh,
                colX: padding.left + bIdx * colWidth,
                colW: colWidth,
                chunk: chunk,
                value: val,
                isOver: isOver
            });

            // X-Axis Labels: Chunk name + Dates
            ctx.fillStyle = chunk.isCurrent ? '#6366f1' : (dark ? '#cbd5e1' : '#334155');
            ctx.font = chunk.isCurrent ? 'bold 11px system-ui, -apple-system, sans-serif' : '10px system-ui, -apple-system, sans-serif';
            ctx.textAlign = 'center';
            ctx.textBaseline = 'top';

            var labelY = height - padding.bottom + 8;
            ctx.fillText(chunk.label, bx + barWidth / 2, labelY);

            ctx.fillStyle = dark ? '#64748b' : '#94a3b8';
            ctx.font = '9px system-ui, -apple-system, sans-serif';
            ctx.fillText(chunk.dateRangeStr, bx + barWidth / 2, labelY + 14);
        }

        // Draw Flat Horizontal Line for Constant Spending Pace Target
        if (targetLineVal > 0) {
            var targetY = getY(targetLineVal);

            // Dashed reference line across canvas
            ctx.strokeStyle = '#8b5cf6'; // Violet / Purple
            ctx.lineWidth = 2;
            ctx.setLineDash([6, 4]);
            ctx.beginPath();
            ctx.moveTo(padding.left - 5, targetY);
            ctx.lineTo(width - padding.right + 10, targetY);
            ctx.stroke();
            ctx.setLineDash([]);

            // Right-side badge pill for constant pace
            var badgeText = formatCurrency(targetLineVal) + (currentMode === 'spent' ? ' target' : '/d');
            ctx.font = 'bold 10px system-ui, -apple-system, sans-serif';
            ctx.textAlign = 'left';
            ctx.textBaseline = 'middle';

            var tw = ctx.measureText(badgeText).width;
            var pillX = width - padding.right + 14;
            var pillY = targetY - 10;
            var pillW = tw + 10;
            var pillH = 20;

            // Pill background
            ctx.fillStyle = '#8b5cf6';
            ctx.beginPath();
            ctx.roundRect ? ctx.roundRect(pillX, pillY, pillW, pillH, 4) : ctx.rect(pillX, pillY, pillW, pillH);
            ctx.fill();

            // Pill text
            ctx.fillStyle = '#ffffff';
            ctx.fillText(badgeText, pillX + 5, targetY);
        }
    }

    // Interactive Tooltip Handling
    function setupTooltip() {
        var container = document.getElementById('pace-chart-container');
        var tooltip = document.getElementById('pace-chart-tooltip');
        if (!container || !tooltip) return;

        function handlePointer(clientX) {
            var rect = container.getBoundingClientRect();
            var x = clientX - rect.left;

            var target = null;
            for (var i = 0; i < renderedBars.length; i++) {
                var rb = renderedBars[i];
                if (x >= rb.colX && x <= rb.colX + rb.colW) {
                    target = rb;
                    break;
                }
            }

            if (!target) {
                tooltip.style.display = 'none';
                return;
            }

            var c = target.chunk;
            var data = window.spendingPaceData;
            var maxPerDay = Number(data.maxPerDay) || 0.0;

            var statusHtml = '';
            if (c.isFuture) {
                statusHtml = '<span style="color: var(--text-muted);"><i class="far fa-clock"></i> Upcoming Interval</span>';
            } else if (target.isOver) {
                statusHtml = '<span style="color: #ef4444; font-weight: 700;"><i class="fas fa-arrow-up"></i> Over MAX by ' + formatCurrency(c.differenceFromMax) + '/day</span>';
            } else {
                statusHtml = '<span style="color: #10b981; font-weight: 700;"><i class="fas fa-arrow-down"></i> Under MAX by ' + formatCurrency(Math.abs(c.differenceFromMax)) + '/day</span>';
            }

            var html =
                '<div style="font-weight: 700; color: var(--text-main); margin-bottom: 4px; font-size: 0.85rem; border-bottom: 1px solid var(--border-color); padding-bottom: 4px;">' +
                '  <i class="far fa-calendar-alt" style="margin-right: 4px; color: var(--primary);"></i>' + c.label + ' · ' + c.dateRangeStr +
                '</div>' +
                '<div style="margin-bottom: 6px; font-size: 0.78rem;">' + statusHtml + '</div>' +
                '<div style="display: flex; justify-content: space-between; gap: 14px; margin-bottom: 2px;">' +
                '  <span style="color: var(--text-muted);">5-Day Pace:</span>' +
                '  <strong>' + (c.isFuture ? '—' : formatCurrency(c.spendingPace) + ' / day') + '</strong>' +
                '</div>' +
                '<div style="display: flex; justify-content: space-between; gap: 14px; margin-bottom: 2px;">' +
                '  <span style="color: var(--text-muted);">Total Spent:</span>' +
                '  <strong>' + (c.isFuture ? '—' : formatCurrency(c.totalSpent)) + '</strong>' +
                '</div>' +
                '<div style="display: flex; justify-content: space-between; gap: 14px; margin-bottom: 2px;">' +
                '  <span style="color: var(--text-muted);">Days Elapsed:</span>' +
                '  <span>' + (c.isFuture ? '0' : c.daysElapsed) + ' of ' + c.daysInChunk + ' days</span>' +
                '</div>' +
                '<div style="border-top: 1px dashed var(--border-color); padding-top: 4px; margin-top: 4px; display: flex; justify-content: space-between; gap: 14px;">' +
                '  <span style="color: #8b5cf6;">Constant MAX Target:</span>' +
                '  <strong>' + formatCurrency(maxPerDay) + ' / day</strong>' +
                '</div>';

            tooltip.innerHTML = html;
            tooltip.style.display = 'block';

            var tipWidth = tooltip.offsetWidth || 210;
            var tipHeight = tooltip.offsetHeight || 140;
            var containerWidth = container.offsetWidth;

            var leftPos = target.x + target.w / 2 - tipWidth / 2;
            if (leftPos < 10) leftPos = 10;
            if (leftPos + tipWidth > containerWidth - 10) leftPos = containerWidth - tipWidth - 10;

            var topPos = Math.max(10, target.y - tipHeight - 10);
            if (topPos < 10) topPos = target.y + 20;

            tooltip.style.left = leftPos + 'px';
            tooltip.style.top = topPos + 'px';
        }

        container.addEventListener('mousemove', function (e) {
            handlePointer(e.clientX);
        });

        container.addEventListener('mouseleave', function () {
            tooltip.style.display = 'none';
        });

        container.addEventListener('touchstart', function (e) {
            if (e.touches.length > 0) handlePointer(e.touches[0].clientX);
        }, { passive: true });

        container.addEventListener('touchmove', function (e) {
            if (e.touches.length > 0) handlePointer(e.touches[0].clientX);
        }, { passive: true });
    }

    // View Mode Buttons Setup
    function setupFilterButtons() {
        var buttons = document.querySelectorAll('.btn-pace-filter');
        var caption = document.getElementById('chart-mode-caption');

        buttons.forEach(function (btn) {
            btn.addEventListener('click', function () {
                buttons.forEach(function (b) {
                    b.classList.remove('active');
                    b.style.background = 'transparent';
                    b.style.color = 'var(--text-muted)';
                });
                btn.classList.add('active');
                btn.style.background = 'var(--primary)';
                btn.style.color = '#ffffff';

                currentMode = btn.getAttribute('data-mode') || 'chunk';

                if (caption) {
                    if (currentMode === 'chunk') caption.textContent = 'Showing 5-Day Chunk Spending Pace';
                    else if (currentMode === 'cumulative') caption.textContent = 'Showing Cumulative Running Pace';
                    else if (currentMode === 'spent') caption.textContent = 'Showing Total Amount Spent per Chunk';
                }

                renderChart();
            });
        });
    }

    window.addEventListener('resize', renderChart);

    document.addEventListener('DOMContentLoaded', function () {
        renderChart();
        setupTooltip();
        setupFilterButtons();
    });

    if (document.readyState === 'complete' || document.readyState === 'interactive') {
        setTimeout(function () {
            renderChart();
            setupTooltip();
            setupFilterButtons();
        }, 50);
    }
})();
