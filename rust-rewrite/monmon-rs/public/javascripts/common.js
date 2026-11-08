/**
 * MonMon Common UI Utilities
 * - Generic autoscrolling to edit forms (#edit-form or .editing-mode)
 * - Scroll position restoration across deletes and inline actions
 * - iOS Safari / Mobile keypad enhancement with full negative numbers support (± toggle)
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

    // 3. Robust touch & click binder for iOS Safari + desktop
    function bindTapOrClick(element, handler) {
        if (!element) return;
        var lastTouchTime = 0;

        // Prevent mouse down from blurring focused input on desktop
        element.addEventListener('mousedown', function (e) {
            e.preventDefault();
        });

        // On mobile touch devices (iOS Safari, Android):
        // NEVER preventDefault on touchstart, as iOS suppresses all click/touch interactions!
        // Instead, handle action on touchend with preventDefault to avoid delayed click.
        element.addEventListener('touchend', function (e) {
            lastTouchTime = Date.now();
            e.preventDefault();
            handler(e);
        }, { passive: false });

        element.addEventListener('click', function (e) {
            // If fired within 600ms of touchend, ignore synthetic duplicate click
            if (Date.now() - lastTouchTime < 600) {
                e.preventDefault();
                return;
            }
            e.preventDefault();
            handler(e);
        });
    }

    // 4. Numeric & Negative Inputs Enhancement
    function permitsNegative(inp) {
        if (inp.hasAttribute('data-allow-negative')) {
            return inp.getAttribute('data-allow-negative') !== 'false';
        }
        var min = inp.getAttribute('min');
        if (min !== null && parseFloat(min) >= 0) {
            return false;
        }
        var name = (inp.getAttribute('name') || '').toLowerCase();
        if (name === 'priority' || name === 'outgoingday' || name === 'incomingday' || name === 'savingspercentage' || name === 'humansplit') {
            return false;
        }
        return true;
    }

    function toggleSign(inp) {
        if (!inp) return;
        var val = inp.value ? inp.value.trim() : '';
        if (val === '') {
            inp.value = '-';
        } else if (val === '-') {
            inp.value = '';
        } else if (val.startsWith('-')) {
            inp.value = val.substring(1);
        } else if (val.startsWith('+')) {
            inp.value = '-' + val.substring(1);
        } else {
            inp.value = '-' + val;
        }
        updateInputSignStyle(inp);
        inp.dispatchEvent(new Event('input', { bubbles: true }));
        inp.dispatchEvent(new Event('change', { bubbles: true }));

        // Ensure input remains focused and cursor is placed at end
        try {
            inp.focus();
            if (inp.setSelectionRange && inp.type !== 'number') {
                var len = inp.value.length;
                inp.setSelectionRange(len, len);
            }
        } catch (err) {
            // Ignore if browser restricts setSelectionRange
        }
    }

    function updateInputSignStyle(inp) {
        var isNeg = inp && inp.value && inp.value.trim().startsWith('-');
        var container = inp.closest('div');
        if (container) {
            var inlineBtn = container.querySelector('.btn-toggle-sign, #balance-sign-toggle');
            if (inlineBtn) {
                if (isNeg) {
                    inlineBtn.style.background = 'rgba(239, 68, 68, 0.2)';
                    inlineBtn.style.color = '#ef4444';
                    inlineBtn.style.borderColor = '#ef4444';
                } else {
                    inlineBtn.style.background = 'var(--bg-card, #1e293b)';
                    inlineBtn.style.color = 'var(--text-main, #f8fafc)';
                    inlineBtn.style.borderColor = 'var(--border-color, #334155)';
                }
            }
        }
        var pill = document.getElementById('monmon-sign-pill');
        if (pill && pill._currentInput === inp) {
            if (isNeg) {
                pill.style.background = '#ef4444';
                pill.innerHTML = '<span style="font-size:1.15rem;font-weight:900;">&minus;</span> Negative';
            } else {
                pill.style.background = '#6366f1';
                pill.innerHTML = '<span style="font-size:1.15rem;font-weight:900;">&plusmn;</span> Negative';
            }
        }
    }

    var floatingPill = null;
    function getOrCreateFloatingPill() {
        if (!floatingPill) {
            floatingPill = document.createElement('button');
            floatingPill.id = 'monmon-sign-pill';
            floatingPill.type = 'button';
            floatingPill.setAttribute('aria-label', 'Toggle negative sign');
            floatingPill.style.cssText = 'position:fixed;bottom:20px;left:50%;transform:translateX(-50%);z-index:9999;' +
                'background:#6366f1;color:#fff;border:none;font-weight:700;font-size:0.9rem;padding:8px 18px;' +
                'border-radius:9999px;box-shadow:0 4px 16px rgba(0,0,0,0.5);cursor:pointer;display:none;align-items:center;gap:6px;' +
                'user-select:none;-webkit-user-select:none;-webkit-tap-highlight-color:transparent;touch-action:manipulation;transition:background 0.15s ease;';
            floatingPill.innerHTML = '<span style="font-size:1.15rem;font-weight:900;">&plusmn;</span> Negative';

            bindTapOrClick(floatingPill, function () {
                if (floatingPill._currentInput) {
                    toggleSign(floatingPill._currentInput);
                }
            });

            document.body.appendChild(floatingPill);
        }
        return floatingPill;
    }

    function enhanceNumericInputs() {
        var inputs = document.querySelectorAll('input[type="number"], input[inputmode="decimal"], input[inputmode="numeric"]');
        inputs.forEach(function (inp) {
            var step = inp.getAttribute('step');
            var name = (inp.getAttribute('name') || '').toLowerCase();
            var isInteger = step === '1' || name === 'priority' || name === 'outgoingday' || name === 'incomingday';
            var canBeNegative = permitsNegative(inp);

            if (!inp.getAttribute('inputmode')) {
                inp.setAttribute('inputmode', isInteger ? 'numeric' : 'decimal');
            }

            if (canBeNegative) {
                inp.setAttribute('pattern', '[-+]?[0-9]*[.,]?[0-9]*');
                if (inp.type === 'number' && !inp.hasAttribute('min')) {
                    inp.type = 'text';
                }
            } else {
                inp.setAttribute('pattern', isInteger ? '[0-9]*' : '[0-9]*[.,]?[0-9]*');
            }

            inp.addEventListener('input', function () {
                updateInputSignStyle(inp);
            });

            if (canBeNegative) {
                inp.addEventListener('focus', function () {
                    // If the input already has an inline toggle right next to it, don't clutter with floating pill
                    var container = inp.closest('div');
                    if (container && container.querySelector('.btn-toggle-sign, #balance-sign-toggle')) {
                        return;
                    }
                    if (window.innerWidth <= 768 || ('ontouchstart' in window)) {
                        var pill = getOrCreateFloatingPill();
                        pill._currentInput = inp;
                        updateInputSignStyle(inp);
                        pill.style.display = 'flex';
                    }
                });

                inp.addEventListener('blur', function () {
                    setTimeout(function () {
                        if (floatingPill && floatingPill._currentInput === inp) {
                            floatingPill.style.display = 'none';
                            floatingPill._currentInput = null;
                        }
                    }, 250);
                });
            }
        });

        // Wire up all inline .btn-toggle-sign buttons
        var signButtons = document.querySelectorAll('.btn-toggle-sign, #balance-sign-toggle');
        signButtons.forEach(function (btn) {
            if (btn._boundSign) return;
            btn._boundSign = true;

            bindTapOrClick(btn, function () {
                var targetId = btn.getAttribute('data-target');
                var targetInput = targetId ? document.getElementById(targetId) : null;
                if (!targetInput) {
                    var container = btn.closest('div');
                    targetInput = container ? container.querySelector('input') : null;
                }
                if (targetInput) {
                    toggleSign(targetInput);
                }
            });
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
