/**
 * Net Worth History & Trajectory Chart
 * Interactive HTML5 Canvas chart showing 1y, 2y, 5y, and 10y trajectory for:
 * 1. Pensions
 * 2. Cash & Savings (on one line)
 * 3. Fixed Assets (drops to 0 when sold/deleted)
 * 4. Total Net Worth
 */
(function () {
    'use strict';

    var currentRange = '1y';
    var rawData = [];
    var renderedPoints = [];

    function formatCurrency(val) {
        if (val === null || val === undefined || isNaN(val)) return '£0';
        return '£' + Math.round(val).toLocaleString('en-GB');
    }

    function formatShortCurrency(val) {
        if (Math.abs(val) >= 1000000) {
            return '£' + (val / 1000000).toFixed(1).replace(/\.0$/, '') + 'M';
        }
        if (Math.abs(val) >= 1000) {
            return '£' + (val / 1000).toFixed(0) + 'k';
        }
        return '£' + Math.round(val);
    }

    function getRangeSeconds(range) {
        switch (range) {
            case '2y': return 730 * 86400;
            case '5y': return 1825 * 86400;
            case '10y': return 3650 * 86400;
            case '1y':
            default:
                return 365 * 86400;
        }
    }

    function getRangeLabel(range) {
        switch (range) {
            case '2y': return 'Showing Last 2 Years';
            case '5y': return 'Showing Last 5 Years';
            case '10y': return 'Showing Last 10 Years';
            case '1y':
            default:
                return 'Showing Last 1 Year';
        }
    }

    function renderChart() {
        var canvas = document.getElementById('networth-chart');
        if (!canvas) return;
        var ctx = canvas.getContext('2d');
        var container = canvas.parentElement;
        var rect = container.getBoundingClientRect();
        var dpr = window.devicePixelRatio || 1;
        var width = rect.width || 600;
        var height = rect.height || 300;

        canvas.width = width * dpr;
        canvas.height = height * dpr;
        ctx.scale(dpr, dpr);

        ctx.clearRect(0, 0, width, height);
        renderedPoints = [];

        var padding = { top: 30, right: 30, bottom: 40, left: 70 };

        var nowSec = Math.floor(Date.now() / 1000);
        var rangeDuration = getRangeSeconds(currentRange);
        var minSec = nowSec - rangeDuration;

        // If no data, show polite placeholder
        if (!rawData || rawData.length === 0) {
            ctx.fillStyle = '#94a3b8';
            ctx.font = '14px system-ui, -apple-system, sans-serif';
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            ctx.fillText('No history points recorded yet', width / 2, height / 2);
            return;
        }

        // Sort data chronologically
        var sorted = rawData.slice().sort(function (a, b) { return a.timestamp - b.timestamp; });

        // Filter points within range, plus carry forward the last point prior to minSec
        var priorPoint = null;
        for (var i = 0; i < sorted.length; i++) {
            if (sorted[i].timestamp < minSec) {
                priorPoint = sorted[i];
            } else {
                break;
            }
        }

        var visible = sorted.filter(function (p) {
            return p.timestamp >= minSec && p.timestamp <= nowSec;
        });

        // Ensure boundary point at minSec for smooth lines
        if (priorPoint) {
            visible.unshift({
                timestamp: minSec,
                dateStr: priorPoint.dateStr,
                pensions: priorPoint.pensions,
                cashSavings: priorPoint.cashSavings,
                fixedAssets: priorPoint.fixedAssets,
                total: priorPoint.total
            });
        } else if (visible.length > 0 && visible[0].timestamp > minSec) {
            visible.unshift({
                timestamp: minSec,
                dateStr: visible[0].dateStr,
                pensions: 0,
                cashSavings: 0,
                fixedAssets: 0,
                total: 0
            });
        }

        // Ensure boundary point at nowSec
        if (visible.length > 0) {
            var last = visible[visible.length - 1];
            if (last.timestamp < nowSec) {
                visible.push({
                    timestamp: nowSec,
                    dateStr: 'Today',
                    pensions: last.pensions,
                    cashSavings: last.cashSavings,
                    fixedAssets: last.fixedAssets,
                    total: last.total
                });
            }
        }

        if (visible.length === 0) {
            ctx.fillStyle = '#94a3b8';
            ctx.font = '14px system-ui, -apple-system, sans-serif';
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            ctx.fillText('No data available in this time range', width / 2, height / 2);
            return;
        }

        // Compute Y min and max
        var minVal = 0;
        var maxVal = 0;

        for (var j = 0; j < visible.length; j++) {
            var pt = visible[j];
            var lowest = Math.min(pt.pensions, pt.cashSavings, pt.fixedAssets, pt.total);
            var highest = Math.max(pt.pensions, pt.cashSavings, pt.fixedAssets, pt.total);
            if (lowest < minVal) minVal = lowest;
            if (highest > maxVal) maxVal = highest;
        }

        if (maxVal === 0 && minVal === 0) {
            maxVal = 1000;
        } else {
            maxVal = maxVal * 1.15;
            if (minVal < 0) minVal = minVal * 1.1;
        }

        var tMin = minSec;
        var tMax = nowSec;
        if (tMin >= tMax) tMin = tMax - 86400;

        function getX(t) {
            return padding.left + ((t - tMin) / (tMax - tMin)) * (width - padding.left - padding.right);
        }

        function getY(v) {
            return height - padding.bottom - ((v - minVal) / (maxVal - minVal)) * (height - padding.top - padding.bottom);
        }

        // Grid lines & Y Axis labels
        var ySteps = 4;
        ctx.strokeStyle = 'rgba(148, 163, 184, 0.2)';
        ctx.fillStyle = '#94a3b8';
        ctx.font = '11px system-ui, -apple-system, sans-serif';
        ctx.textAlign = 'right';
        ctx.textBaseline = 'middle';
        ctx.setLineDash([3, 3]);

        for (var k = 0; k <= ySteps; k++) {
            var val = minVal + ((maxVal - minVal) / ySteps) * k;
            var yPos = getY(val);
            ctx.beginPath();
            ctx.moveTo(padding.left, yPos);
            ctx.lineTo(width - padding.right, yPos);
            ctx.stroke();

            ctx.fillText(formatShortCurrency(val), padding.left - 8, yPos);
        }
        ctx.setLineDash([]); // Reset dash

        // Draw Zero Baseline if minVal < 0
        if (minVal < 0) {
            var zeroY = getY(0);
            ctx.strokeStyle = 'rgba(239, 68, 68, 0.4)';
            ctx.lineWidth = 1;
            ctx.beginPath();
            ctx.moveTo(padding.left, zeroY);
            ctx.lineTo(width - padding.right, zeroY);
            ctx.stroke();
        }

        // Draw X-axis date ticks (4 intervals)
        ctx.fillStyle = '#94a3b8';
        ctx.font = '10px system-ui, -apple-system, sans-serif';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'top';

        var xTicks = 4;
        for (var tIdx = 0; tIdx <= xTicks; tIdx++) {
            var tickT = tMin + ((tMax - tMin) / xTicks) * tIdx;
            var tickX = getX(tickT);
            var tickDate = new Date(tickT * 1000);
            var monthNames = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
            var tickLabel = monthNames[tickDate.getMonth()] + ' ' + tickDate.getFullYear();

            ctx.fillText(tickLabel, tickX, height - padding.bottom + 10);
        }

        // Draw line helper
        function drawSeries(points, valueKey, strokeColor, lineWidth, isDashed) {
            if (points.length === 0) return;

            ctx.strokeStyle = strokeColor;
            ctx.lineWidth = lineWidth || 2.5;
            ctx.lineJoin = 'round';
            ctx.lineCap = 'round';
            if (isDashed) {
                ctx.setLineDash([4, 4]);
            } else {
                ctx.setLineDash([]);
            }

            ctx.beginPath();
            for (var i = 0; i < points.length; i++) {
                var px = getX(points[i].timestamp);
                var py = getY(points[i][valueKey]);
                if (i === 0) {
                    ctx.moveTo(px, py);
                } else {
                    ctx.lineTo(px, py);
                }
            }
            ctx.stroke();
            ctx.setLineDash([]);
        }

        // 1. Total Net Worth (Subtle dashed reference line)
        drawSeries(visible, 'total', 'rgba(99, 102, 241, 0.5)', 1.75, true);

        // 2. Fixed Assets (Amber)
        drawSeries(visible, 'fixedAssets', '#f59e0b', 2.5, false);

        // 3. Cash & Savings (Emerald Green)
        drawSeries(visible, 'cashSavings', '#10b981', 2.5, false);

        // 4. Pensions (Purple)
        drawSeries(visible, 'pensions', '#8b5cf6', 2.5, false);

        // Map visible points to renderedPoints with canvas coordinates
        renderedPoints = visible.map(function (p) {
            return {
                timestamp: p.timestamp,
                dateStr: p.dateStr,
                pensions: p.pensions,
                cashSavings: p.cashSavings,
                fixedAssets: p.fixedAssets,
                total: p.total,
                x: getX(p.timestamp),
                yPensions: getY(p.pensions),
                yCash: getY(p.cashSavings),
                yFixed: getY(p.fixedAssets),
                yTotal: getY(p.total)
            };
        });
    }

    // Tooltip interaction
    function setupInteractions() {
        var canvas = document.getElementById('networth-chart');
        var container = document.getElementById('nw-chart-container');
        var tooltip = document.getElementById('nw-chart-tooltip');
        if (!canvas || !container || !tooltip) return;

        function handlePointer(clientX) {
            if (!renderedPoints || renderedPoints.length === 0) return;

            var rect = canvas.getBoundingClientRect();
            var mouseX = clientX - rect.left;

            // Find closest rendered point
            var bestPt = null;
            var bestDist = Infinity;

            for (var i = 0; i < renderedPoints.length; i++) {
                var pt = renderedPoints[i];
                var dist = Math.abs(pt.x - mouseX);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestPt = pt;
                }
            }

            if (!bestPt || bestDist > 80) {
                tooltip.style.display = 'none';
                return;
            }

            // Build tooltip HTML
            var html =
                '<div style="font-weight: 700; color: var(--text-main); margin-bottom: 5px; font-size: 0.85rem; border-bottom: 1px solid var(--border-color); padding-bottom: 3px;">' +
                '  <i class="far fa-calendar-alt" style="margin-right: 4px; color: var(--primary);"></i>' + (bestPt.dateStr || 'Date') +
                '</div>' +
                '<div style="display: flex; justify-content: space-between; gap: 14px; margin-bottom: 3px;">' +
                '  <span style="color: #8b5cf6;"><span style="display: inline-block; width: 8px; height: 8px; border-radius: 50%; background: #8b5cf6; margin-right: 5px;"></span>Pensions:</span>' +
                '  <strong>' + formatCurrency(bestPt.pensions) + '</strong>' +
                '</div>' +
                '<div style="display: flex; justify-content: space-between; gap: 14px; margin-bottom: 3px;">' +
                '  <span style="color: #10b981;"><span style="display: inline-block; width: 8px; height: 8px; border-radius: 50%; background: #10b981; margin-right: 5px;"></span>Cash &amp; Savings:</span>' +
                '  <strong>' + formatCurrency(bestPt.cashSavings) + '</strong>' +
                '</div>' +
                '<div style="display: flex; justify-content: space-between; gap: 14px; margin-bottom: 4px;">' +
                '  <span style="color: #f59e0b;"><span style="display: inline-block; width: 8px; height: 8px; border-radius: 50%; background: #f59e0b; margin-right: 5px;"></span>Fixed Assets:</span>' +
                '  <strong>' + formatCurrency(bestPt.fixedAssets) + '</strong>' +
                '</div>' +
                '<div style="border-top: 1px dashed var(--border-color); padding-top: 4px; margin-top: 4px; display: flex; justify-content: space-between; gap: 14px; font-weight: 800;">' +
                '  <span style="color: var(--primary);">Total Net Worth:</span>' +
                '  <span style="color: ' + (bestPt.total >= 0 ? 'var(--primary)' : 'var(--danger)') + ';">' + formatCurrency(bestPt.total) + '</span>' +
                '</div>';

            tooltip.innerHTML = html;
            tooltip.style.display = 'block';

            // Positioning
            var tipWidth = tooltip.offsetWidth || 180;
            var tipHeight = tooltip.offsetHeight || 130;
            var containerWidth = container.offsetWidth;

            var leftPos = bestPt.x + 15;
            if (leftPos + tipWidth > containerWidth - 10) {
                leftPos = bestPt.x - tipWidth - 15;
            }

            var topPos = Math.max(10, bestPt.yTotal - tipHeight / 2);
            if (topPos + tipHeight > container.offsetHeight - 10) {
                topPos = container.offsetHeight - tipHeight - 10;
            }

            tooltip.style.left = Math.max(10, leftPos) + 'px';
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

    // Range buttons
    function setupRangeButtons() {
        var buttons = document.querySelectorAll('.btn-nw-filter');
        var labelEl = document.getElementById('nw-chart-range-label');

        buttons.forEach(function (btn) {
            btn.addEventListener('click', function () {
                var range = btn.getAttribute('data-range');
                if (!range || range === currentRange) return;

                currentRange = range;

                // Update button styles
                buttons.forEach(function (b) {
                    var isSelected = b.getAttribute('data-range') === range;
                    b.style.background = isSelected ? 'var(--primary)' : 'transparent';
                    b.style.color = isSelected ? '#ffffff' : 'var(--text-muted)';
                    b.style.fontWeight = isSelected ? '700' : '600';
                });

                if (labelEl) {
                    labelEl.textContent = getRangeLabel(range);
                }

                renderChart();
            });
        });
    }

    // Debounced window resize
    var resizeTimer = null;
    window.addEventListener('resize', function () {
        clearTimeout(resizeTimer);
        resizeTimer = setTimeout(function () {
            renderChart();
        }, 80);
    });

    document.addEventListener('DOMContentLoaded', function () {
        if (window.netWorthHistory && Array.isArray(window.netWorthHistory)) {
            rawData = window.netWorthHistory;
        }

        setupRangeButtons();
        setupInteractions();
        renderChart();
    });
})();
