// MonMon Mobile Deck Navigation & Swipe Synchronization (SPOG, Bank Sync, Savings Plan, Net Worth)
(function() {
    'use strict';

    if (window.__monmonDeckInitialized) return;
    window.__monmonDeckInitialized = true;

    var lastMetricsKey = '';
    var lastViewportWidth = window.innerWidth;

    // Dynamically calculate the navigation metrics across mobile viewports
    function getDynamicNavMetrics() {
        var ticker = document.getElementById('ticker');
        var header = document.querySelector('header.app-header');
        var mainNav = document.getElementById('main-nav') || document.querySelector('nav.crumbs');

        var tickerHeight = (ticker && ticker.offsetHeight > 0) ? ticker.offsetHeight : 0;
        var headerHeight = (header && header.offsetHeight > 0) ? header.offsetHeight : 0;
        var mainNavHeight = (mainNav && mainNav.offsetHeight > 0) ? mainNav.offsetHeight : 0;
        var totalNavHeight = tickerHeight + headerHeight + mainNavHeight;

        // Sticky offset: mainNav (nav.crumbs) is position: sticky; top: 0
        var stickyTopOffset = mainNavHeight;

        return {
            tickerHeight: tickerHeight,
            headerHeight: headerHeight,
            mainNavHeight: mainNavHeight,
            totalNavHeight: totalNavHeight,
            stickyTopOffset: stickyTopOffset
        };
    }

    function updateNavCssVariables() {
        var m = getDynamicNavMetrics();
        var key = m.tickerHeight + ':' + m.headerHeight + ':' + m.mainNavHeight;
        if (key === lastMetricsKey) return;
        lastMetricsKey = key;

        var root = document.documentElement;
        root.style.setProperty('--ticker-height', m.tickerHeight + 'px');
        root.style.setProperty('--header-height', m.headerHeight + 'px');
        root.style.setProperty('--main-nav-height', m.mainNavHeight + 'px');
        root.style.setProperty('--total-nav-height', m.totalNavHeight + 'px');
        root.style.setProperty('--deck-nav-sticky-top', m.stickyTopOffset + 'px');
    }

    function getElementDocTop(el) {
        var top = 0;
        while (el) {
            top += el.offsetTop;
            el = el.offsetParent;
        }
        return top;
    }

    function setupSingleDeck(nav, grid) {
        if (!nav || !grid) return;

        var pills = Array.prototype.slice.call(nav.querySelectorAll('.spog-nav-pill, .deck-nav-pill'));
        var cards = Array.prototype.slice.call(grid.querySelectorAll('.spog-deck-card, .deck-card'));
        if (cards.length === 0 || pills.length === 0) return;

        // If pills define data-target, ensure cards array ordering matches pills target sequence
        var pillTargets = pills.map(function(p) { return p.getAttribute('data-target'); }).filter(Boolean);
        if (pillTargets.length === pills.length) {
            cards.sort(function(a, b) {
                var aIdx = pillTargets.indexOf(a.id);
                var bIdx = pillTargets.indexOf(b.id);
                if (aIdx === -1) aIdx = 999;
                if (bIdx === -1) bIdx = 999;
                return aIdx - bIdx;
            });
        }

        var currentActiveCard = null;
        var lastSyncedCard = null;
        var lastSyncedHeight = 0;
        var isClickScrolling = false;
        var clickScrollTimeout = null;
        var isTouchActive = false;
        var touchStartScrollLeft = 0;
        var lastObservedScrollLeft = grid.scrollLeft || 0;
        var didScrollHorizontally = false;
        var scrollDebounceTimeout = null;

        // Scroll ONLY the horizontal pill strip — NEVER call pill.scrollIntoView(),
        // which cancels native vertical momentum scrolling on iOS Safari.
        function scrollPillIntoNav(pill) {
            if (!nav || !pill || window.innerWidth > 768) return;
            var navRect = nav.getBoundingClientRect();
            var pillRect = pill.getBoundingClientRect();
            var targetLeft = nav.scrollLeft + (pillRect.left - navRect.left) - (nav.clientWidth - pillRect.width) / 2;
            var maxScroll = Math.max(0, nav.scrollWidth - nav.clientWidth);
            var clamped = Math.max(0, Math.min(maxScroll, targetLeft));
            if (Math.abs(nav.scrollLeft - clamped) > 2) {
                if (typeof nav.scrollTo === 'function') {
                    nav.scrollTo({ left: clamped, behavior: 'smooth' });
                } else {
                    nav.scrollLeft = clamped;
                }
            }
        }

        // Scroll ONLY the horizontal deck grid — NEVER call card.scrollIntoView(),
        // which can fight vertical scrolling on iOS Safari.
        function scrollCardIntoGrid(targetEl, behavior) {
            if (!grid || !targetEl) return;
            var gridRect = grid.getBoundingClientRect();
            var cardRect = targetEl.getBoundingClientRect();
            var targetLeft = Math.max(0, grid.scrollLeft + (cardRect.left - gridRect.left));
            if (typeof grid.scrollTo === 'function') {
                grid.scrollTo({ left: targetLeft, behavior: behavior || 'smooth' });
            } else {
                grid.scrollLeft = targetLeft;
            }
        }

        // Snap window scroll up to the top of the view if scrolled down into content,
        // dynamically taking into account the dynamic height of sticky nav elements
        function snapToTopIfNeeded() {
            if (window.innerWidth > 768) return;
            var metrics = getDynamicNavMetrics();
            var navTop = Math.max(0, getElementDocTop(nav) - metrics.stickyTopOffset);
            var currentScroll = window.pageYOffset || document.documentElement.scrollTop;
            if (currentScroll > navTop) {
                window.scrollTo({
                    top: navTop,
                    behavior: 'smooth'
                });
            }
        }

        // Clean up mobile-specific height and overflow overrides on desktop
        function clearMobileStyles() {
            lastSyncedCard = null;
            lastSyncedHeight = 0;
            cards.forEach(function(card) {
                card.style.maxHeight = '';
                card.style.overflow = '';
                card.classList.remove('deck-card-active', 'deck-card-inactive');
            });
        }

        // Dynamically adjust inactive card heights so the grid viewport height
        // is strictly determined by the active card's content, avoiding extra empty space
        function syncCardHeights(activeCard, force) {
            if (!activeCard || window.innerWidth > 768) {
                clearMobileStyles();
                return;
            }

            // 1. Ensure active card is unconstrained so its full natural height can be measured
            if (activeCard.classList.contains('deck-card-inactive') || !activeCard.classList.contains('deck-card-active')) {
                activeCard.classList.remove('deck-card-inactive');
                activeCard.classList.add('deck-card-active');
            }
            if (activeCard.style.maxHeight !== '') activeCard.style.maxHeight = '';
            if (activeCard.style.overflow !== '') activeCard.style.overflow = '';

            var activeHeight = activeCard.offsetHeight;
            if (!activeHeight || activeHeight <= 0) return;

            if (!force && activeCard === lastSyncedCard && activeHeight === lastSyncedHeight) {
                return;
            }
            lastSyncedCard = activeCard;
            lastSyncedHeight = activeHeight;

            // 2. Clamp inactive cards to activeHeight so they cannot push down grid/page height
            var maxHeightPx = activeHeight + 'px';
            cards.forEach(function(card) {
                if (card !== activeCard) {
                    card.classList.remove('deck-card-active');
                    card.classList.add('deck-card-inactive');
                    if (card.style.maxHeight !== maxHeightPx) {
                        card.style.maxHeight = maxHeightPx;
                    }
                    if (card.style.overflow !== 'hidden') {
                        card.style.overflow = 'hidden';
                    }
                }
            });
        }

        // Update pills visual state during scroll without causing mid-gesture layout thrashing
        function updatePillVisuals(targetCard, scrollNavPill) {
            if (!targetCard) return;
            var targetId = targetCard.id;
            var targetIndex = cards.indexOf(targetCard);

            pills.forEach(function(pill, pIdx) {
                var pillTarget = pill.getAttribute('data-target');
                var isMatch = pillTarget ? (pillTarget === targetId) : (pIdx === targetIndex);
                pill.classList.toggle('active', isMatch);
                pill.setAttribute('aria-selected', isMatch ? 'true' : 'false');
                if (isMatch && scrollNavPill && window.innerWidth <= 768) {
                    scrollPillIntoNav(pill);
                }
            });
        }

        // Fully activate card when stationary (click or settled swipe)
        function setActiveCard(targetCard, snapTop, scrollNavPill) {
            if (!targetCard) return;

            var isChange = currentActiveCard !== targetCard;
            currentActiveCard = targetCard;

            updatePillVisuals(targetCard, isChange && scrollNavPill);
            if (isChange || lastSyncedCard !== targetCard) {
                syncCardHeights(targetCard, true);
            }

            if (isChange && snapTop) {
                snapToTopIfNeeded();
            }
        }

        // Click on a navigation pill -> switch card, snap up if needed, smoothly scroll to card
        pills.forEach(function(pill, index) {
            pill.addEventListener('click', function(e) {
                e.preventDefault();
                var targetId = pill.getAttribute('data-target');
                var targetEl = (targetId ? document.getElementById(targetId) : null) || cards[index];
                if (targetEl) {
                    isClickScrolling = true;
                    setActiveCard(targetEl, true, true);
                    scrollCardIntoGrid(targetEl, 'smooth');

                    clearTimeout(clickScrollTimeout);
                    clickScrollTimeout = setTimeout(function() {
                        isClickScrolling = false;
                        lastObservedScrollLeft = grid.scrollLeft || 0;
                    }, 500);
                }
            });
        });

        // Determine which card is centered horizontally during swipe
        function getClosestCard() {
            var gridRect = grid.getBoundingClientRect();
            var gridCenter = gridRect.left + gridRect.width / 2;

            var bestCard = null;
            var bestDist = Infinity;

            cards.forEach(function(card) {
                var cardRect = card.getBoundingClientRect();
                var cardCenter = cardRect.left + cardRect.width / 2;
                var dist = Math.abs(cardCenter - gridCenter);

                if (dist < bestDist) {
                    bestDist = dist;
                    bestCard = card;
                }
            });

            return bestCard;
        }

        // On horizontal scroll while gesture is active: ONLY update pill indicator, NEVER snap vertical scroll or mutate card heights
        function onScrollProgress() {
            if (isClickScrolling) return;
            var closest = getClosestCard();
            if (closest) {
                updatePillVisuals(closest, false);
            }
        }

        // When swipe scroll completes and settles: finalize card activation, sync height, and snap scroll up if needed
        function onScrollSettled() {
            if (isClickScrolling) return;
            didScrollHorizontally = false;
            lastObservedScrollLeft = grid.scrollLeft || 0;
            var closest = getClosestCard();
            if (closest) {
                setActiveCard(closest, true, true);
            }
        }

        // Touch tracking to distinguish pure vertical scrolling from horizontal deck swipes.
        // Pure vertical touches MUST NOT schedule onScrollSettled() or touch layout/scroll mid-momentum.
        grid.addEventListener('touchstart', function() {
            isTouchActive = true;
            touchStartScrollLeft = grid.scrollLeft || 0;
            didScrollHorizontally = false;
        }, { passive: true });

        function handleTouchRelease() {
            isTouchActive = false;
            var movedHorizontally = didScrollHorizontally || Math.abs((grid.scrollLeft || 0) - touchStartScrollLeft) > 1;
            if (!movedHorizontally) {
                return;
            }
            clearTimeout(scrollDebounceTimeout);
            scrollDebounceTimeout = setTimeout(function() {
                onScrollSettled();
            }, 100);
        }

        grid.addEventListener('touchend', handleTouchRelease, { passive: true });
        grid.addEventListener('touchcancel', handleTouchRelease, { passive: true });

        // Scroll listener with RAF throttling for responsive pill progress (horizontal scroll only)
        var ticking = false;
        grid.addEventListener('scroll', function() {
            var currentLeft = grid.scrollLeft || 0;
            if (Math.abs(currentLeft - lastObservedScrollLeft) < 0.5) {
                return;
            }
            lastObservedScrollLeft = currentLeft;
            didScrollHorizontally = true;

            if (!ticking) {
                window.requestAnimationFrame(function() {
                    onScrollProgress();
                    ticking = false;
                });
                ticking = true;
            }

            clearTimeout(scrollDebounceTimeout);
            scrollDebounceTimeout = setTimeout(function() {
                if (!isTouchActive && didScrollHorizontally) {
                    onScrollSettled();
                }
            }, 140);
        }, { passive: true });

        if ('onscrollend' in window) {
            grid.addEventListener('scrollend', function() {
                if (!isTouchActive && didScrollHorizontally) {
                    onScrollSettled();
                }
            });
        }

        // Responsive resize: ignore vertical-only height changes caused by iOS Safari
        // URL/toolbar collapse & expand during scrolling (only react when width changes).
        window.addEventListener('resize', function() {
            var newWidth = window.innerWidth;
            if (newWidth === lastViewportWidth) {
                return;
            }
            lastViewportWidth = newWidth;
            updateNavCssVariables();
            if (newWidth <= 768) {
                if (currentActiveCard) {
                    syncCardHeights(currentActiveCard, true);
                }
            } else {
                clearMobileStyles();
            }
        });

        window.addEventListener('load', function() {
            updateNavCssVariables();
            if (currentActiveCard && window.innerWidth <= 768) {
                syncCardHeights(currentActiveCard, false);
            }
        });

        if ('ResizeObserver' in window) {
            var ro = new ResizeObserver(function(entries) {
                if (window.innerWidth > 768 || !currentActiveCard) return;
                var activeChanged = false;
                for (var i = 0; i < entries.length; i++) {
                    if (entries[i].target === currentActiveCard) {
                        activeChanged = true;
                        break;
                    }
                }
                if (activeChanged) {
                    syncCardHeights(currentActiveCard, false);
                }
            });
            cards.forEach(function(card) {
                ro.observe(card);
            });
        }

        // Initialize active card (supports URL hash or active pill)
        var hash = window.location.hash ? window.location.hash.substring(1) : '';
        var initialCard = null;
        if (hash) {
            initialCard = document.getElementById(hash);
        }
        if (!initialCard) {
            var activePill = nav.querySelector('.spog-nav-pill.active, .deck-nav-pill.active');
            if (activePill) {
                var tid = activePill.getAttribute('data-target');
                initialCard = (tid ? document.getElementById(tid) : null);
            }
        }
        if (!initialCard) {
            initialCard = cards[0];
        }

        if (initialCard) {
            setActiveCard(initialCard, false, false);
            if (initialCard !== cards[0] && hash) {
                setTimeout(function() {
                    scrollCardIntoGrid(initialCard, 'auto');
                    lastObservedScrollLeft = grid.scrollLeft || 0;
                }, 50);
            }
        }
    }

    function initAllDecks() {
        updateNavCssVariables();

        // 1. SPOG Deck
        var spogNav = document.getElementById('spog-nav');
        var spogGrid = document.querySelector('.spog-layout-grid');
        if (spogNav && spogGrid) {
            setupSingleDeck(spogNav, spogGrid);
        }

        // 2. Generic Decks (.deck-nav + .deck-layout-grid)
        var allNavs = document.querySelectorAll('.deck-nav');
        allNavs.forEach(function(nav) {
            if (nav === spogNav) return;
            var container = nav.closest('.w3-content') || nav.closest('.w3-container') || nav.parentElement;
            var grid = container ? container.querySelector('.deck-layout-grid') : document.querySelector('.deck-layout-grid');
            if (grid) {
                setupSingleDeck(nav, grid);
            }
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initAllDecks);
    } else {
        initAllDecks();
    }
})();
