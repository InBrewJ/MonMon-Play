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

        tabBtns.forEach(function (btn) {
            btn.addEventListener('click', function () {
                tabBtns.forEach(function (b) {
                    b.classList.remove('active');
                    b.style.borderBottom = 'none';
                    b.style.fontWeight = '500';
                    b.style.color = 'var(--text-muted)';
                });
                btn.classList.add('active');
                btn.style.borderBottom = '2px solid var(--primary)';
                btn.style.fontWeight = '700';
                btn.style.color = 'var(--primary)';

                var tab = btn.getAttribute('data-tab');
                applyTabFilter(tab);
            });
        });

        // Apply initial filter (checking URL ?tab= parameter or defaulting to 'active')
        var urlParams = new URLSearchParams(window.location.search);
        var initialTab = urlParams.get('tab') || 'active';
        var initialBtn = document.querySelector('.outgoings-tab-btn[data-tab="' + initialTab + '"]');
        if (initialBtn) {
            tabBtns.forEach(function (b) {
                b.classList.remove('active');
                b.style.borderBottom = 'none';
                b.style.fontWeight = '500';
                b.style.color = 'var(--text-muted)';
            });
            initialBtn.classList.add('active');
            initialBtn.style.borderBottom = '2px solid var(--primary)';
            initialBtn.style.fontWeight = '700';
            initialBtn.style.color = 'var(--primary)';
        }
        applyTabFilter(initialTab);

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
