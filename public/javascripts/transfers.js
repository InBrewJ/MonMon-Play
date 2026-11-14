(function () {
    'use strict';

    var STORAGE_PREFIX = 'monmon_payday_transfer_done_';

    window.switchWindowTab = function (tab) {
        var panelNext = document.getElementById('panel-next');
        var panelLast = document.getElementById('panel-last');
        var btnNext = document.getElementById('tab-btn-next');
        var btnLast = document.getElementById('tab-btn-last');

        if (!panelNext || !panelLast) return;

        if (tab === 'next') {
            panelNext.style.display = 'block';
            panelLast.style.display = 'none';
            if (btnNext) btnNext.classList.add('active');
            if (btnLast) btnLast.classList.remove('active');
        } else {
            panelNext.style.display = 'none';
            panelLast.style.display = 'block';
            if (btnNext) btnNext.classList.remove('active');
            if (btnLast) btnLast.classList.add('active');
        }

        try {
            var url = new URL(window.location.href);
            url.searchParams.set('tab', tab);
            window.history.replaceState({}, '', url.toString());
        } catch (e) {}
    };

    window.toggleBreakdown = function (breakdownId) {
        var el = document.getElementById('breakdown-' + breakdownId);
        var icon = document.getElementById('icon-' + breakdownId);
        if (!el) return;
        if (el.style.display === 'none' || !el.style.display) {
            el.style.display = 'block';
            if (icon) {
                icon.classList.remove('fa-chevron-down');
                icon.classList.add('fa-chevron-up');
            }
        } else {
            el.style.display = 'none';
            if (icon) {
                icon.classList.remove('fa-chevron-up');
                icon.classList.add('fa-chevron-down');
            }
        }
    };

    window.copyTransferAmount = function (amountStr, btn) {
        if (!navigator.clipboard) {
            var fallback = document.createElement('textarea');
            fallback.value = amountStr;
            document.body.appendChild(fallback);
            fallback.select();
            document.execCommand('copy');
            document.body.removeChild(fallback);
            showCopiedFeedback(btn);
            return;
        }

        navigator.clipboard.writeText(amountStr)
            .then(function () {
                showCopiedFeedback(btn);
            })
            .catch(function (err) {
                console.warn('Could not copy to clipboard:', err);
            });
    };

    function showCopiedFeedback(btn) {
        if (!btn) return;
        var originalHtml = btn.innerHTML;
        btn.innerHTML = '<i class="fas fa-check" style="color: #10b981;"></i> Copied!';
        btn.style.borderColor = '#10b981';
        setTimeout(function () {
            btn.innerHTML = originalHtml;
            btn.style.borderColor = '';
        }, 1800);
    }

    window.toggleTransferDone = function (windowType, itemId) {
        var chk = document.getElementById('chk-' + windowType + '-' + itemId);
        var cardId = windowType === 'next' ? 'card-' + itemId : 'card-last-' + itemId;
        var card = document.getElementById(cardId);
        if (!chk) return;

        var key = STORAGE_PREFIX + windowType + '_' + itemId;
        if (chk.checked) {
            localStorage.setItem(key, 'true');
            if (card) card.classList.add('is-done');
        } else {
            localStorage.removeItem(key);
            if (card) card.classList.remove('is-done');
        }
        updateCheckedSummary(windowType);
    };

    window.clearAllChecks = function (windowType) {
        var prefix = STORAGE_PREFIX + windowType + '_';
        var toRemove = [];
        for (var i = 0; i < localStorage.length; i++) {
            var k = localStorage.key(i);
            if (k && k.indexOf(prefix) === 0) {
                toRemove.push(k);
            }
        }
        toRemove.forEach(function (k) {
            localStorage.removeItem(k);
        });

        var containerId = windowType === 'next' ? 'transfer-cards-container-next' : 'transfer-cards-container-last';
        var container = document.getElementById(containerId);
        if (container) {
            var checkboxes = container.querySelectorAll('input[type="checkbox"][id^="chk-"]');
            checkboxes.forEach(function (chk) {
                chk.checked = false;
            });
            var cards = container.querySelectorAll('.transfer-card');
            cards.forEach(function (card) {
                card.classList.remove('is-done');
            });
        }
        updateCheckedSummary(windowType);
    };

    function updateCheckedSummary(windowType) {
        var containerId = windowType === 'next' ? 'transfer-cards-container-next' : 'transfer-cards-container-last';
        var container = document.getElementById(containerId);
        var summaryEl = document.getElementById(windowType + '-checked-summary');
        var clearBtn = document.getElementById(windowType + '-clear-btn');
        if (!container) return;

        var checkboxes = container.querySelectorAll('input[type="checkbox"][id^="chk-"]');
        var total = checkboxes.length;
        var checkedCount = 0;
        checkboxes.forEach(function (chk) {
            if (chk.checked) checkedCount++;
        });

        if (summaryEl) {
            if (total === 0) {
                summaryEl.textContent = '';
                summaryEl.style.display = 'none';
            } else if (checkedCount === total) {
                summaryEl.innerHTML = '<i class="fas fa-check-double"></i> All ' + total + ' completed';
                summaryEl.style.display = 'inline-block';
            } else if (checkedCount > 0) {
                summaryEl.innerHTML = '<i class="fas fa-check"></i> ' + checkedCount + ' of ' + total + ' completed';
                summaryEl.style.display = 'inline-block';
            } else {
                summaryEl.textContent = '';
                summaryEl.style.display = 'none';
            }
        }

        if (clearBtn) {
            clearBtn.style.display = checkedCount > 0 ? 'inline-flex' : 'none';
        }
    }

    function initCardClickHandlers() {
        var cards = document.querySelectorAll('.transfer-card');
        cards.forEach(function (card) {
            // Prevent duplicate bindings
            if (card.dataset.hasClickListener) return;
            card.dataset.hasClickListener = 'true';

            card.addEventListener('click', function (e) {
                // If clicked inside interactive controls (buttons, links, form inputs, labels, tables), don't intercept
                if (e.target.closest('button') || e.target.closest('a') || e.target.closest('input') || e.target.closest('label') || e.target.closest('table') || e.target.closest('.breakdown-toggle-btn')) {
                    return;
                }
                var windowType = card.getAttribute('data-window');
                var itemId = card.getAttribute('data-item-id');
                if (windowType && itemId) {
                    var chk = document.getElementById('chk-' + windowType + '-' + itemId);
                    if (chk) {
                        chk.checked = !chk.checked;
                        window.toggleTransferDone(windowType, itemId);
                    }
                }
            });
        });
    }

    function restoreCheckboxStates() {
        var checkboxes = document.querySelectorAll('input[type="checkbox"][id^="chk-"]');
        checkboxes.forEach(function (chk) {
            var parts = chk.id.split('-'); // chk, windowType, itemId...
            if (parts.length >= 3) {
                var windowType = parts[1];
                var itemId = parts.slice(2).join('-');
                var key = STORAGE_PREFIX + windowType + '_' + itemId;
                var isDone = localStorage.getItem(key) === 'true';
                chk.checked = isDone;

                var cardId = windowType === 'next' ? 'card-' + itemId : 'card-last-' + itemId;
                var card = document.getElementById(cardId);
                if (card && isDone) {
                    card.classList.add('is-done');
                }
            }
        });
    }

    document.addEventListener('DOMContentLoaded', function () {
        restoreCheckboxStates();
        initCardClickHandlers();
        updateCheckedSummary('next');
        updateCheckedSummary('last');
    });
})();
