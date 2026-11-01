// MonMon Mobile Deck Navigation & Swipe Synchronization (SPOG, Bank Sync, Savings Plan, Net Worth)
(function() {
    'use strict';

    if (window.__monmonDeckInitialized) return;
    window.__monmonDeckInitialized = true;

    function setupSingleDeck(nav, grid) {
        if (!nav || !grid) return;

        var pills = Array.prototype.slice.call(nav.querySelectorAll('.spog-nav-pill, .deck-nav-pill'));
        var cards = Array.prototype.slice.call(grid.querySelectorAll('.spog-deck-card, .deck-card'));
        if (cards.length === 0 || pills.length === 0) return;

        var currentActiveCard = null;
        var isClickScrolling = false;
        var clickScrollTimeout = null;

        // Calculate the top offset for sticky nav (below 54px main navbar)
        function getDeckNavTop() {
            var el = nav;
            var top = 0;
            while (el) {
                top += el.offsetTop;
                el = el.offsetParent;
            }
            var stickyOffset = 54; // MonMon top fixed navbar height
            return Math.max(0, top - stickyOffset);
        }

        // Snap window scroll up to the top of the view if scrolled down into content
        function snapToTopIfNeeded() {
            if (window.innerWidth > 768) return;
            var navTop = getDeckNavTop();
            if ((window.pageYOffset || document.documentElement.scrollTop) > navTop) {
                window.scrollTo(0, navTop);
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

        function setActiveCard(targetCard, snapTop, scrollNavPill) {
            if (!targetCard) return;

            var isChange = currentActiveCard !== targetCard;
            if (isChange && snapTop) {
                snapToTopIfNeeded();
            }

            currentActiveCard = targetCard;

            // Adjust card heights to fit active card content
            syncCardHeights(targetCard);

            // Synchronize pill states
            var targetId = targetCard.id;
            var targetIndex = cards.indexOf(targetCard);

            pills.forEach(function(pill, pIdx) {
                var pillTarget = pill.getAttribute('data-target');
                var isMatch = (pillTarget && pillTarget === targetId) || (pIdx === targetIndex);
                pill.classList.toggle('active', isMatch);
                pill.setAttribute('aria-selected', isMatch ? 'true' : 'false');
                if (isMatch && scrollNavPill !== false && window.innerWidth <= 768) {
                    pill.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' });
                }
            });
        }

        // Click on a navigation pill -> switch card, snap up if needed, scroll horizontally
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
                    }, 650);
                }
            });
        });

        // Determine which card is centered horizontally during swipe
        function updateActiveOnScroll() {
            if (isClickScrolling) return;

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

            if (bestCard && bestCard !== currentActiveCard) {
                setActiveCard(bestCard, true, true);
            }
        }

        // High performance scroll listener with requestAnimationFrame
        var ticking = false;
        grid.addEventListener('scroll', function() {
            if (!ticking) {
                window.requestAnimationFrame(function() {
                    updateActiveOnScroll();
                    ticking = false;
                });
                ticking = true;
            }
        }, { passive: true });

        // Scrollend event or debounced fallback to guarantee settling
        if ('onscrollend' in window) {
            grid.addEventListener('scrollend', function() {
                updateActiveOnScroll();
            });
        } else {
            var scrollDebounce = null;
            grid.addEventListener('scroll', function() {
                clearTimeout(scrollDebounce);
                scrollDebounce = setTimeout(function() {
                    updateActiveOnScroll();
                }, 120);
            }, { passive: true });
        }

        // IntersectionObserver fallback for horizontal snapping
        if ('IntersectionObserver' in window) {
            var observer = new IntersectionObserver(function(entries) {
                if (isClickScrolling) return;
                entries.forEach(function(entry) {
                    if (entry.isIntersecting && entry.intersectionRatio >= 0.5) {
                        if (entry.target !== currentActiveCard) {
                            setActiveCard(entry.target, true, true);
                        }
                    }
                });
            }, {
                root: window.innerWidth <= 768 ? grid : null,
                threshold: [0.5]
            });

            cards.forEach(function(card) {
                observer.observe(card);
            });
        }

        // Responsive resize and content change handling
        window.addEventListener('resize', function() {
            if (window.innerWidth <= 768) {
                if (currentActiveCard) {
                    syncCardHeights(currentActiveCard);
                }
            } else {
                clearMobileStyles();
            }
        });

        window.addEventListener('load', function() {
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
