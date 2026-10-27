// MonMon Savings Plan Client Script (Plain JavaScript)
// Features: Real-time proportional scaling, interactive segmented pot bar, self-balancing sliders & lock mechanics
(function () {
    'use strict';

    var SLICE_COLORS = [
        '#3b82f6', // blue
        '#8b5cf6', // purple
        '#10b981', // emerald
        '#f59e0b', // amber
        '#ec4899', // pink
        '#06b6d4', // cyan
        '#6366f1', // indigo
        '#14b8a6', // teal
        '#f97316', // orange
        '#84cc16'  // lime
    ];

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
        }

        // Initialize item rows for real-time proportional scaling & self-balancing
        var itemRows = document.querySelectorAll('.savings-item-row');
        var itemsData = [];
        var initialTotalAllocated = 0;

        for (var i = 0; i < itemRows.length; i++) {
            var row = itemRows[i];
            var id = row.getAttribute('data-id');
            var name = row.getAttribute('data-name') || ('Item ' + (i + 1));
            var priority = parseInt(row.getAttribute('data-priority'), 10) || (i + 1);
            var isLocked = row.getAttribute('data-locked') === 'true';
            var baseMonthly = parseFloat(row.getAttribute('data-base-monthly')) || 0;
            var debt = parseFloat(row.getAttribute('data-debt')) || 0;
            var target = parseFloat(row.getAttribute('data-target')) || 0;
            var remaining = parseFloat(row.getAttribute('data-remaining')) || 0;
            var type = row.getAttribute('data-type') || 'SAVINGS_TARGET';
            var color = SLICE_COLORS[i % SLICE_COLORS.length];

            initialTotalAllocated += baseMonthly;
            itemsData.push({
                id: id,
                row: row,
                name: name,
                priority: priority,
                isLocked: isLocked,
                currentMonthly: baseMonthly,
                baseMonthly: baseMonthly,
                debt: debt,
                target: target,
                remaining: remaining,
                type: type,
                color: color,
                monthlyEl: row.querySelector('.item-monthly-val'),
                durationEl: row.querySelector('.item-duration-cell') || row.querySelector('.item-duration-val'),
                sliceSlider: row.querySelector('.item-slice-slider')
            });
        }

        // Initial target pot
        var currentPercent = slider ? parseInt(slider.value, 10) : 0;
        var currentTargetPot = (currentPercent / 100) * incomingTotal;

        // Render the Segmented Pot Bar and Legend
        var potBarEl = document.getElementById('pot-segmented-bar');
        var potLegendEl = document.getElementById('pot-bar-legend');
        var saveAllocationsForm = document.getElementById('save-allocations-form');
        var btnSaveAllocations = document.getElementById('btn-save-allocations');

        function updateDurationProjection(item, monthlyVal) {
            if (!item.durationEl) return;

            if (item.type === 'DEBT_PAYOFF') {
                if (item.debt <= 0) {
                    item.durationEl.innerHTML = '<span class="badge-pill badge-success" style="font-size: 0.78rem;"><i class="fas fa-check"></i> Paid off (£0 debt)</span>';
                } else if (monthlyVal > 0) {
                    var months = Math.ceil(item.debt / monthlyVal);
                    item.durationEl.innerHTML = '<span style="font-weight: 700; color: var(--text-main);">~' + months + ' month' + (months === 1 ? '' : 's') + '</span>' +
                        '<div style="font-size: 0.75rem; color: var(--text-muted);">to clear £' + item.debt.toFixed(2) + ' at £' + monthlyVal.toFixed(2) + '/mo</div>';
                } else {
                    item.durationEl.innerHTML = '<span style="color: var(--text-muted); font-size: 0.8rem;">No monthly payment</span>';
                }
            } else {
                if (item.target > 0 && item.remaining <= 0) {
                    item.durationEl.innerHTML = '<span class="badge-pill badge-success" style="font-size: 0.78rem;"><i class="fas fa-check"></i> Target reached</span>';
                } else if (monthlyVal > 0 && item.target > 0) {
                    var targetMonths = Math.ceil(item.remaining / monthlyVal);
                    item.durationEl.innerHTML = '<span style="font-weight: 700; color: var(--text-main);">~' + targetMonths + ' month' + (targetMonths === 1 ? '' : 's') + '</span>' +
                        '<div style="font-size: 0.75rem; color: var(--text-muted);">to reach £' + item.target.toFixed(2) + ' at £' + monthlyVal.toFixed(2) + '/mo</div>';
                } else if (monthlyVal > 0) {
                    item.durationEl.innerHTML = '<span style="color: var(--text-muted); font-size: 0.8rem;">Ongoing savings (£' + monthlyVal.toFixed(2) + '/mo)</span>';
                } else {
                    item.durationEl.innerHTML = '<span style="color: var(--text-muted); font-size: 0.8rem;">Ongoing savings</span>';
                }
            }
        }

        function renderPotBar(targetPot) {
            if (!potBarEl || itemsData.length === 0) return;

            potBarEl.innerHTML = '';
            if (potLegendEl) potLegendEl.innerHTML = '';

            var totalAllocated = 0;
            for (var i = 0; i < itemsData.length; i++) {
                totalAllocated += itemsData[i].currentMonthly;
            }

            var safeTarget = targetPot > 0 ? targetPot : 1;

            for (var j = 0; j < itemsData.length; j++) {
                var itm = itemsData[j];
                var amt = itm.currentMonthly;
                var pct = targetPot > 0 ? Math.round((amt / safeTarget) * 100) : 0;
                var widthPct = targetPot > 0 ? (amt / safeTarget) * 100 : 0;

                if (widthPct > 0.5) {
                    var seg = document.createElement('div');
                    seg.className = 'pot-segment';
                    seg.style.width = widthPct.toFixed(2) + '%';
                    seg.style.backgroundColor = itm.color;
                    seg.title = 'P' + itm.priority + ' ' + itm.name + ': £' + amt.toFixed(2) + ' (' + pct + '%)' + (itm.isLocked ? ' [LOCKED]' : '');

                    if (widthPct > 8) {
                        seg.innerHTML = (itm.isLocked ? '<i class="fas fa-lock" style="font-size: 0.65rem; margin-right: 3px;"></i>' : '') +
                            'P' + itm.priority + ' ' + (widthPct > 15 ? '£' + amt.toFixed(0) : '');
                    }
                    potBarEl.appendChild(seg);
                }

                // Legend chip
                if (potLegendEl) {
                    var chip = document.createElement('div');
                    chip.className = 'pot-legend-chip';
                    chip.innerHTML = '<span class="pot-legend-dot" style="background-color: ' + itm.color + ';"></span>' +
                        '<span>P' + itm.priority + ' ' + itm.name + ': <b>£' + amt.toFixed(2) + '</b> <span style="color: var(--text-muted); font-weight: 400;">(' + pct + '%)</span></span>' +
                        (itm.isLocked ? ' <i class="fas fa-lock" style="color: #f59e0b; font-size: 0.72rem;" title="Locked amount"></i>' : '');
                    potLegendEl.appendChild(chip);
                }
            }

            // Unallocated slice if any
            var unallocated = targetPot - totalAllocated;
            if (unallocated > 0.01 && targetPot > 0) {
                var unallocPct = (unallocated / safeTarget) * 100;
                var unallocSeg = document.createElement('div');
                unallocSeg.className = 'pot-segment';
                unallocSeg.style.width = unallocPct.toFixed(2) + '%';
                unallocSeg.style.background = 'repeating-linear-gradient(45deg, rgba(148, 163, 184, 0.25), rgba(148, 163, 184, 0.25) 10px, rgba(148, 163, 184, 0.4) 10px, rgba(148, 163, 184, 0.4) 20px)';
                unallocSeg.style.color = 'var(--text-muted)';
                unallocSeg.title = 'Unallocated Buffer: £' + unallocated.toFixed(2) + ' (' + Math.round(unallocPct) + '%)';
                if (unallocPct > 10) {
                    unallocSeg.textContent = '£' + unallocated.toFixed(0) + ' unallocated';
                }
                potBarEl.appendChild(unallocSeg);
            }
        }

        function markAllocationsChanged() {
            if (!saveAllocationsForm || !btnSaveAllocations) return;

            saveAllocationsForm.innerHTML = '';
            var csrfInput = document.querySelector('input[name="csrfToken"]');
            if (csrfInput) {
                var hiddenCsrf = document.createElement('input');
                hiddenCsrf.type = 'hidden';
                hiddenCsrf.name = 'csrfToken';
                hiddenCsrf.value = csrfInput.value;
                saveAllocationsForm.appendChild(hiddenCsrf);
            }

            for (var i = 0; i < itemsData.length; i++) {
                var itm = itemsData[i];
                var inp = document.createElement('input');
                inp.type = 'hidden';
                inp.name = 'amount_' + itm.id;
                inp.value = itm.currentMonthly.toFixed(2);
                saveAllocationsForm.appendChild(inp);
            }

            btnSaveAllocations.style.display = 'inline-block';
        }

        if (btnSaveAllocations && saveAllocationsForm) {
            btnSaveAllocations.addEventListener('click', function () {
                saveAllocationsForm.submit();
            });
        }

        // Self-Balancing Engine when a single item slider moves
        function handleItemSliderChange(changedItem, newVal) {
            var targetPot = currentTargetPot;
            if (targetPot <= 0 || itemsData.length <= 1) {
                changedItem.currentMonthly = newVal;
                if (changedItem.monthlyEl) changedItem.monthlyEl.textContent = '£' + newVal.toFixed(2);
                updateDurationProjection(changedItem, newVal);
                renderPotBar(targetPot);
                markAllocationsChanged();
                return;
            }

            // Calculate locked sum
            var lockedTotal = 0;
            var otherUnlocked = [];
            for (var i = 0; i < itemsData.length; i++) {
                var itm = itemsData[i];
                if (itm.id === changedItem.id) continue;
                if (itm.isLocked) {
                    lockedTotal += itm.currentMonthly;
                } else {
                    otherUnlocked.push(itm);
                }
            }

            var maxAllowedForChanged = Math.max(0, targetPot - lockedTotal);
            if (newVal > maxAllowedForChanged) {
                newVal = maxAllowedForChanged;
                if (changedItem.sliceSlider) changedItem.sliceSlider.value = newVal;
            }

            var oldVal = changedItem.currentMonthly;
            var delta = newVal - oldVal;
            changedItem.currentMonthly = Math.round(newVal * 100) / 100;
            if (changedItem.monthlyEl) changedItem.monthlyEl.textContent = '£' + changedItem.currentMonthly.toFixed(2);
            updateDurationProjection(changedItem, changedItem.currentMonthly);

            if (otherUnlocked.length > 0) {
                var otherTotal = 0;
                for (var j = 0; j < otherUnlocked.length; j++) {
                    otherTotal += otherUnlocked[j].currentMonthly;
                }

                var remainingBudget = Math.max(0, targetPot - (lockedTotal + changedItem.currentMonthly));
                var runningSum = 0;

                for (var k = 0; k < otherUnlocked.length; k++) {
                    var other = otherUnlocked[k];
                    if (k === otherUnlocked.length - 1) {
                        // Reconcile penny drift on the last unlocked item
                        other.currentMonthly = Math.max(0, Math.round((remainingBudget - runningSum) * 100) / 100);
                    } else {
                        var share = 0;
                        if (otherTotal > 0) {
                            share = Math.round(((other.currentMonthly / otherTotal) * remainingBudget) * 100) / 100;
                        } else {
                            share = Math.round((remainingBudget / otherUnlocked.length) * 100) / 100;
                        }
                        other.currentMonthly = Math.max(0, share);
                        runningSum += other.currentMonthly;
                    }

                    if (other.monthlyEl) other.monthlyEl.textContent = '£' + other.currentMonthly.toFixed(2);
                    if (other.sliceSlider) other.sliceSlider.value = other.currentMonthly;
                    updateDurationProjection(other, other.currentMonthly);
                }
            }

            // Update footer totals
            var newAllocated = 0;
            for (var m = 0; m < itemsData.length; m++) {
                newAllocated += itemsData[m].currentMonthly;
            }
            var totalAllocatedEl = document.getElementById('total-allocated-val');
            var footerTotalEl = document.getElementById('footer-total-allocated');
            if (totalAllocatedEl) totalAllocatedEl.textContent = '£' + newAllocated.toFixed(2);
            if (footerTotalEl) footerTotalEl.textContent = '£' + newAllocated.toFixed(2);

            renderPotBar(targetPot);
            markAllocationsChanged();
        }

        // Attach listeners to item sliders
        for (var s = 0; s < itemsData.length; s++) {
            (function (itm) {
                if (itm.sliceSlider) {
                    itm.sliceSlider.addEventListener('input', function () {
                        handleItemSliderChange(itm, parseFloat(itm.sliceSlider.value) || 0);
                    });
                }
            })(itemsData[s]);
        }

        // Scale all items when top savings target slider moves
        function updateSliderAndScaleItems(percent) {
            if (percent > maxPercent) {
                percent = maxPercent;
                if (slider) slider.value = maxPercent;
            }

            if (sliderVal) sliderVal.textContent = percent;
            if (sliderTargetInput) sliderTargetInput.value = percent;

            var newTargetPot = (percent / 100) * incomingTotal;
            currentTargetPot = newTargetPot;

            if (savingsAmountSpan) {
                savingsAmountSpan.textContent = '£' + newTargetPot.toFixed(2);
            }

            var barTargetPotEl = document.getElementById('bar-target-pot-display');
            if (barTargetPotEl) {
                barTargetPotEl.textContent = newTargetPot.toFixed(2);
            }

            // Self-balancing: keep locked items fixed, scale unlocked items proportionally
            var lockedTotal = 0;
            var unlockedItems = [];
            for (var i = 0; i < itemsData.length; i++) {
                var itm = itemsData[i];
                if (itm.isLocked) {
                    lockedTotal += itm.currentMonthly;
                } else {
                    unlockedItems.push(itm);
                }
            }

            var budgetForUnlocked = Math.max(0, newTargetPot - lockedTotal);
            var prevUnlockedSum = 0;
            for (var u = 0; u < unlockedItems.length; u++) {
                prevUnlockedSum += unlockedItems[u].currentMonthly;
            }

            var runningSum = 0;
            for (var k = 0; k < unlockedItems.length; k++) {
                var item = unlockedItems[k];
                if (k === unlockedItems.length - 1) {
                    item.currentMonthly = Math.max(0, Math.round((budgetForUnlocked - runningSum) * 100) / 100);
                } else {
                    var weight = prevUnlockedSum > 0 ? (item.currentMonthly / prevUnlockedSum) : (1.0 / unlockedItems.length);
                    var scaledMonthly = Math.round(budgetForUnlocked * weight * 100) / 100;
                    item.currentMonthly = Math.max(0, scaledMonthly);
                    runningSum += item.currentMonthly;
                }

                if (item.monthlyEl) item.monthlyEl.textContent = '£' + item.currentMonthly.toFixed(2);
                if (item.sliceSlider) {
                    item.sliceSlider.max = newTargetPot;
                    item.sliceSlider.value = item.currentMonthly;
                }
                updateDurationProjection(item, item.currentMonthly);
            }

            // Locked items sliders max updated
            for (var l = 0; l < itemsData.length; l++) {
                if (itemsData[l].isLocked && itemsData[l].sliceSlider) {
                    itemsData[l].sliceSlider.max = newTargetPot;
                }
            }

            var newTotalAllocated = 0;
            for (var j = 0; j < itemsData.length; j++) {
                newTotalAllocated += itemsData[j].currentMonthly;
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

            if (totalAllocatedEl) totalAllocatedEl.textContent = '£' + newTotalAllocated.toFixed(2);
            if (footerTotalEl) footerTotalEl.textContent = '£' + newTotalAllocated.toFixed(2);
            if (footerTargetPotEl) footerTargetPotEl.textContent = 'Target Pot: £' + newTargetPot.toFixed(2);

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

            renderPotBar(newTargetPot);
        }

        if (slider) {
            slider.addEventListener('input', function () {
                updateSliderAndScaleItems(parseInt(slider.value, 10));
            });
        }

        // Initial pot bar render
        renderPotBar(currentTargetPot);

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
            if (!previewBox || !itemTypeSelect) return;

            var type = itemTypeSelect.value;
            var monthly = monthlyAmountInput ? parseFloat(monthlyAmountInput.value) || 0 : 0;
            var selectedOption = accountSelect && accountSelect.selectedIndex >= 0 ? accountSelect.options[accountSelect.selectedIndex] : null;

            var limit = selectedOption ? parseFloat(selectedOption.getAttribute('data-limit')) || 0 : 0;
            var balance = selectedOption ? parseFloat(selectedOption.getAttribute('data-balance')) || 0 : 0;
            var accType = selectedOption ? selectedOption.getAttribute('data-type') || '' : '';

            if (type === 'DEBT_PAYOFF') {
                if (targetAmountGroup) targetAmountGroup.style.display = 'none';

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
                if (targetAmountGroup) targetAmountGroup.style.display = 'block';

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

        if (accountSelect) accountSelect.addEventListener('change', updateEstimatePreview);
        if (targetAmountInput) targetAmountInput.addEventListener('input', updateEstimatePreview);
        if (monthlyAmountInput) monthlyAmountInput.addEventListener('input', updateEstimatePreview);

        updateEstimatePreview();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initSavingsPlan);
    } else {
        initSavingsPlan();
    }
})();
