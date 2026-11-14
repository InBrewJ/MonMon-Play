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
    var activeTimeSlice = 'this_month'; // 'this_month' | 'last_2_cycles' | 'last_6_cycles' | 'all'

    var cycleRanges = {
        nominalPayday: 1,
        thisMonth: null,
        last2Cycles: null,
        last6Cycles: null
    };

    // Cached live state for resetting from test mode
    var liveTransactions = [];
    var liveCategoryData = [];
    var isTestMode = false;
    var originalCategorySelectHtml = '';
    var originalStatTotal = '';
    var originalStatGeotagged = '';
    var originalStatCategory = '';
    var originalSpotsCount = '';

    function formatCategoryName(str) {
        if (!str) return 'General';
        var words = str.replace(/_/g, ' ').split(' ');
        for (var i = 0; i < words.length; i++) {
            words[i] = words[i].charAt(0).toUpperCase() + words[i].slice(1).toLowerCase();
        }
        return words.join(' ');
    }

    function buildCategoryHistogram(txs) {
        var map = {};
        var total = 0;
        for (var i = 0; i < txs.length; i++) {
            var raw = (txs[i].category || 'general').toLowerCase();
            if (raw.indexOf('transfer') !== -1) continue;
            if (!map[raw]) {
                map[raw] = {
                    category: formatCategoryName(raw),
                    rawCategory: raw,
                    count: 0,
                    total: 0
                };
            }
            map[raw].count += 1;
            map[raw].total += txs[i].amount;
            total += txs[i].amount;
        }
        var list = [];
        for (var k in map) {
            if (map.hasOwnProperty(k)) {
                var item = map[k];
                item.average = item.count > 0 ? (item.total / item.count) : 0;
                item.percentage = total > 0 ? (item.total / total) * 100 : 0;
                list.push(item);
            }
        }
        return list;
    }

    function buildFallbackCycleRanges(payday) {
        var now = new Date();
        var y = now.getFullYear();
        var m = now.getMonth(); // 0-indexed
        var d = now.getDate();

        function pad(n) { return n < 10 ? '0' + n : String(n); }
        function fmt(year, month, day) { return year + '-' + pad(month + 1) + '-' + pad(day); }

        var todayStr = fmt(y, m, d);
        var pDay = (payday && payday >= 1 && payday <= 31) ? payday : 1;

        var lastPayMonth = (d >= pDay) ? m : m - 1;
        var lastPayYear = y;
        if (lastPayMonth < 0) {
            lastPayMonth += 12;
            lastPayYear -= 1;
        }
        var thisMonthStart = fmt(lastPayYear, lastPayMonth, pDay);

        var l2Month = lastPayMonth - 1;
        var l2Year = lastPayYear;
        if (l2Month < 0) {
            l2Month += 12;
            l2Year -= 1;
        }
        var last2Start = fmt(l2Year, l2Month, pDay);

        var l6Month = lastPayMonth - 5;
        var l6Year = lastPayYear;
        while (l6Month < 0) {
            l6Month += 12;
            l6Year -= 1;
        }
        var last6Start = fmt(l6Year, l6Month, pDay);

        return {
            nominalPayday: pDay,
            thisMonth: { startDate: thisMonthStart, endDate: todayStr, label: thisMonthStart + ' – ' + todayStr },
            last2Cycles: { startDate: last2Start, endDate: todayStr, label: last2Start + ' – ' + todayStr },
            last6Cycles: { startDate: last6Start, endDate: todayStr, label: last6Start + ' – ' + todayStr }
        };
    }

    function isTransactionInSlice(tx, slice) {
        if (!slice || slice === 'all') return true;
        if (!tx || !tx.date) return true;

        var txDateStr = String(tx.date).substring(0, 10);
        var range = null;
        if (slice === 'this_month') range = cycleRanges.thisMonth;
        else if (slice === 'last_2_cycles') range = cycleRanges.last2Cycles;
        else if (slice === 'last_6_cycles') range = cycleRanges.last6Cycles;

        if (!range || !range.startDate || !range.endDate) return true;

        return txDateStr >= range.startDate && txDateStr <= range.endDate;
    }

    function getSlicedTransactions(txs, slice) {
        if (!txs || txs.length === 0) return [];
        if (!slice || slice === 'all') return txs.slice(0);
        return txs.filter(function(t) {
            return isTransactionInSlice(t, slice);
        });
    }

    function updateSliceButtonCounts(txs) {
        var countThisMonth = getSlicedTransactions(txs, 'this_month').length;
        var countLast2 = getSlicedTransactions(txs, 'last_2_cycles').length;
        var countLast6 = getSlicedTransactions(txs, 'last_6_cycles').length;
        var countAll = txs ? txs.length : 0;

        var elThisMonth = document.getElementById('count-slice-this-month');
        var elLast2 = document.getElementById('count-slice-last-2');
        var elLast6 = document.getElementById('count-slice-last-6');
        var elAll = document.getElementById('count-slice-all');

        if (elThisMonth) elThisMonth.textContent = countThisMonth;
        if (elLast2) elLast2.textContent = countLast2;
        if (elLast6) elLast6.textContent = countLast6;
        if (elAll) elAll.textContent = countAll;
    }

    function applyTimeSlice(slice) {
        activeTimeSlice = slice;

        // 1. Update button active states
        var buttons = document.querySelectorAll('.dig-timeslice-btn');
        for (var b = 0; b < buttons.length; b++) {
            if (buttons[b].getAttribute('data-slice') === activeTimeSlice) {
                buttons[b].classList.add('active');
            } else {
                buttons[b].classList.remove('active');
            }
        }

        // 2. Update subtitle dates and stat badge
        var periodDatesEl = document.getElementById('dig-period-dates-text');
        var periodBadge = document.getElementById('dig-stat-period-badge');
        var rangeLabel = '';
        var badgeLabel = '';

        if (activeTimeSlice === 'this_month') {
            rangeLabel = cycleRanges.thisMonth ? cycleRanges.thisMonth.label : 'This Month';
            badgeLabel = '(This Month)';
        } else if (activeTimeSlice === 'last_2_cycles') {
            rangeLabel = cycleRanges.last2Cycles ? cycleRanges.last2Cycles.label : 'Last 2 Pay Cycles';
            badgeLabel = '(Last 2 Cycles)';
        } else if (activeTimeSlice === 'last_6_cycles') {
            rangeLabel = cycleRanges.last6Cycles ? cycleRanges.last6Cycles.label : 'Last 6 Pay Cycles';
            badgeLabel = '(Last 6 Cycles)';
        } else {
            rangeLabel = 'All Recorded Transactions';
            badgeLabel = '(All-Time)';
        }

        if (periodDatesEl) periodDatesEl.textContent = 'Showing: ' + rangeLabel;
        if (periodBadge) periodBadge.textContent = badgeLabel;

        // 3. Filter transactions for the slice
        var sliced = getSlicedTransactions(allTransactions, activeTimeSlice);

        // 4. Empty slice banner handling
        var emptyBanner = document.getElementById('dig-timeslice-empty-banner');
        var emptySliceName = document.getElementById('dig-empty-slice-name');
        var emptySliceDates = document.getElementById('dig-empty-slice-dates');
        if (sliced.length === 0 && allTransactions.length > 0) {
            if (emptyBanner) emptyBanner.style.display = 'block';
            if (emptySliceName) emptySliceName.textContent = activeTimeSlice === 'this_month' ? 'This Month' : (activeTimeSlice === 'last_2_cycles' ? 'Last 2 Pay Cycles' : 'Last 6 Pay Cycles');
            if (emptySliceDates) emptySliceDates.textContent = rangeLabel;
        } else {
            if (emptyBanner) emptyBanner.style.display = 'none';
        }

        // 5. Rebuild histogram for this slice
        categoryData = buildCategoryHistogram(sliced);
        categoryData.sort(function(a, b) {
            return sortMode === 'count' ? (b.count - a.count) : (b.total - a.total);
        });
        renderHistogramBars(categoryData, sortMode);

        // 6. Update Category Select options for this slice
        var catSelect = document.getElementById('dig-category-select');
        if (catSelect) {
            var optHtml = '<option value="ALL">All Categories (' + sliced.length + ')</option>';
            for (var c = 0; c < categoryData.length; c++) {
                optHtml += '<option value="' + categoryData[c].rawCategory + '">' + categoryData[c].category + ' (' + categoryData[c].count + ')</option>';
            }
            catSelect.innerHTML = optHtml;

            // Retain active category if it exists in new slice, else reset to ALL
            var categoryStillExists = categoryData.some(function(item) {
                return item.rawCategory === activeCategoryFilter || item.category === activeCategoryFilter;
            });
            if (!categoryStillExists) {
                activeCategoryFilter = 'ALL';
            }
            catSelect.value = activeCategoryFilter;
        }

        // 7. Update Quick Stat Cards
        var totalSpent = 0;
        for (var i = 0; i < sliced.length; i++) {
            totalSpent += sliced[i].amount;
        }
        var statTotal = document.getElementById('dig-stat-total');
        var statGeotagged = document.getElementById('dig-stat-geotagged');
        var statCategory = document.getElementById('dig-stat-category');
        var spotsCount = document.getElementById('dig-spots-count');

        if (statTotal) statTotal.textContent = '£' + totalSpent.toFixed(2);
        if (statGeotagged) statGeotagged.textContent = String(sliced.length);
        if (statCategory) statCategory.textContent = categoryData.length > 0 ? categoryData[0].category : 'None';
        if (spotsCount) spotsCount.textContent = '(' + sliced.length + ' geotagged spots)';

        // 8. Filter by category if selected and render on map
        var displayed = sliced;
        if (activeCategoryFilter && activeCategoryFilter !== 'ALL') {
            displayed = sliced.filter(function(t) {
                var cat = (t.category || 'general').toLowerCase().trim();
                return cat === activeCategoryFilter.toLowerCase().trim();
            });
        }

        if (!map) {
            if (allTransactions.length > 0) {
                setupMap(displayed.length > 0 ? displayed : allTransactions);
            }
        } else {
            renderHeatmapAndMarkers(displayed);
            if (displayed.length > 0) {
                fitMapBounds(displayed);
            }
        }
    }

    function generateDynamicTestTransactions() {
        var tm = cycleRanges.thisMonth;
        var l2 = cycleRanges.last2Cycles;
        var l6 = cycleRanges.last6Cycles;

        var dThisMonth1 = tm ? tm.endDate : '2026-11-01'; // Harrods in This Month
        var dThisMonth2 = tm ? tm.startDate : '2026-10-24'; // Apple in This Month
        var dLast2 = l2 ? l2.startDate : '2026-09-25'; // Ritz in Last 2 Cycles
        var dLast2_2 = l2 ? l2.startDate : '2026-09-28'; // Milkman in Last 2 Cycles
        var dLast6 = l6 ? l6.startDate : '2026-05-25'; // Vatican in Last 6 Cycles
        var dAll = '2025-08-15'; // Older in All

        return [
            {
                id: 9901,
                lat: 51.4994,
                lng: -0.1632,
                amount: 1850.00,
                merchant: "Harrods Knightsbridge",
                category: "shopping",
                emoji: "🛍️",
                address: "87-135 Brompton Rd, Knightsbridge, London SW1X 7XL, UK",
                date: dThisMonth1
            },
            {
                id: 9902,
                lat: 51.5133,
                lng: -0.1408,
                amount: 1299.00,
                merchant: "Apple Regent Street",
                category: "shopping",
                emoji: "💻",
                address: "235 Regent St, Mayfair, London W1B 2EL, UK",
                date: dThisMonth2
            },
            {
                id: 9903,
                lat: 51.5070,
                lng: -0.1416,
                amount: 650.00,
                merchant: "The Ritz Restaurant",
                category: "eating_out",
                emoji: "🍽️",
                address: "150 Piccadilly, St. James's, London W1J 9BR, UK",
                date: dLast2
            },
            {
                id: 9904,
                lat: 55.9508,
                lng: -3.1895,
                amount: 4.20,
                merchant: "The Milkman Coffee",
                category: "eating_out",
                emoji: "☕",
                address: "7 Cockburn St, Edinburgh EH1 1BP, Scotland, UK",
                date: dLast2_2
            },
            {
                id: 9905,
                lat: 41.9029,
                lng: 12.4534,
                amount: 18500.00,
                merchant: "Vatican Apostolic Archive & Sistine Tour",
                category: "entertainment",
                emoji: "🏛️",
                address: "Cortile del Belvedere, 00120 Città del Vaticano, Vatican City",
                date: dLast6
            },
            {
                id: 9906,
                lat: 51.5033,
                lng: -0.1195,
                amount: 45.00,
                merchant: "London Eye River Cruise",
                category: "entertainment",
                emoji: "🎡",
                address: "Riverside Building, County Hall, London SE1 7PB, UK",
                date: dAll
            }
        ];
    }

    function initDig() {
        // Retrieve embedded JSON data from script tag
        var dataScript = document.getElementById('dig-data');
        var rawPayload = {};
        if (dataScript) {
            try {
                rawPayload = JSON.parse(dataScript.textContent);
            } catch (e) {
                console.error('Failed to parse dig-data payload:', e);
            }
        }

        if (rawPayload.cycles) {
            cycleRanges = rawPayload.cycles;
        } else {
            cycleRanges = buildFallbackCycleRanges(1);
        }

        allTransactions = (rawPayload.transactions || []).filter(function(t) {
            var c = (t.category || '').toLowerCase();
            return c.indexOf('transfer') === -1;
        });
        categoryData = (rawPayload.histogram || []).filter(function(h) {
            var c = (h.rawCategory || h.category || '').toLowerCase();
            return c.indexOf('transfer') === -1;
        });
        if (categoryData.length === 0 && allTransactions.length > 0) {
            categoryData = buildCategoryHistogram(allTransactions);
            categoryData.sort(function(a, b) { return b.total - a.total; });
        }
        liveTransactions = allTransactions.slice(0);
        liveCategoryData = categoryData.slice(0);

        // Cache initial DOM values for resetting
        var statTotal = document.getElementById('dig-stat-total');
        var statGeotagged = document.getElementById('dig-stat-geotagged');
        var statCategory = document.getElementById('dig-stat-category');
        var spotsCount = document.getElementById('dig-spots-count');
        var catSelect = document.getElementById('dig-category-select');

        if (statTotal) originalStatTotal = statTotal.textContent;
        if (statGeotagged) originalStatGeotagged = statGeotagged.textContent;
        if (statCategory) originalStatCategory = statCategory.textContent;
        if (spotsCount) originalSpotsCount = spotsCount.textContent;
        if (catSelect) originalCategorySelectHtml = catSelect.innerHTML;

        updateSliceButtonCounts(allTransactions);
        initHistogram(categoryData);
        setupControls();

        if (allTransactions.length > 0) {
            // Apply default time slice ('this_month')
            applyTimeSlice('this_month');
        }
    }

    function setupMap(transactions) {
        var mapEl = document.getElementById('dig-map');
        if (!mapEl) return;
        if (map !== null) {
            renderHeatmapAndMarkers(transactions);
            if (transactions.length > 0) {
                fitMapBounds(transactions);
            }
            return;
        }

        // Determine center from first transaction or UK default
        var initialLat = (transactions.length > 0 && transactions[0].lat) ? transactions[0].lat : 51.5074;
        var initialLng = (transactions.length > 0 && transactions[0].lng) ? transactions[0].lng : -0.1278;

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

        if (transactions.length > 0) {
            fitMapBounds(transactions);
        }
    }

    function fitMapBounds(transactions) {
        if (!map || !transactions || transactions.length === 0) return;
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
        // Clear tiering: small spend (<£25) green, large spend (£500-£2500) orange, huge spend (>=£5000) red
        if (amount < 25) return '#10b981'; // Green
        if (amount < 150) return '#84cc16'; // Lime
        if (amount < 500) return '#eab308'; // Amber
        if (amount < 2500) return '#f97316'; // Orange (Large amount)
        return '#ef4444';                   // Red (Very large spend)
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

        if (!transactions || transactions.length === 0) {
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
        var heatPoints = [];
        for (var j = 0; j < transactions.length; j++) {
            var tx = transactions[j];
            if (typeof tx.lat === 'number' && typeof tx.lng === 'number') {
                var normalizedWeight;
                if (maxAmount > 50) {
                    var logAmt = Math.log10(Math.max(tx.amount, 1));
                    var logMax = Math.log10(Math.max(maxAmount, 1));
                    normalizedWeight = Math.min(Math.max(logAmt / logMax, 0.18), 1.0);
                } else {
                    normalizedWeight = Math.min(Math.max(tx.amount / maxAmount, 0.18), 1.0);
                }
                heatPoints.push([tx.lat, tx.lng, normalizedWeight]);

                // Interactive circle marker with popup
                var markerColor = getHeatColor(tx.amount, maxAmount);
                var circleMarker = L.circleMarker([tx.lat, tx.lng], {
                    radius: tx.amount >= 5000 ? 10 : 7,
                    fillColor: markerColor,
                    color: '#ffffff',
                    weight: 2,
                    opacity: 0.95,
                    fillOpacity: 0.85
                });

                var emojiHtml = tx.emoji ? '<span style="font-size: 1.3rem; margin-right: 6px;">' + tx.emoji + '</span>' : '';
                var amountFormatted = '£' + parseFloat(tx.amount).toFixed(2);
                var categoryFormatted = tx.category ? formatCategoryName(tx.category) : 'General';
                var addressHtml = tx.address ? '<div style="margin-top: 4px; color: #64748b; font-size: 0.8rem;"><i class="fas fa-map-marker-alt"></i> ' + tx.address + '</div>' : '';

                var popupContent = '<div style="font-family: inherit; min-width: 180px; padding: 4px;">' +
                    '<div style="display: flex; align-items: center; justify-content: space-between; margin-bottom: 4px;">' +
                    '<div style="font-weight: 700; font-size: 0.95rem; display: flex; align-items: center;">' + emojiHtml + (tx.merchant || 'Monzo Purchase') + '</div>' +
                    '<span style="font-weight: 700; color: ' + markerColor + '; font-size: 1rem; margin-left: 8px;">' + amountFormatted + '</span>' +
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

        heatLayer = L.heatLayer(heatPoints, {
            radius: 28,
            blur: 18,
            maxZoom: 16,
            minOpacity: 0.25,
            gradient: {
                0.2: '#10b981', // green (good / low spend)
                0.45: '#84cc16', // lime
                0.65: '#f59e0b', // amber
                0.85: '#f97316', // orange (large spend)
                1.0: '#ef4444'  // red (very large spend)
            }
        }).addTo(map);
    }

    function toggleTestMode() {
        var testBtn = document.getElementById('btn-dig-test');
        var testBtnText = document.getElementById('dig-test-btn-text');
        var emptyCard = document.getElementById('dig-empty-state-card');
        var mapCard = document.getElementById('dig-map-card');
        var timesliceCard = document.getElementById('dig-timeslice-card');

        isTestMode = !isTestMode;

        if (isTestMode) {
            // Activate Test Mode
            if (testBtn) {
                testBtn.style.background = 'var(--primary)';
                testBtn.style.color = '#ffffff';
                testBtn.style.borderColor = 'var(--primary)';
            }
            if (testBtnText) {
                testBtnText.textContent = 'Exit Test Mode';
            }

            // Show map and timeslice cards, hide empty state
            if (emptyCard) emptyCard.style.display = 'none';
            if (mapCard) mapCard.style.display = 'block';
            if (timesliceCard) timesliceCard.style.display = 'block';

            allTransactions = generateDynamicTestTransactions();
            updateSliceButtonCounts(allTransactions);
            applyTimeSlice(activeTimeSlice);

            setTimeout(function() {
                if (map) {
                    map.invalidateSize();
                    var currentDisplayed = getSlicedTransactions(allTransactions, activeTimeSlice);
                    fitMapBounds(currentDisplayed.length > 0 ? currentDisplayed : allTransactions);
                }
            }, 150);
        } else {
            // Exit Test Mode - Restore Live Data
            if (testBtn) {
                testBtn.style.background = 'var(--card-bg)';
                testBtn.style.color = 'var(--text-main)';
                testBtn.style.borderColor = 'var(--border-color)';
            }
            if (testBtnText) {
                testBtnText.textContent = 'Test';
            }

            allTransactions = liveTransactions.slice(0);
            updateSliceButtonCounts(allTransactions);

            if (allTransactions.length === 0) {
                if (mapCard) mapCard.style.display = 'none';
                if (timesliceCard) timesliceCard.style.display = 'none';
                if (emptyCard) emptyCard.style.display = 'block';
            } else {
                if (emptyCard) emptyCard.style.display = 'none';
                if (mapCard) mapCard.style.display = 'block';
                if (timesliceCard) timesliceCard.style.display = 'block';
                applyTimeSlice(activeTimeSlice);
            }
        }
    }

    function setupControls() {
        // Time Slice buttons
        var sliceButtons = document.querySelectorAll('.dig-timeslice-btn');
        for (var i = 0; i < sliceButtons.length; i++) {
            sliceButtons[i].addEventListener('click', function() {
                var slice = this.getAttribute('data-slice');
                if (slice) {
                    applyTimeSlice(slice);
                }
            });
        }

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
                var sliced = getSlicedTransactions(allTransactions, activeTimeSlice);
                renderHeatmapAndMarkers(sliced);
                if (sliced.length > 0) fitMapBounds(sliced);
                updateHistogramActiveBar(null);
            });
        }

        // Test Data buttons
        var btnTest = document.getElementById('btn-dig-test');
        if (btnTest) {
            btnTest.addEventListener('click', toggleTestMode);
        }
        var btnTestEmpty = document.getElementById('btn-dig-test-empty');
        if (btnTestEmpty) {
            btnTestEmpty.addEventListener('click', toggleTestMode);
        }
    }

    function filterTransactions(category) {
        activeCategoryFilter = category;
        var sliced = getSlicedTransactions(allTransactions, activeTimeSlice);
        var filtered = sliced;
        if (category && category !== 'ALL') {
            filtered = sliced.filter(function(tx) {
                var c = (tx.category || 'General').toLowerCase().trim();
                return c === category.toLowerCase().trim();
            });
        }
        renderHeatmapAndMarkers(filtered);
        if (filtered.length > 0) {
            fitMapBounds(filtered);
        }
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
                '<p style="margin: 0;">No category transaction data available for this time slice.</p>' +
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

        // Clicking a histogram row highlights / filters the map above
        var rows = container.querySelectorAll('.hist-row');
        for (var k = 0; k < rows.length; k++) {
            rows[k].addEventListener('click', function() {
                var cat = this.getAttribute('data-category');
                var catSelect = document.getElementById('dig-category-select');
                if (activeCategoryFilter === cat) {
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
