/**
 * MonMon Dig - Spending Heatmap and Histogram
 */
(function() {
    'use strict';

    var map = null;
    var heatLayer = null;
    var markersLayer = null;
    var allTransactions = [];
    var categoryData = [];
    var activeCategoryFilter = 'ALL';
    var sortMode = 'spend'; // 'spend' or 'count'

    function initDig() {
        var mapContainer = document.getElementById('dig-map');
        if (!mapContainer) return;

        // Retrieve embedded JSON data from script tag
        var dataScript = document.getElementById('dig-data');
        if (!dataScript) return;

        var rawPayload = {};
        try {
            rawPayload = JSON.parse(dataScript.textContent);
        } catch (e) {
            console.error('Failed to parse dig-data payload:', e);
            return;
        }

        allTransactions = rawPayload.transactions || [];
        categoryData = rawPayload.histogram || [];

        if (allTransactions.length === 0) {
            // Nothing to render on map, empty state is displayed by template
            initHistogram(categoryData);
            return;
        }

        setupMap(allTransactions);
        initHistogram(categoryData);
        setupControls();
    }

    function setupMap(transactions) {
        var mapEl = document.getElementById('dig-map');
        if (!mapEl) return;

        // Determine center from first transaction or UK default
        var initialLat = transactions[0].lat || 51.5074;
        var initialLng = transactions[0].lng || -0.1278;

        map = L.map('dig-map', {
            zoomControl: true,
            scrollWheelZoom: true
        }).setView([initialLat, initialLng], 12);

        // Free OpenStreetMap tile layer
        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
            maxZoom: 19,
            attribution: '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a> contributors'
        }).addTo(map);

        markersLayer = L.layerGroup().addTo(map);

        renderHeatmapAndMarkers(transactions);

        // Auto-fit bounds to include all transactions
        fitMapBounds(transactions);
    }

    function fitMapBounds(transactions) {
        if (!map || transactions.length === 0) return;
        var latLngs = [];
        for (var i = 0; i < transactions.length; i++) {
            var t = transactions[i];
            if (typeof t.lat === 'number' && typeof t.lng === 'number') {
                latLngs.push([t.lat, t.lng]);
            }
        }
        if (latLngs.length > 0) {
            var bounds = L.latLngBounds(latLngs);
            map.fitBounds(bounds, { padding: [40, 40], maxZoom: 15 });
        }
    }

    function getHeatColor(amount, maxAmount) {
        var ratio = maxAmount > 0 ? Math.min(amount / maxAmount, 1.0) : 0.5;
        if (ratio < 0.25) return '#10b981'; // Green
        if (ratio < 0.5) return '#eab308';  // Yellow
        if (ratio < 0.75) return '#f97316'; // Orange
        return '#ef4444';                   // Red
    }

    function renderHeatmapAndMarkers(transactions) {
        if (!map) return;

        // Clear existing layers
        if (heatLayer) {
            map.removeLayer(heatLayer);
            heatLayer = null;
        }
        if (markersLayer) {
            markersLayer.clearLayers();
        }

        if (transactions.length === 0) {
            return;
        }

        // Calculate max amount for normalization
        var maxAmount = 1.0;
        for (var i = 0; i < transactions.length; i++) {
            if (transactions[i].amount > maxAmount) {
                maxAmount = transactions[i].amount;
            }
        }

        // Prepare heatmap points: [lat, lng, normalized intensity]
        // "The larger the sum, the redder the heat. Green is good on the heatmap."
        var heatPoints = [];
        for (var j = 0; j < transactions.length; j++) {
            var tx = transactions[j];
            if (typeof tx.lat === 'number' && typeof tx.lng === 'number') {
                // Non-linear weight so large purchases pop out as red, while small ones stay green
                var normalizedWeight = Math.min(Math.max(tx.amount / maxAmount, 0.15), 1.0);
                heatPoints.push([tx.lat, tx.lng, normalizedWeight]);

                // Also add an interactive circle marker with popup
                var markerColor = getHeatColor(tx.amount, maxAmount);
                var circleMarker = L.circleMarker([tx.lat, tx.lng], {
                    radius: 7,
                    fillColor: markerColor,
                    color: '#ffffff',
                    weight: 1.5,
                    opacity: 0.9,
                    fillOpacity: 0.8
                });

                var emojiHtml = tx.emoji ? '<span style="font-size: 1.3rem; margin-right: 6px;">' + tx.emoji + '</span>' : '';
                var amountFormatted = '£' + parseFloat(tx.amount).toFixed(2);
                var categoryFormatted = tx.category ? tx.category.replace('_', ' ') : 'General';
                var addressHtml = tx.address ? '<div style="margin-top: 4px; color: #64748b; font-size: 0.8rem;"><i class="fas fa-map-marker-alt"></i> ' + tx.address + '</div>' : '';

                var popupContent = '<div style="font-family: inherit; min-width: 180px; padding: 4px;">' +
                    '<div style="display: flex; align-items: center; justify-content: space-between; margin-bottom: 4px;">' +
                    '<div style="font-weight: 700; font-size: 0.95rem; display: flex; align-items: center;">' + emojiHtml + (tx.merchant || 'Monzo Purchase') + '</div>' +
                    '<span style="font-weight: 700; color: ' + markerColor + '; font-size: 1rem;">' + amountFormatted + '</span>' +
                    '</div>' +
                    '<div style="display: flex; align-items: center; justify-content: space-between; font-size: 0.82rem; color: #475569;">' +
                    '<span style="background: rgba(99,102,241,0.1); color: #6366f1; padding: 2px 6px; border-radius: 4px; text-transform: capitalize;">' + categoryFormatted + '</span>' +
                    '<span>' + (tx.date || '') + '</span>' +
                    '</div>' +
                    addressHtml +
                    '</div>';

                circleMarker.bindPopup(popupContent);
                markersLayer.addLayer(circleMarker);
            }
        }

        // Green -> Yellow -> Orange -> Red
        heatLayer = L.heatLayer(heatPoints, {
            radius: 28,
            blur: 18,
            maxZoom: 16,
            minOpacity: 0.25,
            gradient: {
                0.2: '#10b981', // green (good)
                0.45: '#84cc16', // lime
                0.65: '#f59e0b', // amber
                0.85: '#f97316', // orange
                1.0: '#ef4444'  // red (large sum)
            }
        }).addTo(map);
    }

    function setupControls() {
        // Toggle Heatmap layer
        var toggleHeat = document.getElementById('toggle-heat');
        if (toggleHeat) {
            toggleHeat.addEventListener('change', function() {
                if (heatLayer) {
                    if (this.checked) {
                        map.addLayer(heatLayer);
                    } else {
                        map.removeLayer(heatLayer);
                    }
                }
            });
        }

        // Toggle Markers layer
        var toggleMarkers = document.getElementById('toggle-markers');
        if (toggleMarkers) {
            toggleMarkers.addEventListener('change', function() {
                if (markersLayer) {
                    if (this.checked) {
                        map.addLayer(markersLayer);
                    } else {
                        map.removeLayer(markersLayer);
                    }
                }
            });
        }

        // Category filter selector
        var catSelect = document.getElementById('dig-category-select');
        if (catSelect) {
            catSelect.addEventListener('change', function() {
                var selectedCat = this.value;
                activeCategoryFilter = selectedCat;
                filterTransactions(selectedCat);
            });
        }

        // Reset View button
        var resetBtn = document.getElementById('btn-reset-view');
        if (resetBtn) {
            resetBtn.addEventListener('click', function() {
                if (catSelect) catSelect.value = 'ALL';
                activeCategoryFilter = 'ALL';
                renderHeatmapAndMarkers(allTransactions);
                fitMapBounds(allTransactions);
                updateHistogramActiveBar(null);
            });
        }
    }

    function filterTransactions(category) {
        var filtered = allTransactions;
        if (category && category !== 'ALL') {
            filtered = allTransactions.filter(function(tx) {
                var c = (tx.category || 'General').toLowerCase().trim();
                return c === category.toLowerCase().trim();
            });
        }
        renderHeatmapAndMarkers(filtered);
        fitMapBounds(filtered);
        updateHistogramActiveBar(category);
    }

    function initHistogram(data) {
        var histContainer = document.getElementById('dig-histogram-bars');
        if (!histContainer) return;

        renderHistogramBars(data, sortMode);

        var btnSortSpend = document.getElementById('btn-sort-spend');
        var btnSortCount = document.getElementById('btn-sort-count');

        if (btnSortSpend && btnSortCount) {
            btnSortSpend.addEventListener('click', function() {
                sortMode = 'spend';
                btnSortSpend.classList.add('active');
                btnSortCount.classList.remove('active');
                renderHistogramBars(categoryData, 'spend');
            });

            btnSortCount.addEventListener('click', function() {
                sortMode = 'count';
                btnSortCount.classList.add('active');
                btnSortSpend.classList.remove('active');
                renderHistogramBars(categoryData, 'count');
            });
        }
    }

    var CATEGORY_COLORS = {
        'groceries': '#10b981',
        'eating_out': '#f59e0b',
        'transport': '#3b82f6',
        'bills': '#ef4444',
        'shopping': '#ec4899',
        'entertainment': '#8b5cf6',
        'finances': '#6366f1',
        'general': '#64748b',
        'expenses': '#06b6d4',
        'holidays': '#14b8a6',
        'personal_care': '#f43f5e',
        'fuel': '#f97316',
        'dining': '#f59e0b',
        'fun': '#a855f7',
        'health': '#10b981'
    };

    var CATEGORY_ICONS = {
        'groceries': 'fa-shopping-basket',
        'eating_out': 'fa-utensils',
        'transport': 'fa-subway',
        'bills': 'fa-file-invoice-dollar',
        'shopping': 'fa-tshirt',
        'entertainment': 'fa-film',
        'finances': 'fa-coins',
        'general': 'fa-tag',
        'expenses': 'fa-receipt',
        'holidays': 'fa-plane',
        'personal_care': 'fa-spa',
        'fuel': 'fa-gas-pump',
        'dining': 'fa-hamburger',
        'fun': 'fa-gamepad',
        'health': 'fa-heartbeat'
    };

    function renderHistogramBars(data, sort) {
        var container = document.getElementById('dig-histogram-bars');
        if (!container) return;

        if (!data || data.length === 0) {
            container.innerHTML = '<div style="padding: 24px; text-align: center; color: var(--text-muted);">' +
                '<i class="fas fa-chart-bar" style="font-size: 2rem; margin-bottom: 8px; opacity: 0.5;"></i>' +
                '<p style="margin: 0;">No category transaction data available from Monzo yet.</p>' +
                '</div>';
            return;
        }

        var sorted = data.slice(0);
        if (sort === 'count') {
            sorted.sort(function(a, b) { return b.count - a.count; });
        } else {
            sorted.sort(function(a, b) { return b.total - a.total; });
        }

        var maxVal = 1;
        for (var i = 0; i < sorted.length; i++) {
            var val = (sort === 'count') ? sorted[i].count : sorted[i].total;
            if (val > maxVal) maxVal = val;
        }

        var html = '';
        for (var j = 0; j < sorted.length; j++) {
            var item = sorted[j];
            var key = (item.rawCategory || item.category || 'general').toLowerCase().trim();
            var color = CATEGORY_COLORS[key] || '#6366f1';
            var iconClass = CATEGORY_ICONS[key] || 'fa-tag';
            var barValue = (sort === 'count') ? item.count : item.total;
            var pctOfMax = Math.round((barValue / maxVal) * 100);
            var isCurrentActive = (activeCategoryFilter.toLowerCase() === key || activeCategoryFilter.toLowerCase() === item.category.toLowerCase());

            html += '<div class="hist-row ' + (isCurrentActive ? 'hist-row-active' : '') + '" data-category="' + (item.rawCategory || item.category) + '" style="cursor: pointer; padding: 8px 12px; border-radius: 8px; transition: background 0.15s ease; margin-bottom: 6px;">' +
                '<div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; font-size: 0.88rem;">' +
                '<div style="display: flex; align-items: center; gap: 8px; font-weight: 600;">' +
                '<span style="display: inline-flex; align-items: center; justify-content: center; width: 26px; height: 26px; border-radius: 6px; background: ' + color + '1e; color: ' + color + '; font-size: 0.85rem;"><i class="fas ' + iconClass + '"></i></span>' +
                '<span>' + item.category + '</span>' +
                '<span style="color: var(--text-muted); font-size: 0.78rem; font-weight: 400;">(' + item.count + ' ' + (item.count === 1 ? 'tx' : 'txs') + ')</span>' +
                '</div>' +
                '<div style="display: flex; align-items: baseline; gap: 10px;">' +
                '<span style="font-weight: 700; color: var(--text-main); font-size: 0.95rem;">£' + parseFloat(item.total).toFixed(2) + '</span>' +
                '<span style="color: var(--text-muted); font-size: 0.8rem; min-width: 45px; text-align: right;">' + parseFloat(item.percentage).toFixed(1) + '%</span>' +
                '</div>' +
                '</div>' +
                '<div style="background: var(--table-hover, rgba(255,255,255,0.06)); height: 10px; border-radius: 999px; overflow: hidden; position: relative;">' +
                '<div style="background: ' + color + '; height: 100%; width: ' + Math.max(pctOfMax, 2) + '%; border-radius: 999px; transition: width 0.4s cubic-bezier(0.16, 1, 0.3, 1);"></div>' +
                '</div>' +
                '</div>';
        }

        container.innerHTML = html;

        // Clicking a histogram row highlights / filters the map above!
        var rows = container.querySelectorAll('.hist-row');
        for (var k = 0; k < rows.length; k++) {
            rows[k].addEventListener('click', function() {
                var cat = this.getAttribute('data-category');
                var catSelect = document.getElementById('dig-category-select');
                if (activeCategoryFilter === cat) {
                    // Toggle off if already selected
                    activeCategoryFilter = 'ALL';
                    if (catSelect) catSelect.value = 'ALL';
                    filterTransactions('ALL');
                } else {
                    activeCategoryFilter = cat;
                    if (catSelect) catSelect.value = cat;
                    filterTransactions(cat);
                }
            });
        }
    }

    function updateHistogramActiveBar(category) {
        var container = document.getElementById('dig-histogram-bars');
        if (!container) return;
        var rows = container.querySelectorAll('.hist-row');
        for (var i = 0; i < rows.length; i++) {
            var rowCat = rows[i].getAttribute('data-category');
            if (category && category !== 'ALL' && rowCat && rowCat.toLowerCase() === category.toLowerCase()) {
                rows[i].classList.add('hist-row-active');
            } else {
                rows[i].classList.remove('hist-row-active');
            }
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initDig);
    } else {
        initDig();
    }
})();
