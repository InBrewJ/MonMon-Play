// MonMon Outgoings Client Script (Plain JavaScript)
// Handles switching tabs (Monthly & Due Now vs Yearly Bills vs All Outgoings)
// and toggling the Frequency (Monthly vs Yearly) selector in the add/edit form.
(function () {
    'use strict';

    function initOutgoings() {
        // Tab switching
        var tabBtns = document.querySelectorAll('.outgoings-tab-btn');
        var desktopRows = document.querySelectorAll('tr.outgoing-row');
        var mobileCards = document.querySelectorAll('.outgoing-card');
        var emptyMsg = document.getElementById('outgoings-empty-tab-msg');
        var tableBody = document.querySelector('#outgoings-table tbody');
        var mobileCardsContainer = document.querySelector('.mobile-cards-view');

        function sortContainerElements(container, isYearlyTab) {
            if (!container) return;
            var items = Array.prototype.slice.call(container.children);
            items.sort(function (a, b) {
                var aIsCalc = a.getAttribute('data-is-calculated') === 'true';
                var bIsCalc = b.getAttribute('data-is-calculated') === 'true';
                if (aIsCalc !== bIsCalc) {
                    return aIsCalc ? -1 : 1;
                }
                if (isYearlyTab) {
                    var aMonth = parseInt(a.getAttribute('data-outgoing-month'), 10) || 0;
                    var bMonth = parseInt(b.getAttribute('data-outgoing-month'), 10) || 0;
                    if (aMonth !== bMonth) {
                        return aMonth - bMonth;
                    }
                    var aDay = parseInt(a.getAttribute('data-outgoing-day'), 10) || 0;
                    var bDay = parseInt(b.getAttribute('data-outgoing-day'), 10) || 0;
                    if (aDay !== bDay) {
                        return aDay - bDay;
                    }
                } else {
                    var aDay = parseInt(a.getAttribute('data-outgoing-day'), 10) || 0;
                    var bDay = parseInt(b.getAttribute('data-outgoing-day'), 10) || 0;
                    if (aDay !== bDay) {
                        return aDay - bDay;
                    }
                }
                var aId = parseInt(a.getAttribute('data-outgoing-id'), 10) || 0;
                var bId = parseInt(b.getAttribute('data-outgoing-id'), 10) || 0;
                return aId - bId;
            });
            for (var k = 0; k < items.length; k++) {
                container.appendChild(items[k]);
            }
        }

        function applyTabFilter(tabName) {
            var visibleCount = 0;
            var configPanel = document.getElementById('yearly-bills-config-panel');
            if (configPanel) {
                configPanel.style.display = (tabName === 'yearly') ? 'block' : 'none';
            }

            var isYearlyTab = (tabName === 'yearly');
            sortContainerElements(tableBody, isYearlyTab);
            sortContainerElements(mobileCardsContainer, isYearlyTab);

            // Re-query in sorted order
            var currentDesktopRows = document.querySelectorAll('tr.outgoing-row');
            var currentMobileCards = document.querySelectorAll('.outgoing-card');

            function filterElement(el) {
                var isYearly = el.getAttribute('data-is-yearly') === 'true';
                var isDueNow = el.getAttribute('data-due-now') === 'true' || el.getAttribute('data-is-due-now') === 'true';
                var isPaused = el.getAttribute('data-is-paused') === 'true';
                var isCalculated = el.getAttribute('data-is-calculated') === 'true';
                var show = false;

                if (tabName === 'all') {
                    show = true;
                } else if (tabName === 'paused') {
                    show = isPaused && !isCalculated;
                } else if (tabName === 'yearly') {
                    show = isYearly && !isCalculated;
                } else {
                    // Default tab: 'active' / 'monthly' -> show monthly bills and yearly bills that roll around in current month
                    show = (!isPaused && (!isYearly || isDueNow || isCalculated));
                }

                if (show) {
                    el.style.display = '';
                    visibleCount++;
                } else {
                    el.style.display = 'none';
                }
            }

            for (var i = 0; i < currentDesktopRows.length; i++) {
                filterElement(currentDesktopRows[i]);
            }
            for (var j = 0; j < currentMobileCards.length; j++) {
                filterElement(currentMobileCards[j]);
            }

            if (typeof window.positionTodayDividers === 'function') {
                window.positionTodayDividers(tabName);
            }

            if (emptyMsg) {
                if (visibleCount === 0) {
                    emptyMsg.style.display = 'block';
                    if (tabName === 'yearly') {
                        emptyMsg.textContent = 'No yearly bills recorded. Switch Frequency to "Yearly" in the form to add one.';
                    } else if (tabName === 'paused') {
                        emptyMsg.textContent = 'No paused outgoings recorded. You can pause any outgoing using the pause button in its row.';
                    } else if (tabName === 'active') {
                        emptyMsg.textContent = 'No monthly or currently due outgoings found.';
                    } else {
                        emptyMsg.textContent = 'No outgoings recorded.';
                    }
                } else {
                    emptyMsg.style.display = 'none';
                }
            }
        }

        var TAB_TO_HASH = { active: 'monthly', monthly: 'monthly', yearly: 'yearly', paused: 'paused', all: 'all' };
        var HASH_TO_TAB = { monthly: 'active', active: 'active', yearly: 'yearly', paused: 'paused', all: 'all' };

        function normalizeTab(raw) {
            if (!raw) return null;
            return HASH_TO_TAB[String(raw).replace(/^#/, '').trim().toLowerCase()] || null;
        }

        function activateTab(tab, updateHash) {
            var normalized = normalizeTab(tab) || 'active';
            var hash = TAB_TO_HASH[normalized] || 'monthly';
            tabBtns.forEach(function (b) {
                var isMatch = b.getAttribute('data-tab') === normalized;
                b.classList.toggle('active', isMatch);
                b.style.borderBottom = isMatch ? '2px solid var(--primary)' : 'none';
                b.style.fontWeight = isMatch ? '700' : '500';
                b.style.color = isMatch ? 'var(--primary)' : 'var(--text-muted)';
            });
            applyTabFilter(normalized);
            if (updateHash !== false && typeof history !== 'undefined' && typeof history.replaceState === 'function') {
                var params = new URLSearchParams(window.location.search);
                params.delete('tab');
                var searchStr = params.toString();
                history.replaceState(null, '', window.location.pathname + (searchStr ? '?' + searchStr : '') + '#' + hash);
            }
        }

        tabBtns.forEach(function (btn) {
            btn.addEventListener('click', function () {
                activateTab(btn.getAttribute('data-tab'), true);
            });
        });

        // Apply initial filter (checking URL #hash or ?tab= parameter, defaulting to 'active')
        var urlParams = new URLSearchParams(window.location.search);
        var initialTab = normalizeTab(window.location.hash) || normalizeTab(urlParams.get('tab')) || 'active';
        activateTab(initialTab, true);

        window.addEventListener('hashchange', function () {
            var fromHash = normalizeTab(window.location.hash);
            if (fromHash) {
                activateTab(fromHash, false);
            }
        });

        // Form frequency toggle (Monthly vs Yearly)
        var freqSelect = document.getElementById('outgoing-frequency');
        var monthGroup = document.getElementById('yearly-month-group');
        var monthSelect = document.getElementById('outgoingMonth');

        if (freqSelect && monthGroup && monthSelect) {
            var intervalInput = document.getElementById('yearInterval');
            var startYearInput = document.getElementById('startingYear');

            function updateFreqUI() {
                if (freqSelect.value === 'yearly') {
                    monthGroup.style.display = 'block';
                    if (!monthSelect.value || monthSelect.value === '0') {
                        var currentMonth = new Date().getMonth() + 1;
                        monthSelect.value = String(currentMonth);
                    }
                    if (intervalInput && (!intervalInput.value || parseInt(intervalInput.value, 10) < 1)) {
                        intervalInput.value = '1';
                    }
                    if (startYearInput && (!startYearInput.value || parseInt(startYearInput.value, 10) < 1990)) {
                        startYearInput.value = String(new Date().getFullYear());
                    }
                } else {
                    monthGroup.style.display = 'none';
                    monthSelect.value = '';
                }
            }

            freqSelect.addEventListener('change', updateFreqUI);
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initOutgoings);
    } else {
        initOutgoings();
    }
})();
