// MonMon SPOG Mobile Deck Navigation & Swipe Synchronization
(function() {
    'use strict';

    function initSpogDeck() {
        var nav = document.getElementById('spog-nav');
        var grid = document.querySelector('.spog-layout-grid');
        if (!nav || !grid) return;

        var pills = Array.prototype.slice.call(nav.querySelectorAll('.spog-nav-pill'));
        var cards = Array.prototype.slice.call(grid.querySelectorAll('.spog-deck-card'));
        if (cards.length === 0 || pills.length === 0) return;

        var currentActiveId = null;
        var isClickScrolling = false;
        var clickScrollTimeout = null;

        function setActivePill(targetId, scrollNav) {
            if (!targetId || targetId === currentActiveId) return;
            currentActiveId = targetId;

            pills.forEach(function(pill) {
                var isMatch = pill.getAttribute('data-target') === targetId;
                pill.classList.toggle('active', isMatch);
                pill.setAttribute('aria-selected', isMatch ? 'true' : 'false');
                if (isMatch && scrollNav !== false && window.innerWidth <= 768) {
                    pill.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' });
                }
            });
        }

        // Click on a pill -> scroll to card
        pills.forEach(function(pill) {
            pill.addEventListener('click', function(e) {
                e.preventDefault();
                var targetId = pill.getAttribute('data-target');
                var targetEl = document.getElementById(targetId);
                if (targetEl) {
                    isClickScrolling = true;
                    setActivePill(targetId, true);
                    targetEl.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'start' });

                    clearTimeout(clickScrollTimeout);
                    clickScrollTimeout = setTimeout(function() {
                        isClickScrolling = false;
                    }, 650);
                }
            });
        });

        // Determine which card is most centered in the horizontal scroll view
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

            if (bestCard && bestCard.id) {
                setActivePill(bestCard.id, true);
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

        // Fallback IntersectionObserver for edge cases
        if ('IntersectionObserver' in window) {
            var observer = new IntersectionObserver(function(entries) {
                if (isClickScrolling) return;
                entries.forEach(function(entry) {
                    if (entry.isIntersecting && entry.intersectionRatio >= 0.4) {
                        setActivePill(entry.target.id, true);
                    }
                });
            }, {
                root: window.innerWidth <= 768 ? grid : null,
                threshold: [0.2, 0.4, 0.6]
            });

            cards.forEach(function(card) {
                observer.observe(card);
            });
        }

        // Initialize first active pill
        var firstPill = pills[0];
        if (firstPill) {
            setActivePill(firstPill.getAttribute('data-target'), false);
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initSpogDeck);
    } else {
        initSpogDeck();
    }
})();
