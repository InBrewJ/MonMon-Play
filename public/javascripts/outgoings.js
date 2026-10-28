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

        function applyTabFilter(tabName) {
            var visibleCount = 0;

            function filterElement(el) {
                var isYearly = el.getAttribute('data-is-yearly') === 'true';
                var isDueNow = el.getAttribute('data-due-now') === 'true';
                var show = false;

                if (tabName === 'all') {
                    show = true;
                } else if (tabName === 'yearly') {
                    show = isYearly;
                } else {
                    // Default tab: 'active' / 'monthly' -> show monthly bills and yearly bills that roll around in current month
                    show = !isYearly || isDueNow;
                }

                if (show) {
                    el.style.display = '';
                    visibleCount++;
                } else {
                    el.style.display = 'none';
                }
            }

            for (var i = 0; i < desktopRows.length; i++) {
                filterElement(desktopRows[i]);
            }
            for (var j = 0; j < mobileCards.length; j++) {
                filterElement(mobileCards[j]);
            }

            if (emptyMsg) {
                if (visibleCount === 0) {
                    emptyMsg.style.display = 'block';
                    if (tabName === 'yearly') {
                        emptyMsg.textContent = 'No yearly bills recorded. Switch Frequency to "Yearly" in the form to add one.';
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

        // Form frequency toggle (Monthly vs Yearly)
        var freqSelect = document.getElementById('outgoing-frequency');
        var monthGroup = document.getElementById('yearly-month-group');
        var monthSelect = document.getElementById('outgoingMonth');

        if (freqSelect && monthGroup && monthSelect) {
            function updateFreqUI() {
                if (freqSelect.value === 'yearly') {
                    monthGroup.style.display = 'block';
                    if (!monthSelect.value || monthSelect.value === '0') {
                        var currentMonth = new Date().getMonth() + 1;
                        monthSelect.value = String(currentMonth);
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
