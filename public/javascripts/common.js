/**
 * MonMon Common UI Utilities
 * - Generic autoscrolling to edit forms (#edit-form or .editing-mode)
 * - Scroll position restoration across deletes and inline actions
 * - iOS Safari keypad enhancement for all number inputs (inputmode="decimal" / "numeric")
 */
(function () {
    'use strict';

    // 1. Scroll restoration & Autoscrolling
    function handleScrollState() {
        var savedScroll = sessionStorage.getItem('monmonReturnScrollPos');
        if (savedScroll !== null) {
            sessionStorage.removeItem('monmonReturnScrollPos');
            var pos = parseInt(savedScroll, 10);
            if (!isNaN(pos)) {
                window.scrollTo(0, pos);
                return;
            }
        }

        if (window.location.hash === '#edit-form' || document.querySelector('#edit-form.editing-mode')) {
            var editTarget = document.getElementById('edit-form');
            if (editTarget) {
                editTarget.scrollIntoView({ behavior: 'smooth', block: 'start' });
                setTimeout(function () {
                    var firstInput = editTarget.querySelector('input:not([type="hidden"]), select, textarea');
                    if (firstInput) {
                        firstInput.focus();
                    }
                }, 250);
            }
        }
    }

    // 2. Capture scroll position before delete actions or form submits marked for preservation
    function bindScrollPreservation() {
        document.addEventListener('click', function (e) {
            var target = e.target.closest('.btn-delete, [data-save-scroll], .save-scroll');
            if (target) {
                sessionStorage.setItem('monmonReturnScrollPos', window.scrollY || window.pageYOffset || 0);
            }
        }, true);
    }

    // 3. iOS Mobile Keyboard Enhancement:
    // Ensures all number inputs trigger the decimal or numeric keypad on iOS Safari instead of full QWERTY
    function enhanceNumericInputs() {
        var inputs = document.querySelectorAll('input[type="number"]');
        inputs.forEach(function (inp) {
            var step = inp.getAttribute('step');
            var name = (inp.getAttribute('name') || '').toLowerCase();
            var isInteger = step === '1' || name === 'priority' || name === 'outgoingday' || name === 'incomingday';

            if (!inp.getAttribute('inputmode')) {
                inp.setAttribute('inputmode', isInteger ? 'numeric' : 'decimal');
            }
            if (!inp.getAttribute('pattern')) {
                inp.setAttribute('pattern', isInteger ? '[0-9]*' : '[0-9]*[.,]?[0-9]*');
            }
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function () {
            handleScrollState();
            bindScrollPreservation();
            enhanceNumericInputs();
        });
    } else {
        handleScrollState();
        bindScrollPreservation();
        enhanceNumericInputs();
    }
})();
