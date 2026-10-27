// MonMon Savings Plan Client Script (Plain JavaScript)
(function () {
    'use strict';

    function initSavingsPlan() {
        var slider = document.getElementById('savings-plan-slider');
        var sliderVal = document.getElementById('savings-plan-slider-val');
        var savingsAmountSpan = document.getElementById('savings-plan-amount');
        var sliderTargetInput = document.getElementById('savingsPercentageInput');

        var maxPercent = slider ? (parseInt(slider.getAttribute('data-max-percent'), 10) || 100) : 100;

        // Find incoming total from DOM
        var incomingTotalEl = document.getElementById('plan-incoming-total');
        var incomingTotal = 0;
        if (incomingTotalEl) {
            incomingTotal = parseFloat(incomingTotalEl.getAttribute('data-incoming')) || 0;
        } else {
            var allMetricBoxes = document.querySelectorAll('.metric-box');
            allMetricBoxes.forEach(function (b) {
                var label = b.textContent || '';
                if (label.indexOf('Monthly Income') !== -1) {
                    var match = label.match(/£([0-9]+(?:\.[0-9]+)?)/);
                    if (match) incomingTotal = parseFloat(match[1]);
                }
            });
        }

        // Initialize item rows for real-time proportional scaling
        var itemRows = document.querySelectorAll('.savings-item-row');
        var itemsData = [];
        var initialTotalAllocated = 0;

        for (var i = 0; i < itemRows.length; i++) {
            var row = itemRows[i];
            var baseMonthly = parseFloat(row.getAttribute('data-base-monthly')) || 0;
            var debt = parseFloat(row.getAttribute('data-debt')) || 0;
            var target = parseFloat(row.getAttribute('data-target')) || 0;
            var remaining = parseFloat(row.getAttribute('data-remaining')) || 0;
            var type = row.getAttribute('data-type') || 'SAVINGS_TARGET';

            initialTotalAllocated += baseMonthly;
            itemsData.push({
                row: row,
                baseMonthly: baseMonthly,
                debt: debt,
                target: target,
                remaining: remaining,
                type: type,
                monthlyEl: row.querySelector('.item-monthly-val'),
                durationEl: row.querySelector('.item-duration-cell') || row.querySelector('.item-duration-val')
            });
        }

        // Calculate baseline weights based on original proportions
        for (var k = 0; k < itemsData.length; k++) {
            if (initialTotalAllocated > 0) {
                itemsData[k].weight = itemsData[k].baseMonthly / initialTotalAllocated;
            } else if (itemsData.length > 0) {
                itemsData[k].weight = 1.0 / itemsData.length;
            } else {
                itemsData[k].weight = 0;
            }
        }

        function updateSliderAndScaleItems(percent) {
            if (percent > maxPercent) {
                percent = maxPercent;
                if (slider) slider.value = maxPercent;
            }

            if (sliderVal) {
                sliderVal.textContent = percent;
            }
            if (sliderTargetInput) {
                sliderTargetInput.value = percent;
            }

            var newTargetPot = (percent / 100) * incomingTotal;
            if (savingsAmountSpan) {
                savingsAmountSpan.textContent = '£' + newTargetPot.toFixed(2);
            }

            var newTotalAllocated = 0;

            // Scale every item in real-time according to original proportions
            for (var j = 0; j < itemsData.length; j++) {
                var item = itemsData[j];
                var scaledMonthly = 0;
                if (initialTotalAllocated > 0 || (itemsData.length > 0 && newTargetPot > 0)) {
                    scaledMonthly = Math.round(newTargetPot * item.weight * 100) / 100;
                }
                newTotalAllocated += scaledMonthly;

                if (item.monthlyEl) {
                    item.monthlyEl.textContent = '£' + scaledMonthly.toFixed(2);
                }

                if (item.durationEl) {
                    if (item.type === 'DEBT_PAYOFF') {
                        if (item.debt <= 0) {
                            item.durationEl.innerHTML = '<span class="badge-pill badge-success" style="font-size: 0.78rem;"><i class="fas fa-check"></i> Paid off (£0 debt)</span>';
                        } else if (scaledMonthly > 0) {
                            var months = Math.ceil(item.debt / scaledMonthly);
                            item.durationEl.innerHTML = '<span style="font-weight: 700; color: var(--text-main);">~' + months + ' month' + (months === 1 ? '' : 's') + '</span>' +
                                '<div style="font-size: 0.75rem; color: var(--text-muted);">to clear £' + item.debt.toFixed(2) + ' at £' + scaledMonthly.toFixed(2) + '/mo</div>';
                        } else {
                            item.durationEl.innerHTML = '<span style="color: var(--text-muted); font-size: 0.8rem;">No monthly payment</span>';
                        }
                    } else {
                        if (item.target > 0 && item.remaining <= 0) {
                            item.durationEl.innerHTML = '<span class="badge-pill badge-success" style="font-size: 0.78rem;"><i class="fas fa-check"></i> Target reached</span>';
                        } else if (scaledMonthly > 0 && item.target > 0) {
                            var targetMonths = Math.ceil(item.remaining / scaledMonthly);
                            item.durationEl.innerHTML = '<span style="font-weight: 700; color: var(--text-main);">~' + targetMonths + ' month' + (targetMonths === 1 ? '' : 's') + '</span>' +
                                '<div style="font-size: 0.75rem; color: var(--text-muted);">to reach £' + item.target.toFixed(2) + ' at £' + scaledMonthly.toFixed(2) + '/mo</div>';
                        } else if (scaledMonthly > 0) {
                            item.durationEl.innerHTML = '<span style="color: var(--text-muted); font-size: 0.8rem;">Ongoing savings (£' + scaledMonthly.toFixed(2) + '/mo)</span>';
                        } else {
                            item.durationEl.innerHTML = '<span style="color: var(--text-muted); font-size: 0.8rem;">Ongoing savings</span>';
                        }
                    }
                }
            }

            // Update summary metric boxes and footer in real-time
            var totalAllocatedEl = document.getElementById('total-allocated-val');
            var unallocatedEl = document.getElementById('unallocated-val');
            var formUnallocatedEl = document.getElementById('form-unallocated-val');
            var btnAllocateMax = document.getElementById('btn-allocate-max');
            var btnAllocateMaxLabel = document.getElementById('btn-allocate-max-label');
            var unallocatedAlert = document.getElementById('unallocated-alert');
            var footerTotalEl = document.getElementById('footer-total-allocated');
            var footerTargetPotEl = document.getElementById('footer-target-pot');

            if (totalAllocatedEl) {
                totalAllocatedEl.textContent = '£' + newTotalAllocated.toFixed(2);
            }
            if (footerTotalEl) {
                footerTotalEl.textContent = '£' + newTotalAllocated.toFixed(2);
            }
            if (footerTargetPotEl) {
                footerTargetPotEl.textContent = 'Target Pot: £' + newTargetPot.toFixed(2);
            }

            var unallocated = newTargetPot - newTotalAllocated;
            if (unallocatedEl) {
                unallocatedEl.textContent = '£' + Math.abs(unallocated).toFixed(2);
                unallocatedEl.style.color = unallocated < -0.01 ? 'var(--danger)' : 'var(--text-main)';
            }
            if (formUnallocatedEl) {
                formUnallocatedEl.textContent = '£' + unallocated.toFixed(2);
                formUnallocatedEl.style.color = unallocated < -0.01 ? 'var(--danger)' : 'var(--primary)';
            }
            if (btnAllocateMax) {
                var safeMax = Math.max(0, unallocated);
                btnAllocateMax.setAttribute('data-unallocated', safeMax.toFixed(2));
                if (btnAllocateMaxLabel) {
                    btnAllocateMaxLabel.textContent = '£' + safeMax.toFixed(2);
                }
            }
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

        if (slider) {
            slider.addEventListener('input', function () {
                updateSliderAndScaleItems(parseInt(slider.value, 10));
            });
        }

        // Live calculation in the "Add / Amend Item" form
        var itemTypeSelect = document.getElementById('item-type-select');
        var accountSelect = document.getElementById('item-account-select');
        var targetAmountInput = document.getElementById('item-target-amount');
        var monthlyAmountInput = document.getElementById('item-monthly-amount');
        var previewBox = document.getElementById('item-estimate-preview');
        var targetAmountGroup = document.getElementById('target-amount-group');
        var btnAllocateMax = document.getElementById('btn-allocate-max');

        // Wire "Allocate Max" button
        if (btnAllocateMax && monthlyAmountInput) {
            btnAllocateMax.addEventListener('click', function (e) {
                e.preventDefault();
                var unallocVal = parseFloat(btnAllocateMax.getAttribute('data-unallocated')) || 0;
                if (unallocVal > 0) {
                    monthlyAmountInput.value = unallocVal.toFixed(2);
                    updateEstimatePreview();
                }
            });
        }

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
                } else if (accType === 'DEBIT' || accType === 'DEBIT_SHARED_BILLS') {
                    debt = balance < 0 ? Math.abs(balance) : 0;
                } else {
                    debt = Math.max(0, limit - balance);
                }

                if (!selectedOption || !selectedOption.value || selectedOption.value === '0') {
                    previewBox.innerHTML = '<span style="color: var(--text-muted);"><i class="fas fa-info-circle"></i> Select a credit or overdraft account to estimate payoff duration.</span>';
                    return;
                }

                if (debt <= 0) {
                    if (accType === 'DEBIT' || accType === 'DEBIT_SHARED_BILLS') {
                        previewBox.innerHTML = '<span style="color: var(--success);"><i class="fas fa-check-circle"></i> <b>£0 overdraft</b> — Account balance is positive (£' + balance.toFixed(2) + ').</span>';
                    } else {
                        previewBox.innerHTML = '<span style="color: var(--success);"><i class="fas fa-check-circle"></i> <b>0 months</b> — Account debt is £0.00 (already paid off).</span>';
                    }
                } else if (monthly <= 0) {
                    var debtLabel = (accType === 'DEBIT' || accType === 'DEBIT_SHARED_BILLS') ? 'Current overdraft' : 'Current debt';
                    previewBox.innerHTML = '<span style="color: var(--text-main);"><i class="fas fa-clock"></i> ' + debtLabel + ': <b>£' + debt.toFixed(2) + '</b>. Enter a monthly allocation to estimate payoff duration.</span>';
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
                var isDebt = itemTypeSelect.value === 'DEBT_PAYOFF';
                var optgroups = accountSelect ? accountSelect.getElementsByTagName('optgroup') : [];
                for (var i = 0; i < optgroups.length; i++) {
                    var groupLabel = optgroups[i].getAttribute('label') || '';
                    if (isDebt && groupLabel.indexOf('Debt') === -1 && groupLabel.indexOf('Credit') === -1 && groupLabel.indexOf('Overdraft') === -1) {
                        optgroups[i].style.opacity = '0.5';
                    } else if (!isDebt && (groupLabel.indexOf('Debt') !== -1 || groupLabel.indexOf('Credit') !== -1 || groupLabel.indexOf('Overdraft') !== -1)) {
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
