// MonMon Mobile Deck Navigation & Swipe Synchronization (SPOG, Bank Sync, Savings Plan, Net Worth)
(function() {
    'use strict';

    if (window.__monmonDeckInitialized) return;
    window.__monmonDeckInitialized = true;

    // Dynamically calculate the navigation metrics across mobile viewports
    function getDynamicNavMetrics() {
        var ticker = document.getElementById('ticker');
        var header = document.querySelector('header.app-header');
        var mainNav = document.getElementById('main-nav') || document.querySelector('nav.crumbs');

        var tickerHeight = (ticker && ticker.offsetHeight > 0) ? ticker.offsetHeight : 0;
        var headerHeight = (header && header.offsetHeight > 0) ? header.offsetHeight : 0;
        var mainNavHeight = (mainNav && mainNav.offsetHeight > 0) ? mainNav.offsetHeight : 0;
        var totalNavHeight = tickerHeight + headerHeight + mainNavHeight;

        // Sticky offset: #ticker is position: sticky; top: 0
        var stickyTopOffset = tickerHeight;

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

        var currentActiveCard = null;
        var isClickScrolling = false;
        var clickScrollTimeout = null;
        var isTouchActive = false;
        var scrollDebounceTimeout = null;

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
            cards.forEach(function(card) {
                card.style.maxHeight = '';
                card.style.overflow = '';
                card.classList.remove('deck-card-active', 'deck-card-inactive');
            });
        }

        // Dynamically adjust inactive card heights so the grid viewport height
        // is strictly determined by the active card's content, avoiding extra empty space
        function syncCardHeights(activeCard) {
            if (!activeCard || window.innerWidth > 768) {
                clearMobileStyles();
                return;
            }

            // 1. Ensure active card is unconstrained so its full natural height can be measured
            activeCard.classList.remove('deck-card-inactive');
            activeCard.classList.add('deck-card-active');
            activeCard.style.maxHeight = '';
            activeCard.style.overflow = '';

            var activeHeight = activeCard.offsetHeight;
            if (!activeHeight || activeHeight <= 0) return;

            // 2. Clamp inactive cards to activeHeight so they cannot push down grid/page height
            cards.forEach(function(card) {
                if (card !== activeCard) {
                    card.classList.remove('deck-card-active');
                    card.classList.add('deck-card-inactive');
                    card.style.maxHeight = activeHeight + 'px';
                    card.style.overflow = 'hidden';
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
                var isMatch = (pillTarget && pillTarget === targetId) || (pIdx === targetIndex);
                pill.classList.toggle('active', isMatch);
                pill.setAttribute('aria-selected', isMatch ? 'true' : 'false');
                if (isMatch && scrollNavPill && window.innerWidth <= 768) {
                    pill.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' });
                }
            });
        }

        // Fully activate card when stationary (click or settled swipe)
        function setActiveCard(targetCard, snapTop, scrollNavPill) {
            if (!targetCard) return;

            var isChange = currentActiveCard !== targetCard;
            currentActiveCard = targetCard;

            updatePillVisuals(targetCard, scrollNavPill);
            syncCardHeights(targetCard);

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
                    targetEl.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'start' });

                    clearTimeout(clickScrollTimeout);
                    clickScrollTimeout = setTimeout(function() {
                        isClickScrolling = false;
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
            var closest = getClosestCard();
            if (closest) {
                setActiveCard(closest, true, true);
            }
        }

        // Touch tracking to distinguish mid-drag from settling
        grid.addEventListener('touchstart', function() {
            isTouchActive = true;
        }, { passive: true });

        grid.addEventListener('touchend', function() {
            isTouchActive = false;
            clearTimeout(scrollDebounceTimeout);
            scrollDebounceTimeout = setTimeout(function() {
                onScrollSettled();
            }, 100);
        }, { passive: true });

        grid.addEventListener('touchcancel', function() {
            isTouchActive = false;
            clearTimeout(scrollDebounceTimeout);
            scrollDebounceTimeout = setTimeout(function() {
                onScrollSettled();
            }, 100);
        }, { passive: true });

        // Scroll listener with RAF throttling for responsive pill progress
        var ticking = false;
        grid.addEventListener('scroll', function() {
            if (!ticking) {
                window.requestAnimationFrame(function() {
                    onScrollProgress();
                    ticking = false;
                });
                ticking = true;
            }

            clearTimeout(scrollDebounceTimeout);
            scrollDebounceTimeout = setTimeout(function() {
                if (!isTouchActive) {
                    onScrollSettled();
                }
            }, 140);
        }, { passive: true });

        if ('onscrollend' in window) {
            grid.addEventListener('scrollend', function() {
                if (!isTouchActive) {
                    onScrollSettled();
                }
            });
        }

        // Responsive resize and content change handling
        window.addEventListener('resize', function() {
            updateNavCssVariables();
            if (window.innerWidth <= 768) {
                if (currentActiveCard) {
                    syncCardHeights(currentActiveCard);
                }
            } else {
                clearMobileStyles();
            }
        });

        window.addEventListener('load', function() {
            updateNavCssVariables();
            if (currentActiveCard && window.innerWidth <= 768) {
                syncCardHeights(currentActiveCard);
            }
        });

        if ('ResizeObserver' in window) {
            var ro = new ResizeObserver(function() {
                if (window.innerWidth <= 768 && currentActiveCard) {
                    syncCardHeights(currentActiveCard);
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
                    initialCard.scrollIntoView({ behavior: 'auto', block: 'nearest', inline: 'start' });
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
