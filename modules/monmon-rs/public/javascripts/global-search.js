/**
 * MonMon Global Expandable Search Bar & Target Highlight Router
 * - Expandable search bar in header with keyboard shortcuts (Ctrl+K, /)
 * - Queries /api/search?q=... across all application primitives (accounts, outgoings, incomings, balances, pots, savings targets, meal plans, net worth assets, etc.)
 * - Keyboard navigation (Up/Down, Enter, Esc)
 * - Target page navigation, smooth scrolling into context, and brief 2.5s glowing pulse highlight
 */
(function () {
    'use strict';

    var searchContainer, searchInput, searchDropdown, searchResultsList, searchSpinner, searchClearBtn, searchKbd;
    var searchDebounceTimer = null;
    var activeResultIndex = -1;
    var currentResults = [];

    // Initialize once DOM is ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    function init() {
        searchContainer = document.getElementById('header-search-container');
        searchInput = document.getElementById('global-search-input');
        searchDropdown = document.getElementById('header-search-dropdown');
        searchResultsList = document.getElementById('header-search-results-list');
        searchSpinner = document.getElementById('header-search-spinner');
        searchClearBtn = document.getElementById('header-search-clear');
        searchKbd = document.getElementById('header-search-kbd');

        if (searchInput && searchDropdown) {
            bindSearchInputEvents();
            bindGlobalKeybindings();
        }

        // On every page load or hash change, check if we need to scroll to and highlight a target entity
        handleTargetEntityHighlight();
        window.addEventListener('hashchange', handleTargetEntityHighlight);
        window.addEventListener('popstate', handleTargetEntityHighlight);
    }

    function bindSearchInputEvents() {
        searchInput.addEventListener('focus', function () {
            if (searchContainer) searchContainer.classList.add('is-expanded');
            if (searchInput.value.trim().length > 0) {
                openDropdown();
            }
        });

        searchInput.addEventListener('input', function () {
            var val = searchInput.value;
            if (val.length > 0) {
                if (searchClearBtn) searchClearBtn.style.display = 'inline-flex';
                if (searchKbd) searchKbd.style.display = 'none';
            } else {
                if (searchClearBtn) searchClearBtn.style.display = 'none';
                if (searchKbd) searchKbd.style.display = 'inline-block';
                closeDropdown();
                return;
            }

            clearTimeout(searchDebounceTimer);
            searchDebounceTimer = setTimeout(function () {
                performSearch(val.trim());
            }, 180);
        });

        searchInput.addEventListener('keydown', function (e) {
            if (e.key === 'ArrowDown') {
                e.preventDefault();
                navigateResults(1);
            } else if (e.key === 'ArrowUp') {
                e.preventDefault();
                navigateResults(-1);
            } else if (e.key === 'Enter') {
                e.preventDefault();
                selectActiveResult();
            } else if (e.key === 'Escape') {
                e.preventDefault();
                closeAndCollapseSearch();
            }
        });

        if (searchClearBtn) {
            searchClearBtn.addEventListener('click', function (e) {
                e.stopPropagation();
                searchInput.value = '';
                searchInput.focus();
                searchClearBtn.style.display = 'none';
                if (searchKbd) searchKbd.style.display = 'inline-block';
                closeDropdown();
            });
        }

        // Close dropdown when clicking outside
        document.addEventListener('click', function (e) {
            if (searchContainer && !searchContainer.contains(e.target)) {
                closeAndCollapseSearch();
            }
        });
    }

    function bindGlobalKeybindings() {
        document.addEventListener('keydown', function (e) {
            // Check if user is typing in another input / textarea
            var activeTag = document.activeElement ? document.activeElement.tagName.toLowerCase() : '';
            var isEditable = document.activeElement && (document.activeElement.isContentEditable || activeTag === 'input' || activeTag === 'textarea' || activeTag === 'select');

            // Quick search hotkey: '/' or Ctrl+K / Cmd+K
            var isSlash = (e.key === '/' || e.key === '?') && !isEditable;
            var isCtrlK = (e.ctrlKey || e.metaKey) && (e.key === 'k' || e.key === 'K');

            if (isSlash || isCtrlK) {
                e.preventDefault();
                if (searchContainer) searchContainer.classList.add('is-expanded');
                if (searchInput) {
                    searchInput.focus();
                    searchInput.select();
                }
            }
        });
    }

    function performSearch(query) {
        if (!query || query.length === 0) {
            closeDropdown();
            return;
        }

        if (searchSpinner) searchSpinner.style.display = 'inline-flex';
        if (searchClearBtn) searchClearBtn.style.display = 'none';

        var url = '/api/search?q=' + encodeURIComponent(query);
        fetch(url, {
            headers: {
                'Accept': 'application/json'
            }
        })
        .then(function (response) {
            if (!response.ok) {
                throw new Error('Search failed');
            }
            return response.json();
        })
        .then(function (results) {
            if (searchSpinner) searchSpinner.style.display = 'none';
            if (searchClearBtn && searchInput.value.length > 0) searchClearBtn.style.display = 'inline-flex';
            currentResults = results || [];
            activeResultIndex = -1;
            renderResults(currentResults, query);
            openDropdown();
        })
        .catch(function (err) {
            if (searchSpinner) searchSpinner.style.display = 'none';
            if (searchClearBtn && searchInput.value.length > 0) searchClearBtn.style.display = 'inline-flex';
            renderError('Unable to load search results.');
            openDropdown();
        });
    }

    function renderResults(results, query) {
        if (!searchResultsList) return;
        searchResultsList.innerHTML = '';

        if (!results || results.length === 0) {
            var emptyDiv = document.createElement('div');
            emptyDiv.className = 'header-search-empty';
            emptyDiv.innerHTML = '<div style="font-weight: 600; margin-bottom: 4px;"><i class="fas fa-search" style="opacity: 0.4; margin-right: 6px;"></i> No results for "<b>' + escapeHtml(query) + '</b>"</div>' +
                                 '<div style="font-size: 0.78rem; opacity: 0.7;">Try searching for an account, outgoing, income, pot, recipe, asset or transaction amount.</div>';
            searchResultsList.appendChild(emptyDiv);
            return;
        }

        for (var i = 0; i < results.length; i++) {
            var res = results[i];
            var itemEl = document.createElement('div');
            itemEl.className = 'header-search-result-item';
            itemEl.setAttribute('data-index', i);
            itemEl.setAttribute('role', 'option');

            var iconClass = res.icon ? res.icon : 'fa-search';
            if (iconClass.indexOf('fa-') === -1) iconClass = 'fa-' + iconClass;

            itemEl.innerHTML =
                '<div class="search-item-icon-box">' +
                    '<i class="fas ' + escapeHtml(iconClass) + '"></i>' +
                '</div>' +
                '<div class="search-item-info">' +
                    '<div class="search-item-header">' +
                        '<span class="search-item-title">' + highlightMatch(res.title, query) + '</span>' +
                        '<span class="search-item-badge search-badge-' + escapeHtml(res.entityType) + '">' + escapeHtml(res.badge || res.entityType) + '</span>' +
                    '</div>' +
                    '<div class="search-item-subtitle">' + escapeHtml(res.subtitle) + '</div>' +
                '</div>' +
                '<div class="search-item-action-icon">' +
                    '<i class="fas fa-arrow-right"></i>' +
                '</div>';

            (function (itemData) {
                itemEl.addEventListener('click', function (e) {
                    e.preventDefault();
                    navigateToResult(itemData);
                });
            })(res);

            searchResultsList.appendChild(itemEl);
        }
    }

    function renderError(msg) {
        if (!searchResultsList) return;
        searchResultsList.innerHTML = '<div class="header-search-empty" style="color: var(--danger, #ef4444);"><i class="fas fa-exclamation-circle" style="margin-right: 6px;"></i> ' + escapeHtml(msg) + '</div>';
    }

    function navigateResults(delta) {
        var items = searchResultsList ? searchResultsList.querySelectorAll('.header-search-result-item') : [];
        if (!items || items.length === 0) return;

        if (activeResultIndex >= 0 && activeResultIndex < items.length) {
            items[activeResultIndex].classList.remove('is-active');
        }

        activeResultIndex += delta;
        if (activeResultIndex >= items.length) {
            activeResultIndex = 0;
        } else if (activeResultIndex < 0) {
            activeResultIndex = items.length - 1;
        }

        var activeEl = items[activeResultIndex];
        if (activeEl) {
            activeEl.classList.add('is-active');
            activeEl.scrollIntoView({ block: 'nearest' });
        }
    }

    function selectActiveResult() {
        if (activeResultIndex >= 0 && currentResults && activeResultIndex < currentResults.length) {
            navigateToResult(currentResults[activeResultIndex]);
        } else if (currentResults && currentResults.length > 0) {
            navigateToResult(currentResults[0]);
        }
    }

    function navigateToResult(result) {
        if (!result || !result.targetUrl) return;

        closeAndCollapseSearch();

        var targetUrl = result.targetUrl;
        var parser = document.createElement('a');
        parser.href = targetUrl;

        var isSamePage = (parser.pathname === window.location.pathname);

        if (isSamePage) {
            // Already on target page: smooth scroll & pulse directly without a page reload!
            if (window.history && window.history.pushState) {
                window.history.pushState(null, '', targetUrl);
            } else {
                window.location.hash = parser.hash;
            }
            highlightTargetById(result.domId || (parser.hash ? parser.hash.substring(1) : null));
        } else {
            // Different page: navigate there
            window.location.href = targetUrl;
        }
    }

    function openDropdown() {
        if (searchDropdown) {
            searchDropdown.style.display = 'block';
        }
    }

    function closeDropdown() {
        if (searchDropdown) {
            searchDropdown.style.display = 'none';
        }
        activeResultIndex = -1;
    }

    function closeAndCollapseSearch() {
        closeDropdown();
        if (searchInput) searchInput.blur();
        if (searchContainer && (!searchInput || searchInput.value.trim().length === 0)) {
            searchContainer.classList.remove('is-expanded');
        }
    }

    // -------------------------------------------------------------
    // Target Entity Highlight & Smooth Scroll Router
    // -------------------------------------------------------------

    function handleTargetEntityHighlight() {
        var hash = window.location.hash ? window.location.hash.substring(1) : '';
        var searchParams = new URLSearchParams(window.location.search);
        var highlightParam = searchParams.get('highlight');

        var targetId = highlightParam || hash;
        if (!targetId) return;

        // Allow DOM to settle, especially for dynamic tabs or tables
        setTimeout(function () {
            highlightTargetById(targetId);
        }, 120);
    }

    function highlightTargetById(targetId) {
        if (!targetId) return;

        // 1. Locate element by id, card id variant, or data-entity-id
        var targetEl = document.getElementById(targetId);
        if (!targetEl) {
            // Try mobile card pattern or prefix pattern
            targetEl = document.getElementById(targetId + '-card') ||
                       document.getElementById(targetId.replace(/^([a-z]+)-/, '$1-card-')) ||
                       document.querySelector('[data-entity-id="' + targetId + '"]');
        }

        if (!targetEl) return;

        // 2. Unhide target if nested inside a hidden tab or mobile deck view
        revealHiddenParentContext(targetEl);

        // 3. Smooth scroll directly to the entity
        setTimeout(function () {
            targetEl.scrollIntoView({ behavior: 'smooth', block: 'center' });

            // 4. Trigger brief glowing pulse highlight
            targetEl.classList.remove('entity-search-highlight');
            // Force browser reflow to restart CSS animation if already present
            void targetEl.offsetWidth;
            targetEl.classList.add('entity-search-highlight');

            // 5. Clean up highlight class after animation finishes
            setTimeout(function () {
                targetEl.classList.remove('entity-search-highlight');
            }, 3000);
        }, 80);
    }

    /**
     * If an entity is hidden because of an active tab (e.g. Outgoings yearly tab, paused tab),
     * automatically activate the tab so the user sees the element in full context.
     */
    function revealHiddenParentContext(element) {
        if (!element) return;

        // A. Outgoings Tabs (Monthly vs Yearly vs Paused vs All)
        if (element.classList.contains('outgoing-row') || element.classList.contains('outgoing-card')) {
            var isPaused = element.getAttribute('data-is-paused') === 'true';
            var isYearly = element.getAttribute('data-is-yearly') === 'true';
            var dueNow = element.getAttribute('data-due-now') === 'true';

            if (isPaused) {
                var pausedTabBtn = document.querySelector('.outgoings-tab-btn[data-tab="paused"]');
                if (pausedTabBtn) pausedTabBtn.click();
            } else if (isYearly && !dueNow) {
                var yearlyTabBtn = document.querySelector('.outgoings-tab-btn[data-tab="yearly"]');
                if (yearlyTabBtn) yearlyTabBtn.click();
            } else if (element.style.display === 'none') {
                var allTabBtn = document.querySelector('.outgoings-tab-btn[data-tab="all"]');
                if (allTabBtn) allTabBtn.click();
            }
        }

        // B. NomMon Meal Plan Content
        var nommonContent = document.getElementById('nommon-content');
        if (nommonContent && nommonContent.style.display === 'none') {
            nommonContent.style.display = 'block';
        }

        // C. Savings Plan Deck / Sliced Items Tab
        var slicedItemsCard = document.getElementById('sliced-items-card');
        if (slicedItemsCard && slicedItemsCard.classList.contains('deck-card')) {
            var pillBtn = document.querySelector('.deck-nav-pill[data-target="sliced-items-card"]');
            if (pillBtn && typeof pillBtn.click === 'function') {
                pillBtn.click();
            }
        }
    }

    // Helper functions
    function highlightMatch(text, query) {
        if (!text) return '';
        if (!query) return escapeHtml(text);

        var escapedQuery = query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        var regex = new RegExp('(' + escapedQuery + ')', 'gi');
        return escapeHtml(text).replace(regex, '<mark class="search-match-mark">$1</mark>');
    }

    function escapeHtml(str) {
        if (!str) return '';
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }
})();
