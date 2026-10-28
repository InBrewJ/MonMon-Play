// MonMon SPOG Mobile Deck Navigation & Swipe Synchronization
(function() {
    'use strict';

    function initSpogDeck() {
        var nav = document.getElementById('spog-nav');
        var grid = document.querySelector('.spog-layout-grid');
        if (!nav || !grid) return;

        var pills = nav.querySelectorAll('.spog-nav-pill');
        var cards = grid.querySelectorAll('.spog-deck-card');

        function setActivePill(targetId) {
            pills.forEach(function(pill) {
                var isMatch = pill.getAttribute('data-target') === targetId;
                pill.classList.toggle('active', isMatch);
                pill.setAttribute('aria-selected', isMatch ? 'true' : 'false');
                if (isMatch && window.innerWidth <= 768) {
                    pill.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' });
                }
            });
        }

        pills.forEach(function(pill) {
            pill.addEventListener('click', function(e) {
                e.preventDefault();
                var targetId = pill.getAttribute('data-target');
                var targetEl = document.getElementById(targetId);
                if (targetEl) {
                    setActivePill(targetId);
                    targetEl.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'start' });
                }
            });
        });

        if ('IntersectionObserver' in window) {
            var observer = new IntersectionObserver(function(entries) {
                entries.forEach(function(entry) {
                    if (entry.isIntersecting && entry.intersectionRatio >= 0.5) {
                        setActivePill(entry.target.id);
                    }
                });
            }, {
                root: window.innerWidth <= 768 ? grid : null,
                threshold: 0.5
            });

            cards.forEach(function(card) {
                observer.observe(card);
            });
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initSpogDeck);
    } else {
        initSpogDeck();
    }
})();
