/**
 * Universal Modal Scroll Lock & Boundary Chaining Eliminator
 * 
 * Guarantees across all browsers and devices:
 * 1. Background page scroll is firmly locked when any modal is open.
 * 2. Scrolling inside scrollable modal content works smoothly.
 * 3. Reaching top or bottom limits inside the modal NEVER leaks or chains scroll to the background.
 * 4. Touching or scrolling on the modal backdrop outside the card is completely prevented.
 */
(function () {
    'use strict';

    var MODAL_SELECTORS = [
        '.modal-backdrop',
        '.w3-modal',
        '.waiting-list-modal-overlay',
        '[role="dialog"]',
        '.modal'
    ];

    var touchStartY = 0;
    var isLocked = false;
    var savedScrollY = 0;

    function getOpenModals() {
        var openModals = [];
        MODAL_SELECTORS.forEach(function (selector) {
            var elements = document.querySelectorAll(selector);
            elements.forEach(function (el) {
                if (isModalVisible(el)) {
                    openModals.push(el);
                }
            });
        });
        return openModals;
    }

    function isModalVisible(el) {
        if (!el) return false;
        var style = window.getComputedStyle(el);
        return style.display !== 'none' && style.visibility !== 'hidden' && style.opacity !== '0';
    }

    function getScrollableAncestor(target, modal) {
        var el = target;
        while (el && el !== modal && el !== document.body && el !== document.documentElement) {
            var style = window.getComputedStyle(el);
            var overflowY = style.overflowY;
            var canScroll = (overflowY === 'auto' || overflowY === 'scroll') && (el.scrollHeight > el.clientHeight);
            if (canScroll) {
                return el;
            }
            el = el.parentElement;
        }
        return null;
    }

    function lockScroll() {
        if (isLocked) return;
        isLocked = true;

        savedScrollY = window.scrollY || window.pageYOffset || document.documentElement.scrollTop || 0;
        var scrollbarWidth = window.innerWidth - document.documentElement.clientWidth;

        document.documentElement.classList.add('modal-scroll-locked');
        document.body.classList.add('modal-scroll-locked');

        if (scrollbarWidth > 0) {
            document.body.style.paddingRight = scrollbarWidth + 'px';
        }
    }

    function unlockScroll() {
        if (!isLocked) return;
        isLocked = false;

        document.documentElement.classList.remove('modal-scroll-locked');
        document.body.classList.remove('modal-scroll-locked');
        document.body.style.paddingRight = '';

        if (savedScrollY > 0) {
            window.scrollTo(0, savedScrollY);
        }
    }

    function syncLockState() {
        var openModals = getOpenModals();
        if (openModals.length > 0) {
            lockScroll();
        } else {
            unlockScroll();
        }
    }

    // Wheel event handler (Desktop / Trackpad)
    function onWheel(e) {
        var openModals = getOpenModals();
        if (openModals.length === 0) return;

        // Find which modal contains the event target
        var targetModal = null;
        for (var i = 0; i < openModals.length; i++) {
            if (openModals[i].contains(e.target) || openModals[i] === e.target) {
                targetModal = openModals[i];
                break;
            }
        }

        // If wheeled outside modal or directly on modal backdrop
        if (!targetModal) {
            e.preventDefault();
            return;
        }

        var scrollable = getScrollableAncestor(e.target, targetModal);
        if (!scrollable) {
            // Not a scrollable element (e.g. backdrop, modal header, static text)
            e.preventDefault();
            return;
        }

        var deltaY = e.deltaY;
        var scrollTop = scrollable.scrollTop;
        var maxScrollTop = scrollable.scrollHeight - scrollable.clientHeight;

        // When at top and scrolling UP -> prevent bubbling to background
        if (deltaY < 0 && scrollTop <= 0) {
            e.preventDefault();
            return;
        }

        // When at bottom and scrolling DOWN -> prevent bubbling to background
        if (deltaY > 0 && scrollTop >= maxScrollTop - 1) {
            e.preventDefault();
            return;
        }
    }

    // Touch event handlers (Mobile / Touch devices)
    function onTouchStart(e) {
        if (e.touches.length > 0) {
            touchStartY = e.touches[0].clientY;
        }
    }

    function onTouchMove(e) {
        var openModals = getOpenModals();
        if (openModals.length === 0) return;

        var targetModal = null;
        for (var i = 0; i < openModals.length; i++) {
            if (openModals[i].contains(e.target) || openModals[i] === e.target) {
                targetModal = openModals[i];
                break;
            }
        }

        if (!targetModal) {
            e.preventDefault();
            return;
        }

        var scrollable = getScrollableAncestor(e.target, targetModal);
        if (!scrollable) {
            e.preventDefault();
            return;
        }

        if (e.touches.length > 0) {
            var currentY = e.touches[0].clientY;
            var deltaY = currentY - touchStartY;
            var scrollTop = scrollable.scrollTop;
            var maxScrollTop = scrollable.scrollHeight - scrollable.clientHeight;

            // Swiping down to scroll up at the top edge
            if (deltaY > 0 && scrollTop <= 0) {
                e.preventDefault();
                return;
            }

            // Swiping up to scroll down at the bottom edge
            if (deltaY < 0 && scrollTop >= maxScrollTop - 1) {
                e.preventDefault();
                return;
            }
        }
    }

    // Set up MutationObserver to react to any modal opening or closing anywhere in the DOM
    function setupObserver() {
        var observer = new MutationObserver(function () {
            syncLockState();
        });

        observer.observe(document.body, {
            childList: true,
            subtree: true,
            attributes: true,
            attributeFilter: ['style', 'class', 'hidden']
        });
    }

    document.addEventListener('DOMContentLoaded', function () {
        setupObserver();
        syncLockState();

        // Non-passive wheel and touch listeners with capture to intercept boundary events
        window.addEventListener('wheel', onWheel, { passive: false, capture: true });
        window.addEventListener('touchstart', onTouchStart, { passive: true, capture: true });
        window.addEventListener('touchmove', onTouchMove, { passive: false, capture: true });
    });
})();
