// MonMon Savings Plan Client Script (Plain JavaScript)
(function () {
    'use strict';

    function initSavingsPlan() {
        var slider = document.getElementById('savings-plan-slider');
        var sliderVal = document.getElementById('savings-plan-slider-val');
        var savingsAmountSpan = document.getElementById('savings-plan-amount');
        var sliderTargetInput = document.getElementById('savingsPercentageInput');
        var incomingTotalEl = document.getElementById('plan-incoming-total');

        var incomingTotal = incomingTotalEl ? parseFloat(incomingTotalEl.getAttribute('data-incoming')) || 0 : 0;

        function updateSliderUI(percent) {
            if (sliderVal) {
                sliderVal.textContent = percent;
            }
            if (sliderTargetInput) {
                sliderTargetInput.value = percent;
            }
            if (savingsAmountSpan) {
                var amount = ((percent / 100) * incomingTotal).toFixed(2);
                savingsAmountSpan.textContent = '£' + amount;
            }

            // Also update live unallocated preview if element exists
            var totalAllocatedEl = document.getElementById('total-allocated-val');
            var unallocatedEl = document.getElementById('unallocated-val');
            var unallocatedAlert = document.getElementById('unallocated-alert');
            if (totalAllocatedEl && unallocatedEl) {
                var totalAllocated = parseFloat(totalAllocatedEl.getAttribute('data-allocated')) || 0;
                var currentTargetPot = (percent / 100) * incomingTotal;
                var unallocated = currentTargetPot - totalAllocated;
                unallocatedEl.textContent = '£' + Math.abs(unallocated).toFixed(2);
                if (unallocatedAlert) {
                    if (unallocated < -0.01) {
                        unallocatedAlert.className = 'w3-panel w3-round w3-pale-red w3-border-red';
                        unallocatedAlert.innerHTML = '<i class="fas fa-exclamation-triangle"></i> Sliced allocations exceed monthly target by <b>£' + Math.abs(unallocated).toFixed(2) + '</b>';
                    } else {
                        unallocatedAlert.className = 'w3-panel w3-round w3-pale-green w3-border-green';
                        unallocatedAlert.innerHTML = '<i class="fas fa-check-circle"></i> <b>£' + unallocated.toFixed(2) + '</b> remaining to slice';
                    }
                }
            }
        }

        if (slider) {
            slider.addEventListener('input', function () {
                updateSliderUI(parseInt(slider.value, 10));
            });
            updateSliderUI(parseInt(slider.value, 10));
        }

        // Live calculation in the "Add Item" form
        var itemTypeSelect = document.getElementById('item-type-select');
        var accountSelect = document.getElementById('item-account-select');
        var targetAmountInput = document.getElementById('item-target-amount');
        var monthlyAmountInput = document.getElementById('item-monthly-amount');
        var previewBox = document.getElementById('item-estimate-preview');
        var targetAmountGroup = document.getElementById('target-amount-group');

        function updateEstimatePreview() {
            if (!previewBox || !itemTypeSelect) {
                return;
            }

            var type = itemTypeSelect.value;
            var monthly = monthlyAmountInput ? parseFloat(monthlyAmountInput.value) || 0 : 0;
            var selectedOption = accountSelect && accountSelect.selectedIndex >= 0 ? accountSelect.options[accountSelect.selectedIndex] : null;

            var limit = selectedOption ? parseFloat(selectedOption.getAttribute('data-limit')) || 0 : 0;
            var balance = selectedOption ? parseFloat(selectedOption.getAttribute('data-balance')) || 0 : 0;
            var accType = selectedOption ? selectedOption.getAttribute('data-type') || '' : '';

            if (type === 'DEBT_PAYOFF') {
                if (targetAmountGroup) {
                    targetAmountGroup.style.display = 'none';
                }
                var debt = 0;
                if (accType === 'LOAN') {
                    debt = Math.max(0, balance);
                } else {
                    debt = Math.max(0, limit - balance);
                }

                if (!selectedOption || !selectedOption.value || selectedOption.value === '0') {
                    previewBox.innerHTML = '<span style="color: var(--text-muted);"><i class="fas fa-info-circle"></i> Select a credit account to estimate rough payoff months.</span>';
                    return;
                }

                if (debt <= 0) {
                    previewBox.innerHTML = '<span style="color: var(--success);"><i class="fas fa-check-circle"></i> <b>0 months</b> — Account debt is £0.00 (already paid off).</span>';
                } else if (monthly <= 0) {
                    previewBox.innerHTML = '<span style="color: var(--text-main);"><i class="fas fa-clock"></i> Current debt: <b>£' + debt.toFixed(2) + '</b> (limit £' + limit.toFixed(2) + ', balance £' + balance.toFixed(2) + '). Enter a monthly allocation to estimate payoff duration.</span>';
                } else {
                    var months = Math.ceil(debt / monthly);
                    previewBox.innerHTML = '<span style="color: var(--primary); font-weight: 600;"><i class="fas fa-calculator"></i> Roughly <b>' + months + ' month' + (months === 1 ? '' : 's') + '</b> to pay off £' + debt.toFixed(2) + ' debt at £' + monthly.toFixed(2) + '/month</span>';
                }
            } else {
                if (targetAmountGroup) {
                    targetAmountGroup.style.display = 'block';
                }
                var target = targetAmountInput ? parseFloat(targetAmountInput.value) || 0 : 0;
                var remaining = Math.max(0, target - balance);

                if (target <= 0) {
                    if (monthly > 0) {
                        previewBox.innerHTML = '<span style="color: var(--text-main);"><i class="fas fa-piggy-bank"></i> Ongoing monthly savings of <b>£' + monthly.toFixed(2) + '</b> (no hard target specified).</span>';
                    } else {
                        previewBox.innerHTML = '<span style="color: var(--text-muted);"><i class="fas fa-info-circle"></i> Enter a target amount and monthly allocation to estimate duration.</span>';
                    }
                } else if (remaining <= 0) {
                    previewBox.innerHTML = '<span style="color: var(--success);"><i class="fas fa-check-circle"></i> <b>Target achieved!</b> Current balance (£' + balance.toFixed(2) + ') meets or exceeds target (£' + target.toFixed(2) + ').</span>';
                } else if (monthly <= 0) {
                    previewBox.innerHTML = '<span style="color: var(--text-main);"><i class="fas fa-clock"></i> Remaining to target: <b>£' + remaining.toFixed(2) + '</b> (target £' + target.toFixed(2) + ', current £' + balance.toFixed(2) + '). Enter monthly allocation to estimate months.</span>';
                } else {
                    var targetMonths = Math.ceil(remaining / monthly);
                    previewBox.innerHTML = '<span style="color: var(--primary); font-weight: 600;"><i class="fas fa-calculator"></i> Roughly <b>' + targetMonths + ' month' + (targetMonths === 1 ? '' : 's') + '</b> to reach target (£' + remaining.toFixed(2) + ' remaining of £' + target.toFixed(2) + ' at £' + monthly.toFixed(2) + '/month)</span>';
                }
            }
        }

        if (itemTypeSelect) {
            itemTypeSelect.addEventListener('change', function () {
                // Adjust account options view
                var isDebt = itemTypeSelect.value === 'DEBT_PAYOFF';
                var optgroups = accountSelect ? accountSelect.getElementsByTagName('optgroup') : [];
                for (var i = 0; i < optgroups.length; i++) {
                    var groupLabel = optgroups[i].getAttribute('label') || '';
                    if (isDebt && groupLabel.indexOf('Credit') === -1 && groupLabel.indexOf('Loan') === -1) {
                        optgroups[i].style.opacity = '0.5';
                    } else if (!isDebt && (groupLabel.indexOf('Credit') !== -1 || groupLabel.indexOf('Loan') !== -1)) {
                        optgroups[i].style.opacity = '0.5';
                    } else {
                        optgroups[i].style.opacity = '1.0';
                    }
                }
                updateEstimatePreview();
            });
        }

        if (accountSelect) {
            accountSelect.addEventListener('change', updateEstimatePreview);
        }
        if (targetAmountInput) {
            targetAmountInput.addEventListener('input', updateEstimatePreview);
        }
        if (monthlyAmountInput) {
            monthlyAmountInput.addEventListener('input', updateEstimatePreview);
        }

        updateEstimatePreview();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initSavingsPlan);
    } else {
        initSavingsPlan();
    }
})();
