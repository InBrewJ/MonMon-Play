(function() {
    var GRUELLING_TOTAL_PENCE = 21667;

    function toPence(raw) {
        if (raw === null || raw === undefined || raw === '') {
            return 0;
        }
        var num = (typeof raw === 'number') ? raw : parseFloat(String(raw).trim());
        if (!isFinite(num) || num <= 0) {
            return 0;
        }
        return Math.round(num * 100);
    }

    function formatPence(pence) {
        var n = (typeof pence === 'number' && isFinite(pence)) ? Math.round(pence) : 0;
        if (n === 0) {
            return '0.00';
        }
        var sign = n < 0 ? '-' : '';
        return sign + (Math.abs(n) / 100).toFixed(2);
    }

    function calculateSectionsSummary(sections, maxAllowancePence) {
        var list = Array.isArray(sections) ? sections : [];
        var plannedPence = 0;
        for (var i = 0; i < list.length; i++) {
            var item = list[i];
            if (item && typeof item === 'object') {
                plannedPence += toPence(item.amount);
            }
        }
        var maxPence = (typeof maxAllowancePence === 'number' && isFinite(maxAllowancePence))
            ? Math.round(maxAllowancePence)
            : 0;
        var diffPence = maxPence - plannedPence;
        var isOver = diffPence < 0;
        var absDiffPence = Math.abs(diffPence);

        return {
            plannedPence: plannedPence,
            maxAllowancePence: maxPence,
            diffPence: diffPence,
            isOver: isOver,
            absDiffPence: absDiffPence,
            plannedFormatted: formatPence(plannedPence),
            maxFormatted: formatPence(maxPence),
            absDiffFormatted: formatPence(absDiffPence)
        };
    }

    window.MonMonMonthlyPot = {
        GRUELLING_TOTAL_PENCE: GRUELLING_TOTAL_PENCE,
        toPence: toPence,
        formatPence: formatPence,
        calculateSectionsSummary: calculateSectionsSummary
    };

    function initMonthlyPotPage() {
        var potConfigForm = document.getElementById('pot-config-form');
        if (!potConfigForm) {
            return;
        }

        var presetSelect = document.getElementById('pot-preset');
        var customFields = document.getElementById('custom-plan-fields');
        var customPlanIdInput = document.getElementById('custom-plan-id');
        var presetControlsBar = document.getElementById('preset-controls-bar');
        var presetStatusLabel = document.getElementById('preset-status-label');
        var templateSaveCheckboxWrap = document.getElementById('template-save-checkbox-wrap');
        var presetUpdateModeWrap = document.getElementById('preset-update-mode-wrap');
        var saveTemplateChk = document.getElementById('save-template-chk');
        var sectionsContainer = document.getElementById('sections-container');
        var sectionsJsonInput = document.getElementById('sections-json-input');
        var btnAddSection = document.getElementById('btn-add-section');
        var planNameInput = document.getElementById('plan-name-input');
        var btnSavePresetDirect = document.getElementById('btn-save-preset-direct');
        var btnDeletePresetDirect = document.getElementById('btn-delete-preset-direct');
        var liveSectionsTotalEl = document.getElementById('live-sections-total');
        var liveMaxAllowanceEl = document.getElementById('live-max-allowance');
        var liveAllowanceStatusEl = document.getElementById('live-allowance-status');
        var planLiveSummaryEl = document.getElementById('plan-live-summary');
        var gruellingSummaryEl = document.getElementById('gruelling-allowance-summary');

        var maxAllowancePence = parseInt(potConfigForm.getAttribute('data-max-allowance-pence') || '0', 10);
        if (!isFinite(maxAllowancePence)) {
            maxAllowancePence = 0;
        }

        var accountOptionsTpl = document.getElementById('pot-section-account-options');
        var accountOptionsHtml = accountOptionsTpl ? accountOptionsTpl.innerHTML : '<option value="0">-- No Account --</option>';

        var sectionsState = [
            { heading: 'Food', amount: 200, accountId: null },
            { heading: 'Fuel', amount: 50, accountId: null },
            { heading: 'Transport', amount: 40, accountId: null }
        ];

        function applyStatusStyles(el, summary) {
            if (!el) return;
            if (summary.isOver) {
                el.style.color = 'var(--danger, #ef4444)';
                el.innerHTML = '<i class="fas fa-exclamation-triangle" style="margin-right: 4px;"></i>Over max allowance by <strong>£' + summary.absDiffFormatted + '</strong>';
            } else if (summary.diffPence === 0) {
                el.style.color = 'var(--success, #16a34a)';
                el.innerHTML = '<i class="fas fa-check-circle" style="margin-right: 4px;"></i><strong>£0.00</strong> left — matches max allowance';
            } else {
                el.style.color = 'var(--success, #16a34a)';
                el.innerHTML = '<i class="fas fa-check-circle" style="margin-right: 4px;"></i>Left unallocated: <strong>£' + summary.absDiffFormatted + '</strong>';
            }
        }

        function updateLiveSummary() {
            var summary = calculateSectionsSummary(sectionsState, maxAllowancePence);
            if (liveSectionsTotalEl) {
                liveSectionsTotalEl.textContent = '£' + summary.plannedFormatted;
                liveSectionsTotalEl.style.color = summary.isOver ? 'var(--danger, #ef4444)' : 'var(--text-main)';
            }
            if (liveMaxAllowanceEl) {
                liveMaxAllowanceEl.textContent = '£' + summary.maxFormatted;
            }
            applyStatusStyles(liveAllowanceStatusEl, summary);
            if (planLiveSummaryEl) {
                planLiveSummaryEl.style.borderColor = summary.isOver
                    ? 'var(--danger, #ef4444)'
                    : 'var(--border-color)';
                planLiveSummaryEl.setAttribute('data-over-allowance', summary.isOver ? 'true' : 'false');
            }

            if (gruellingSummaryEl) {
                if (presetSelect && presetSelect.value === 'gruelling') {
                    var gSummary = calculateSectionsSummary([{ amount: GRUELLING_TOTAL_PENCE / 100 }], maxAllowancePence);
                    gruellingSummaryEl.style.display = 'block';
                    gruellingSummaryEl.style.borderColor = gSummary.isOver
                        ? 'var(--danger, #ef4444)'
                        : 'var(--border-color)';
                    var statusText = gSummary.isOver
                        ? '<span style="color: var(--danger, #ef4444); font-weight: 700;"><i class="fas fa-exclamation-triangle" style="margin-right: 4px;"></i>Over max allowance by £' + gSummary.absDiffFormatted + '</span>'
                        : '<span style="color: var(--success, #16a34a); font-weight: 700;"><i class="fas fa-check-circle" style="margin-right: 4px;"></i>£' + gSummary.absDiffFormatted + ' left unallocated</span>';
                    gruellingSummaryEl.innerHTML =
                        '<div style="display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 6px;">' +
                            '<span>Planned: <strong>£' + gSummary.plannedFormatted + '</strong> / £' + gSummary.maxFormatted + ' max</span>' +
                            statusText +
                        '</div>';
                } else {
                    gruellingSummaryEl.style.display = 'none';
                }
            }
        }

        function autoSelectNominatedAccounts() {
            var nominated = {};

            ['food_account_id', 'fun_account_id', 'transport_account_id', 'fuel_account_id'].forEach(function(id) {
                var el = document.getElementById(id);
                if (el && el.value && el.value !== '0') {
                    nominated[el.value] = true;
                }
            });

            if (sectionsState && Array.isArray(sectionsState)) {
                sectionsState.forEach(function(sec) {
                    if (sec && sec.accountId && sec.accountId > 0) {
                        nominated[String(sec.accountId)] = true;
                    }
                });
            }

            var domSecAccSelects = document.querySelectorAll('.sec-account-inp');
            for (var i = 0; i < domSecAccSelects.length; i++) {
                var val = domSecAccSelects[i].value;
                if (val && val !== '0') {
                    nominated[val] = true;
                }
            }

            for (var accId in nominated) {
                if (Object.prototype.hasOwnProperty.call(nominated, accId)) {
                    var chk = document.querySelector('input[name="accounts"][value="' + accId + '"]');
                    if (chk && !chk.checked) {
                        chk.checked = true;
                        var label = chk.closest ? chk.closest('label') : null;
                        if (label) {
                            label.style.transition = 'background-color 0.3s ease';
                            label.style.backgroundColor = 'rgba(99, 102, 241, 0.2)';
                            (function(lbl) {
                                setTimeout(function() {
                                    lbl.style.backgroundColor = '';
                                }, 600);
                            })(label);
                        }
                    }
                }
            }
        }

        ['food_account_id', 'fun_account_id', 'transport_account_id', 'fuel_account_id'].forEach(function(id) {
            var el = document.getElementById(id);
            if (el) {
                el.addEventListener('change', autoSelectNominatedAccounts);
            }
        });

        potConfigForm.addEventListener('submit', function() {
            serialize();
            autoSelectNominatedAccounts();
        });

        function serialize() {
            if (sectionsJsonInput) {
                sectionsJsonInput.value = JSON.stringify(sectionsState);
            }
        }

        function renderSections() {
            if (!sectionsContainer) return;
            sectionsContainer.innerHTML = '';
            sectionsState.forEach(function(sec, idx) {
                var row = document.createElement('div');
                row.style.display = 'grid';
                row.style.gridTemplateColumns = '1.8fr 1fr 1.6fr auto';
                row.style.gap = '6px';
                row.style.alignItems = 'center';

                row.innerHTML =
                    '<input type="text" class="sec-heading-inp" placeholder="Category" style="padding: 6px 8px; font-size: 0.85rem; border: 1px solid var(--border-color); border-radius: 4px; background: var(--card-bg); color: var(--text-main);" />' +
                    '<input type="number" step="0.01" min="0" class="sec-amount-inp" placeholder="£/mo" style="padding: 6px 8px; font-size: 0.85rem; border: 1px solid var(--border-color); border-radius: 4px; background: var(--card-bg); color: var(--text-main);" />' +
                    '<select class="sec-account-inp" style="padding: 6px 4px; font-size: 0.8rem; border: 1px solid var(--border-color); border-radius: 4px; background: var(--card-bg); color: var(--text-main);">' + accountOptionsHtml + '</select>' +
                    '<button type="button" class="btn-del-sec" style="background: transparent; border: none; color: var(--danger, #ef4444); cursor: pointer; padding: 4px 6px;"><i class="fas fa-trash-alt"></i></button>';

                var hInp = row.querySelector('.sec-heading-inp');
                var aInp = row.querySelector('.sec-amount-inp');
                var accSel = row.querySelector('.sec-account-inp');
                var bDel = row.querySelector('.btn-del-sec');

                hInp.value = sec.heading || '';
                aInp.value = (sec.amount !== null && sec.amount !== undefined) ? sec.amount : 0;

                if (sec.accountId) {
                    accSel.value = sec.accountId;
                }

                hInp.addEventListener('input', function() {
                    sectionsState[idx].heading = hInp.value;
                    serialize();
                });
                aInp.addEventListener('input', function() {
                    var parsed = parseFloat(aInp.value);
                    sectionsState[idx].amount = (isFinite(parsed) && parsed > 0) ? parsed : 0;
                    serialize();
                    updateLiveSummary();
                });
                accSel.addEventListener('change', function() {
                    var v = parseInt(accSel.value, 10);
                    sectionsState[idx].accountId = (v && v > 0) ? v : null;
                    serialize();
                    autoSelectNominatedAccounts();
                });
                bDel.addEventListener('click', function() {
                    sectionsState.splice(idx, 1);
                    renderSections();
                    serialize();
                });

                sectionsContainer.appendChild(row);
            });
            serialize();
            updateLiveSummary();
            autoSelectNominatedAccounts();
        }

        if (presetSelect) {
            presetSelect.addEventListener('change', function() {
                var val = presetSelect.value;
                if (val === 'custom') {
                    if (customPlanIdInput) customPlanIdInput.value = '';
                    if (presetControlsBar) presetControlsBar.style.display = 'none';
                    if (presetUpdateModeWrap) presetUpdateModeWrap.style.display = 'none';
                    if (templateSaveCheckboxWrap) templateSaveCheckboxWrap.style.display = 'block';
                    if (saveTemplateChk) saveTemplateChk.checked = false;
                    if (customFields) customFields.style.display = 'block';
                    renderSections();
                } else if (val.indexOf('saved_') === 0) {
                    var opt = presetSelect.options[presetSelect.selectedIndex];
                    var presetId = opt.getAttribute('data-id') || val.replace('saved_', '');
                    if (customPlanIdInput) customPlanIdInput.value = presetId;
                    if (presetStatusLabel) {
                        presetStatusLabel.textContent = 'Editing Preset: ' + (opt.dataset.name || '');
                    }
                    if (presetControlsBar) presetControlsBar.style.display = 'flex';
                    if (presetUpdateModeWrap) presetUpdateModeWrap.style.display = 'block';
                    if (templateSaveCheckboxWrap) templateSaveCheckboxWrap.style.display = 'none';
                    if (saveTemplateChk) saveTemplateChk.checked = true;

                    if (customFields) customFields.style.display = 'block';
                    if (planNameInput && opt.dataset.name) {
                        planNameInput.value = opt.dataset.name;
                    }
                    try {
                        sectionsState = JSON.parse(opt.dataset.sections || '[]');
                    } catch (e) {
                        sectionsState = [];
                    }
                    renderSections();
                } else {
                    if (customPlanIdInput) customPlanIdInput.value = '';
                    if (presetControlsBar) presetControlsBar.style.display = 'none';
                    if (customFields) customFields.style.display = 'none';
                    updateLiveSummary();
                }
                autoSelectNominatedAccounts();
            });
        }

        if (btnAddSection) {
            btnAddSection.addEventListener('click', function() {
                sectionsState.push({ heading: 'Category ' + (sectionsState.length + 1), amount: 0, accountId: null });
                renderSections();
            });
        }

        if (btnSavePresetDirect) {
            btnSavePresetDirect.addEventListener('click', function() {
                var presetId = customPlanIdInput ? customPlanIdInput.value : '';
                var planName = planNameInput ? planNameInput.value.trim() : '';
                if (!planName) planName = 'Custom Plan';
                serialize();
                var sectionsJson = sectionsJsonInput ? sectionsJsonInput.value : '[]';

                var form = document.createElement('form');
                form.method = 'POST';
                form.action = '/pot/preset/save';

                if (presetId) {
                    var idInput = document.createElement('input');
                    idInput.type = 'hidden';
                    idInput.name = 'id';
                    idInput.value = presetId;
                    form.appendChild(idInput);
                }

                var nameInput = document.createElement('input');
                nameInput.type = 'hidden';
                nameInput.name = 'plan_name';
                nameInput.value = planName;
                form.appendChild(nameInput);

                var secInput = document.createElement('input');
                secInput.type = 'hidden';
                secInput.name = 'sections_json';
                secInput.value = sectionsJson;
                form.appendChild(secInput);

                document.body.appendChild(form);
                form.submit();
            });
        }

        if (btnDeletePresetDirect) {
            btnDeletePresetDirect.addEventListener('click', function(e) {
                e.preventDefault();
                if (typeof e.stopImmediatePropagation === 'function') e.stopImmediatePropagation();
                if (typeof e.stopPropagation === 'function') e.stopPropagation();
                if (document.body.classList.contains('is-offline')) {
                    if (window.monmonShowOfflineToast) window.monmonShowOfflineToast();
                    return;
                }
                var presetId = customPlanIdInput ? customPlanIdInput.value : '';
                if (!presetId) return;
                var planName = planNameInput ? planNameInput.value.trim() : 'this preset';
                if (confirm('Are you sure you want to delete preset "' + planName + '"?')) {
                    if (window.showMonmonLoader) window.showMonmonLoader('Saving changes...', 'Updating MonMon');
                    fetch('/pot/preset/delete/' + encodeURIComponent(presetId), {
                        method: 'DELETE',
                        credentials: 'same-origin',
                        headers: { 'Accept': 'application/json' }
                    }).then(function(res) {
                        if (!res.ok) {
                            return res.text().then(function(t) { throw new Error(t || 'Failed to delete preset'); });
                        }
                        return res.json();
                    }).then(function(data) {
                        window.location.assign((data && data.redirect) ? data.redirect : '/myMonthlyPot');
                    }).catch(function(err) {
                        if (window.hideMonmonLoader) window.hideMonmonLoader();
                        alert((err && err.message) ? err.message : 'Failed to delete preset');
                    });
                }
            });
        }

        document.querySelectorAll('.btn-edit-preset').forEach(function(btn) {
            btn.addEventListener('click', function() {
                var pid = btn.getAttribute('data-preset-id');
                if (presetSelect) {
                    presetSelect.value = 'saved_' + pid;
                    presetSelect.dispatchEvent(new Event('change'));
                    var configForm = document.getElementById('pot-config-form');
                    if (configForm && typeof configForm.scrollIntoView === 'function') {
                        configForm.scrollIntoView({ behavior: 'smooth', block: 'start' });
                    }
                }
            });
        });

        serialize();
        updateLiveSummary();
        autoSelectNominatedAccounts();
    }

    initMonthlyPotPage();
})();
