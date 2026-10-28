/**
 * NomMon - Smart Grocery & Meal Planner
 * Plain JavaScript (Strictly No CoffeeScript per MonMon guidelines)
 */
(function () {
    'use strict';

    var container = document.getElementById('nommon-container');
    if (!container) return;

    var weeklyBudget = parseFloat(container.getAttribute('data-budget')) || 50.00;
    var isConfigured = container.getAttribute('data-configured') === 'true';

    var currentPlan = null;
    var checkedItems = new Set();
    var STORAGE_CHECKED_KEY = 'nommon_checked_items';
    var STORAGE_POSTCODE_KEY = 'nommon_user_postcode';
    var STORAGE_PEOPLE_KEY = 'nommon_people_count';
    var peopleCount = 1;

    // Load saved checked items from localStorage
    function loadSavedChecks() {
        try {
            var raw = localStorage.getItem(STORAGE_CHECKED_KEY);
            if (raw) {
                var arr = JSON.parse(raw);
                if (Array.isArray(arr)) {
                    checkedItems = new Set(arr);
                }
            }
        } catch (e) {
            checkedItems = new Set();
        }
    }

    function saveChecks() {
        try {
            localStorage.setItem(STORAGE_CHECKED_KEY, JSON.stringify(Array.from(checkedItems)));
        } catch (e) {}
    }

    // Update Store Locator Links
    // Sainsbury's store finder SPA routes via exact coordinates: stores.sainsburys.co.uk/list/place/@lat,lon/1/all
    // We resolve lat/lon via free UK postcodes.io API with instant fallback to Google Maps search.
    function updateStoreLinks(postcode) {
        var finderLink = document.getElementById('link-sainsburys-finder');
        var mapsLink = document.getElementById('link-google-maps');
        var cleanCode = (postcode || '').trim();

        if (mapsLink) {
            mapsLink.href = cleanCode
                ? 'https://www.google.com/maps/search/Sainsbury\'s+near+' + encodeURIComponent(cleanCode)
                : 'https://www.google.com/maps/search/Sainsbury\'s';
        }

        if (finderLink) {
            if (!cleanCode) {
                finderLink.href = 'https://stores.sainsburys.co.uk/';
                return;
            }

            // Sensible immediate fallback: Google Maps search until coordinates resolve
            var fallbackMapsUrl = 'https://www.google.com/maps/search/Sainsbury\'s+near+' + encodeURIComponent(cleanCode);
            finderLink.href = fallbackMapsUrl;

            // Fetch coordinates from free, fast UK postcodes.io API (CORS-enabled, no key needed)
            var cleanForApi = cleanCode.replace(/[^a-zA-Z0-9]/g, '');
            fetch('https://api.postcodes.io/postcodes/' + encodeURIComponent(cleanForApi))
                .then(function (res) {
                    if (res.ok) return res.json();
                    throw new Error('Postcode lookup status ' + res.status);
                })
                .then(function (data) {
                    if (data && data.result && typeof data.result.latitude === 'number' && typeof data.result.longitude === 'number') {
                        var lat = data.result.latitude.toFixed(5);
                        var lon = data.result.longitude.toFixed(5);
                        finderLink.href = 'https://stores.sainsburys.co.uk/list/place/@' + lat + ',' + lon + '/1/all';
                    }
                })
                .catch(function () {
                    // Keep fallback Google Maps link
                    finderLink.href = fallbackMapsUrl;
                });
        }
    }

    // Tab Switching
    function initTabs() {
        var tabBtns = document.querySelectorAll('.nommon-tab-btn');
        tabBtns.forEach(function (btn) {
            btn.addEventListener('click', function () {
                var targetId = btn.getAttribute('data-tab');

                tabBtns.forEach(function (b) { b.classList.remove('active'); });
                btn.classList.add('active');

                document.querySelectorAll('.nommon-tab-panel').forEach(function (panel) {
                    panel.style.display = 'none';
                });

                var activePanel = document.getElementById(targetId);
                if (activePanel) {
                    activePanel.style.display = 'block';
                }
            });
        });
    }

    // Update in-cart checked summary
    function updateCheckedSummary() {
        var checkboxes = document.querySelectorAll('.nommon-item-check');
        var totalCount = checkboxes.length;
        var checkedCount = 0;
        var checkedSpend = 0.0;

        checkboxes.forEach(function (cb) {
            if (cb.checked) {
                checkedCount++;
                var price = parseFloat(cb.getAttribute('data-price')) || 0;
                var qty = parseInt(cb.getAttribute('data-qty'), 10) || 1;
                checkedSpend += (price * qty);
            }
        });

        var counterEl = document.getElementById('nommon-checked-counter');
        var spendEl = document.getElementById('nommon-checked-spend');

        if (counterEl) {
            counterEl.textContent = checkedCount + ' / ' + totalCount;
        }
        if (spendEl) {
            spendEl.textContent = '£' + checkedSpend.toFixed(2) + ' in cart';
        }
    }

    // Normalize item quantities and packaging sizes defensively
    function normalizeItem(itm) {
        var rawQty = parseInt(itm.quantity, 10);
        if (isNaN(rawQty) || rawQty <= 0) rawQty = 1;

        var price = parseFloat(itm.estimatedPrice) || 0;
        var pkg = itm.packageSize || itm.unit || '';

        // If the AI mistakenly put grams, ml, or unit counts in quantity (e.g. qty=500, unit='g' or price * qty > 20)
        var unitLower = (itm.unit || '').toLowerCase().trim();
        var isWeightOrVolumeUnit = ['g', 'gram', 'grams', 'ml', 'milliliter', 'millilitre', 'millilitres', 'cubes', 'leaves', 'seeds', 'cloves'].indexOf(unitLower) !== -1;

        if (rawQty >= 15 || (isWeightOrVolumeUnit && rawQty > 5)) {
            if (!pkg || pkg === itm.unit) {
                pkg = rawQty + (unitLower ? unitLower : 'g');
            }
            rawQty = 1;
        }

        return {
            name: itm.name || 'Unknown item',
            category: itm.category || 'Other Staples',
            packageSize: pkg,
            quantity: rawQty,
            estimatedPrice: price,
            itemTotal: rawQty * price
        };
    }

    // Render Shopping List
    function renderShoppingList(list) {
        var containerEl = document.getElementById('shopping-list-categories');
        var tabCountEl = document.getElementById('tab-item-count');
        if (!containerEl) return;

        containerEl.innerHTML = '';
        if (!list || !list.length) {
            containerEl.innerHTML = '<div style="color: var(--text-muted); padding: 1rem;">No grocery items found in plan.</div>';
            return;
        }

        if (tabCountEl) {
            tabCountEl.textContent = list.length;
        }

        // Group by category
        var groups = {};
        list.forEach(function (rawItem) {
            var item = normalizeItem(rawItem);
            var cat = item.category || 'Other Staples';
            if (!groups[cat]) groups[cat] = [];
            groups[cat].push(item);
        });

        var categoryOrder = ['Produce', 'Meat & Poultry', 'Meat & Fish', 'Meat & Seafood', 'Dairy & Eggs', 'Bakery', 'Pantry & Pulses', 'Tins & Cans', 'Frozen', 'Other Staples'];
        var sortedCats = Object.keys(groups).sort(function (a, b) {
            var ia = categoryOrder.indexOf(a);
            var ib = categoryOrder.indexOf(b);
            if (ia === -1) ia = 99;
            if (ib === -1) ib = 99;
            return ia - ib;
        });

        function getCategoryIcon(cat) {
            var c = (cat || '').toLowerCase();
            if (c.indexOf('meat') !== -1 || c.indexOf('poultry') !== -1 || c.indexOf('seafood') !== -1 || c.indexOf('fish') !== -1) {
                return 'fa-drumstick-bite';
            }
            if (c.indexOf('produce') !== -1 || c.indexOf('fruit') !== -1 || c.indexOf('veg') !== -1) {
                return 'fa-carrot';
            }
            if (c.indexOf('dairy') !== -1 || c.indexOf('egg') !== -1) {
                return 'fa-egg';
            }
            if (c.indexOf('bakery') !== -1 || c.indexOf('bread') !== -1) {
                return 'fa-bread-slice';
            }
            if (c.indexOf('frozen') !== -1) {
                return 'fa-snowflake';
            }
            if (c.indexOf('tin') !== -1 || c.indexOf('can') !== -1) {
                return 'fa-box-open';
            }
            return 'fa-tag';
        }

        sortedCats.forEach(function (catName) {
            var items = groups[catName];
            var catBox = document.createElement('div');
            catBox.style.border = '1px solid var(--border-color)';
            catBox.style.borderRadius = '8px';
            catBox.style.overflow = 'hidden';

            var header = document.createElement('div');
            header.style.background = 'var(--card-bg-subtle)';
            header.style.padding = '8px 12px';
            header.style.fontWeight = '700';
            header.style.fontSize = '0.9rem';
            header.style.borderBottom = '1px solid var(--border-color)';
            header.style.display = 'flex';
            header.style.justifyContent = 'space-between';
            header.innerHTML = '<span><i class="fas ' + getCategoryIcon(catName) + '" style="color: var(--primary); margin-right: 6px;"></i>' + escapeHtml(catName) + '</span>' +
                '<span style="font-size: 0.8rem; color: var(--text-muted); font-weight: 500;">' + items.length + ' item' + (items.length === 1 ? '' : 's') + '</span>';
            catBox.appendChild(header);

            items.forEach(function (itm) {
                var isChecked = checkedItems.has(itm.name);
                var row = document.createElement('div');
                row.className = 'nommon-checklist-item' + (isChecked ? ' checked' : '');

                var price = itm.estimatedPrice;
                var qty = itm.quantity;
                var itemTotal = itm.itemTotal;
                var pkgLabel = itm.packageSize ? '<span style="font-size: 0.78rem; color: var(--text-muted); font-weight: 400; margin-left: 4px;">(' + escapeHtml(itm.packageSize) + ')</span>' : '';

                row.innerHTML =
                    '<div style="display: flex; align-items: center; gap: 10px;">' +
                    '  <input type="checkbox" class="nommon-item-check" data-name="' + escapeHtml(itm.name) + '" data-price="' + price + '" data-qty="' + qty + '" ' + (isChecked ? 'checked' : '') + ' style="cursor: pointer; transform: scale(1.15);" />' +
                    '  <span class="item-name" style="font-weight: 600; font-size: 0.92rem;">' + escapeHtml(itm.name) + '</span>' +
                    pkgLabel +
                    '</div>' +
                    '<div style="font-weight: 700; font-size: 0.92rem; color: var(--text-main); white-space: nowrap;">' +
                    (qty > 1 ? '<span style="font-size: 0.78rem; font-weight: 400; color: var(--text-muted); margin-right: 4px;">' + qty + 'x £' + price.toFixed(2) + ' = </span>' : '') +
                    '£' + itemTotal.toFixed(2) +
                    '</div>';

                var checkbox = row.querySelector('.nommon-item-check');

                row.addEventListener('click', function (e) {
                    if (e.target !== checkbox) {
                        checkbox.checked = !checkbox.checked;
                    }
                    if (checkbox.checked) {
                        row.classList.add('checked');
                        checkedItems.add(itm.name);
                    } else {
                        row.classList.remove('checked');
                        checkedItems.delete(itm.name);
                    }
                    saveChecks();
                    updateCheckedSummary();
                });

                checkbox.addEventListener('change', function () {
                    if (checkbox.checked) {
                        row.classList.add('checked');
                        checkedItems.add(itm.name);
                    } else {
                        row.classList.remove('checked');
                        checkedItems.delete(itm.name);
                    }
                    saveChecks();
                    updateCheckedSummary();
                });

                catBox.appendChild(row);
            });

            containerEl.appendChild(catBox);
        });

        updateCheckedSummary();
    }

    // Render 7-Day Menu Schedule
    function renderDays(days) {
        var grid = document.getElementById('days-grid');
        if (!grid) return;

        grid.innerHTML = '';
        if (!days || !days.length) {
            grid.innerHTML = '<div style="color: var(--text-muted); padding: 1rem;">No daily schedule generated.</div>';
            return;
        }

        days.forEach(function (d) {
            var card = document.createElement('div');
            card.className = 'day-card';
            card.innerHTML =
                '<div style="font-weight: 800; font-size: 1rem; color: var(--primary); margin-bottom: 0.6rem; border-bottom: 1px dashed var(--border-color); padding-bottom: 0.35rem; display: flex; justify-content: space-between;">' +
                '  <span>' + escapeHtml(d.dayName || 'Day') + '</span>' +
                '  <span class="badge-pill" style="font-size: 0.7rem; background: var(--card-bg); border: 1px solid var(--border-color); color: var(--text-muted);">Sainsbury\'s Staples</span>' +
                '</div>' +
                '<div style="display: flex; flex-direction: column; gap: 0.4rem; font-size: 0.85rem; line-height: 1.45;">' +
                '  <div><b style="color: var(--text-muted);"><i class="fas fa-sun" style="color: #eab308; margin-right: 4px;"></i>Breakfast:</b> ' + escapeHtml(d.breakfast || '—') + '</div>' +
                '  <div><b style="color: var(--text-muted);"><i class="fas fa-bread-slice" style="color: #f97316; margin-right: 4px;"></i>Lunch:</b> ' + escapeHtml(d.lunch || '—') + '</div>' +
                '  <div><b style="color: var(--text-muted);"><i class="fas fa-bowl-food" style="color: #10b981; margin-right: 4px;"></i>Dinner:</b> ' + escapeHtml(d.dinner || '—') + '</div>' +
                (d.snack ? '  <div><b style="color: var(--text-muted);"><i class="fas fa-apple-whole" style="color: #ef4444; margin-right: 4px;"></i>Snack:</b> ' + escapeHtml(d.snack) + '</div>' : '') +
                (d.prepNotes ? '  <div style="font-size: 0.78rem; color: var(--text-muted); margin-top: 4px; padding-top: 4px; border-top: 1px dotted var(--border-color);"><b>Tip:</b> ' + escapeHtml(d.prepNotes) + '</div>' : '') +
                '</div>';
            grid.appendChild(card);
        });
    }

    // Render Batch Recipes
    function renderRecipes(recipes) {
        var containerEl = document.getElementById('recipes-container');
        var tabRecipeCountEl = document.getElementById('tab-recipe-count');
        if (!containerEl) return;

        containerEl.innerHTML = '';
        if (!recipes || !recipes.length) {
            containerEl.innerHTML = '<div style="color: var(--text-muted); padding: 1rem;">No batch recipes generated.</div>';
            return;
        }

        if (tabRecipeCountEl) {
            tabRecipeCountEl.textContent = recipes.length;
        }

        recipes.forEach(function (rec, idx) {
            var box = document.createElement('div');
            box.style.border = '1px solid var(--border-color)';
            box.style.borderRadius = '8px';
            box.style.padding = '1rem';
            box.style.background = 'var(--card-bg-subtle)';

            var ingredientsHtml = '';
            if (rec.ingredientsUsed && rec.ingredientsUsed.length) {
                ingredientsHtml = '<div style="display: flex; flex-wrap: wrap; gap: 4px; margin: 0.5rem 0 0.8rem 0;">' +
                    rec.ingredientsUsed.map(function (ing) {
                        return '<span class="badge-pill" style="font-size: 0.75rem; background: var(--card-bg); border: 1px solid var(--border-color); color: var(--text-main);">' + escapeHtml(ing) + '</span>';
                    }).join('') +
                    '</div>';
            }

            var instructionsHtml = '';
            if (rec.instructions && rec.instructions.length) {
                instructionsHtml = '<ol style="margin: 0; padding-left: 1.25rem; font-size: 0.88rem; line-height: 1.55;">' +
                    rec.instructions.map(function (step) {
                        return '<li style="margin-bottom: 0.35rem;">' + escapeHtml(step) + '</li>';
                    }).join('') +
                    '</ol>';
            }

            box.innerHTML =
                '<div style="display: flex; justify-content: space-between; align-items: flex-start; flex-wrap: wrap; gap: 8px; margin-bottom: 0.4rem;">' +
                '  <h4 style="margin: 0; font-size: 1.05rem; font-weight: 700; color: var(--text-main);">' +
                '    <i class="fas fa-kitchen-set" style="color: var(--primary); margin-right: 6px;"></i>' + escapeHtml(rec.title || ('Recipe ' + (idx + 1))) +
                '  </h4>' +
                '  <div style="font-size: 0.78rem; color: var(--text-muted); display: flex; gap: 10px;">' +
                (rec.servings ? '<span><i class="fas fa-users"></i> ' + rec.servings + ' servings</span>' : '') +
                (rec.prepTimeMinutes ? '<span><i class="far fa-clock"></i> Prep: ' + rec.prepTimeMinutes + 'm</span>' : '') +
                (rec.cookTimeMinutes ? '<span><i class="fas fa-fire"></i> Cook: ' + rec.cookTimeMinutes + 'm</span>' : '') +
                '  </div>' +
                '</div>' +
                ingredientsHtml +
                instructionsHtml;

            containerEl.appendChild(box);
        });
    }

    // Update budget tier badge styling & text based on per-person weekly spend
    function updateBudgetTierBadge(budget, people) {
        var badge = document.getElementById('nommon-budget-tier-badge');
        var subtitle = document.getElementById('nommon-basket-subtitle');
        var subtext = document.getElementById('nommon-budget-subtext');
        if (!badge) return;

        var p = parseInt(people, 10) || 1;
        var perPerson = budget / p;

        if (subtext) {
            subtext.textContent = '£' + perPerson.toFixed(2) + '/person/wk (' + p + ' ' + (p === 1 ? 'person' : 'people') + ')';
        }

        if (perPerson >= 120) {
            badge.textContent = 'Opulent / Gourmet Luxury';
            badge.style.background = 'rgba(168, 85, 247, 0.15)';
            badge.style.color = '#9333ea';
            badge.style.border = '1px solid #9333ea';
            if (subtitle) subtitle.textContent = 'Taste the Difference prime steaks & seafood';
        } else if (perPerson >= 80) {
            badge.textContent = 'Generous / Quality Fresh';
            badge.style.background = 'rgba(22, 163, 74, 0.15)';
            badge.style.color = '#16a34a';
            badge.style.border = '1px solid #16a34a';
            if (subtitle) subtitle.textContent = 'Fresh meat, produce & staples';
        } else if (perPerson >= 55) {
            badge.textContent = 'Moderate / Balanced';
            badge.style.background = 'rgba(59, 130, 246, 0.15)';
            badge.style.color = '#2563eb';
            badge.style.border = '1px solid #2563eb';
            if (subtitle) subtitle.textContent = 'Balanced meat & family staples';
        } else {
            badge.textContent = 'Frugal / Budget Staples';
            badge.style.background = 'rgba(234, 88, 12, 0.15)';
            badge.style.color = '#ea580c';
            badge.style.border = '1px solid #ea580c';
            if (subtitle) subtitle.textContent = 'Stamford St & value staples';
        }
    }

    // Fetch Plan from Backend
    function fetchPlan(force) {
        var loadingEl = document.getElementById('nommon-loading');
        var contentEl = document.getElementById('nommon-content');
        var refreshIcon = document.getElementById('nommon-refresh-icon');
        var postcode = (document.getElementById('nommon-postcode-input').value || '').trim();

        var budgetInput = document.getElementById('nommon-budget-input');
        if (budgetInput) {
            var val = parseFloat(budgetInput.value);
            if (!isNaN(val) && val > 0) {
                weeklyBudget = val;
            }
        }

        var peopleSelect = document.getElementById('nommon-people-select');
        if (peopleSelect) {
            peopleCount = parseInt(peopleSelect.value, 10) || 1;
        }

        updateBudgetTierBadge(weeklyBudget, peopleCount);

        var loadingText = document.getElementById('nommon-loading-text');
        if (loadingText) {
            loadingText.textContent = 'Planning meals for ' + peopleCount + ' ' + (peopleCount === 1 ? 'person' : 'people') + ', balancing Sainsbury\'s selections and recipes within your £' + weeklyBudget.toFixed(2) + ' weekly budget.';
        }

        if (loadingEl) loadingEl.style.display = 'block';
        if (contentEl) contentEl.style.display = 'none';
        if (refreshIcon) refreshIcon.classList.add('fa-spin');

        var url = '/api/nommon/plan?budget=' + encodeURIComponent(weeklyBudget) + '&people=' + encodeURIComponent(peopleCount);
        if (force) url += '&force=true';
        if (postcode) url += '&postcode=' + encodeURIComponent(postcode);

        fetch(url, {
            headers: { 'Accept': 'application/json' }
        })
            .then(function (res) {
                if (!res.ok) throw new Error('HTTP ' + res.status);
                return res.json();
            })
            .then(function (data) {
                if (!data) {
                    throw new Error('No response data received from server');
                }
                if (data.error && !data.plan) {
                    throw new Error(data.error);
                }
                if (!data.plan) {
                    if (data.rawPlan) {
                        console.warn('NomMon rawPlan received:', data.rawPlan);
                        throw new Error('Meal plan generation was incomplete. Please click Retry or Regenerate.');
                    }
                    throw new Error('No meal plan data returned');
                }

                currentPlan = data.plan;

                if (currentPlan.weeklyBudget) {
                    var planBudget = parseFloat(currentPlan.weeklyBudget);
                    if (!isNaN(planBudget) && planBudget > 0) {
                        weeklyBudget = planBudget;
                        if (budgetInput) {
                            budgetInput.value = weeklyBudget.toFixed(2);
                        }
                    }
                }
                updateBudgetTierBadge(weeklyBudget, peopleCount);

                // Title & Highlights
                var titleEl = document.getElementById('plan-title-display');
                var highlightsEl = document.getElementById('nutrition-highlight-display');
                if (titleEl && currentPlan.planTitle) {
                    titleEl.textContent = currentPlan.planTitle;
                }
                if (highlightsEl && currentPlan.nutritionalHighlights) {
                    highlightsEl.innerHTML = '<i class="fas fa-heart-pulse" style="color: #ef4444; margin-right: 4px;"></i>' + escapeHtml(currentPlan.nutritionalHighlights);
                }

                // Top Metrics
                var basketValEl = document.getElementById('nommon-basket-val');
                var bufferValEl = document.getElementById('nommon-buffer-val');

                var computedBasketTotal = 0.0;
                var list = currentPlan.shoppingList || [];
                list.forEach(function (rawItem) {
                    var norm = normalizeItem(rawItem);
                    computedBasketTotal += norm.itemTotal;
                });

                var estCost = computedBasketTotal > 0 ? computedBasketTotal : (parseFloat(currentPlan.totalEstimatedCost) || 0);
                var buffer = Math.max(0, weeklyBudget - estCost);

                if (basketValEl) basketValEl.textContent = '£' + estCost.toFixed(2);
                if (bufferValEl) bufferValEl.textContent = '£' + buffer.toFixed(2);

                // Render sections
                renderShoppingList(currentPlan.shoppingList || []);
                renderDays(currentPlan.days || []);
                renderRecipes(currentPlan.recipes || []);

                if (loadingEl) loadingEl.style.display = 'none';
                if (contentEl) contentEl.style.display = 'block';
            })
            .catch(function (err) {
                console.error('NomMon plan load error:', err);
                if (loadingEl) {
                    loadingEl.innerHTML =
                        '<div style="color: #ef4444; padding: 1.5rem;">' +
                        '  <i class="fas fa-exclamation-triangle" style="font-size: 1.8rem; margin-bottom: 0.5rem;"></i>' +
                        '  <div style="font-weight: 700; margin-bottom: 0.5rem;">Could not load NomMon plan</div>' +
                        '  <div style="font-size: 0.85rem; color: var(--text-muted);">' + escapeHtml(err.message || 'Unknown error') + '</div>' +
                        '  <button id="btn-nommon-retry" class="w3-button w3-round w3-small" style="margin-top: 1rem; background: var(--primary); color: #fff;">Retry</button>' +
                        '</div>';
                    var retryBtn = document.getElementById('btn-nommon-retry');
                    if (retryBtn) {
                        retryBtn.addEventListener('click', function () {
                            fetchPlan(true);
                        });
                    }
                }
            })
            .finally(function () {
                if (refreshIcon) refreshIcon.classList.remove('fa-spin');
            });
    }

    // Helper: Escape HTML
    function escapeHtml(str) {
        if (!str) return '';
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    // DOM Ready Initialization
    document.addEventListener('DOMContentLoaded', function () {
        loadSavedChecks();
        initTabs();

        // Load saved postcode
        var savedPostcode = '';
        try {
            savedPostcode = localStorage.getItem(STORAGE_POSTCODE_KEY) || '';
        } catch (e) {}

        var postcodeInput = document.getElementById('nommon-postcode-input');
        if (postcodeInput && savedPostcode) {
            postcodeInput.value = savedPostcode;
        }
        updateStoreLinks(savedPostcode);

        // Weekly Budget Input & Tier Badge
        var budgetInput = document.getElementById('nommon-budget-input');
        var peopleSelect = document.getElementById('nommon-people-select');

        if (peopleSelect) {
            try {
                var savedPeople = localStorage.getItem(STORAGE_PEOPLE_KEY);
                if (savedPeople) {
                    peopleSelect.value = savedPeople;
                    peopleCount = parseInt(savedPeople, 10) || 1;
                }
            } catch (e) {}
            peopleSelect.addEventListener('change', function () {
                peopleCount = parseInt(peopleSelect.value, 10) || 1;
                try {
                    localStorage.setItem(STORAGE_PEOPLE_KEY, peopleCount);
                } catch (e) {}
                updateBudgetTierBadge(weeklyBudget, peopleCount);
                fetchPlan(false);
            });
        }

        if (budgetInput) {
            updateBudgetTierBadge(weeklyBudget, peopleCount);
            budgetInput.addEventListener('keydown', function (e) {
                if (e.key === 'Enter') {
                    e.preventDefault();
                    fetchPlan(false);
                }
            });
            budgetInput.addEventListener('change', function () {
                var val = parseFloat(budgetInput.value);
                if (!isNaN(val) && val > 0) {
                    weeklyBudget = val;
                    updateBudgetTierBadge(val, peopleCount);
                }
            });
        }

        // Save postcode button
        var btnSavePostcode = document.getElementById('btn-save-postcode');
        if (btnSavePostcode && postcodeInput) {
            btnSavePostcode.addEventListener('click', function () {
                var code = postcodeInput.value.trim();
                try {
                    localStorage.setItem(STORAGE_POSTCODE_KEY, code);
                } catch (e) {}
                updateStoreLinks(code);
            });
        }

        // Regenerate Button
        var btnRegen = document.getElementById('btn-regenerate-nommon');
        if (btnRegen) {
            btnRegen.addEventListener('click', function () {
                fetchPlan(true);
            });
        }

        // Uncheck all button
        var btnUncheck = document.getElementById('btn-uncheck-all');
        if (btnUncheck) {
            btnUncheck.addEventListener('click', function () {
                checkedItems.clear();
                saveChecks();
                document.querySelectorAll('.nommon-checklist-item').forEach(function (row) {
                    row.classList.remove('checked');
                    var cb = row.querySelector('.nommon-item-check');
                    if (cb) cb.checked = false;
                });
                updateCheckedSummary();
            });
        }

        // Initial Load
        fetchPlan(false);
    });
})();
