// MonMon /historicSpent View Switcher, Hash Persistence & Swipe Deck Sync
// Plain JavaScript (Zero CoffeeScript).
(function() {
    'use strict';

    var HASH_TO_CARD = {
        'pots': 'historic-card-pots',
        'all-accounts': 'historic-card-all-accounts'
    };

    var CARD_TO_HASH = {
        'historic-card-pots': 'pots',
        'historic-card-all-accounts': 'all-accounts'
    };

    function normalizeHash(rawHash) {
        var cleaned = String(rawHash || '').replace(/^#/, '').trim().toLowerCase();
        return HASH_TO_CARD[cleaned] ? cleaned : 'pots';
    }

    function initHistoricSpent() {
        var nav = document.getElementById('historic-spent-nav');
        var grid = document.getElementById('historic-spent-grid');
        if (!nav || !grid) return;

        var pills = Array.prototype.slice.call(nav.querySelectorAll('.deck-nav-pill'));
        var cards = Array.prototype.slice.call(grid.querySelectorAll('.deck-card'));
        if (pills.length === 0 || cards.length === 0) return;

        var isSyncing = false;

        function updateHash(hashKey) {
            var desired = '#' + hashKey;
            if (window.location.hash !== desired && window.history && typeof window.history.replaceState === 'function') {
                var base = (window.location.pathname || '/historicSpent') + (window.location.search || '');
                window.history.replaceState(null, '', base + desired);
            }
        }

        function scrollGridToCard(cardEl, behavior) {
            if (!grid || !cardEl || window.innerWidth > 768) return;
            var gridRect = grid.getBoundingClientRect();
            var cardRect = cardEl.getBoundingClientRect();
            var targetLeft = Math.max(0, (grid.scrollLeft || 0) + (cardRect.left - gridRect.left));
            if (typeof grid.scrollTo === 'function') {
                grid.scrollTo({ left: targetLeft, behavior: behavior || 'auto' });
            } else {
                grid.scrollLeft = targetLeft;
            }
        }

        function activateView(hashKey, opts) {
            var options = opts || {};
            var key = normalizeHash(hashKey);
            var targetCardId = HASH_TO_CARD[key];
            var targetCard = document.getElementById(targetCardId);
            if (!targetCard) return;

            isSyncing = true;
            try {
                cards.forEach(function(card) {
                    card.classList.toggle('historic-tab-active', card.id === targetCardId);
                });

                pills.forEach(function(pill) {
                    var pillTarget = pill.getAttribute('data-target');
                    var isMatch = pillTarget === targetCardId;
                    pill.classList.toggle('active', isMatch);
                    pill.setAttribute('aria-selected', isMatch ? 'true' : 'false');
                });

                if (options.updateUrl !== false) {
                    updateHash(key);
                }

                if (options.scrollMobile) {
                    scrollGridToCard(targetCard, options.scrollBehavior || 'auto');
                }
            } finally {
                isSyncing = false;
            }
        }

        // Click on pills switches view on desktop and syncs hash on both desktop & mobile
        pills.forEach(function(pill) {
            pill.addEventListener('click', function(e) {
                if (e && typeof e.preventDefault === 'function') {
                    e.preventDefault();
                }
                var pillHash = pill.getAttribute('data-hash') || CARD_TO_HASH[pill.getAttribute('data-target')] || 'pots';
                activateView(pillHash, { updateUrl: true, scrollMobile: false });
            });
        });

        // Observe active pill changes triggered by spog-deck.js on mobile swipe settle
        if (typeof MutationObserver === 'function') {
            var observer = new MutationObserver(function() {
                if (isSyncing) return;
                for (var i = 0; i < pills.length; i++) {
                    if (pills[i].classList.contains('active')) {
                        var targetId = pills[i].getAttribute('data-target');
                        var key = pills[i].getAttribute('data-hash') || CARD_TO_HASH[targetId];
                        if (key) {
                            isSyncing = true;
                            try {
                                cards.forEach(function(card) {
                                    card.classList.toggle('historic-tab-active', card.id === targetId);
                                });
                                updateHash(key);
                            } finally {
                                isSyncing = false;
                            }
                        }
                        break;
                    }
                }
            });
            pills.forEach(function(pill) {
                observer.observe(pill, { attributes: true, attributeFilter: ['class'] });
            });
        }

        // Handle browser Back/Forward hash navigation
        window.addEventListener('hashchange', function() {
            var key = normalizeHash(window.location.hash);
            activateView(key, { updateUrl: false, scrollMobile: true, scrollBehavior: 'smooth' });
        });

        // Prevent /spendingPace link clicks inside <summary> from toggling <details>
        var paceLinks = Array.prototype.slice.call(document.querySelectorAll('.historic-pace-link'));
        paceLinks.forEach(function(link) {
            link.addEventListener('click', function(e) {
                if (e && typeof e.stopPropagation === 'function') {
                    e.stopPropagation();
                }
            });
        });

        // Initial activation from URL hash
        var initialHash = normalizeHash(window.location.hash);
        var hasExplicitHash = Boolean(window.location.hash && HASH_TO_CARD[String(window.location.hash).replace(/^#/, '').trim().toLowerCase()]);
        activateView(initialHash, {
            updateUrl: false,
            scrollMobile: hasExplicitHash && initialHash !== 'pots',
            scrollBehavior: 'auto'
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initHistoricSpent);
    } else {
        initHistoricSpent();
    }
})();
