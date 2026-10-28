/**
 * MonMon Waiting List UI-Blocking Modal & Auto-Polling
 */
(function() {
    'use strict';

    var pollTimer = null;
    var isChecking = false;

    function showModal(username) {
        var modal = document.getElementById('monmon-waiting-list-modal');
        if (!modal) return;

        modal.style.display = 'flex';
        document.body.style.overflow = 'hidden';

        var userEl = document.getElementById('waiting-list-username');
        if (userEl && username) {
            userEl.textContent = username;
        }

        // Disable interaction with underlying navigation and main content
        var mainNav = document.getElementById('main-nav');
        if (mainNav) mainNav.style.pointerEvents = 'none';

        // Disable all form submits while waiting list modal is active
        document.querySelectorAll('form').forEach(function(form) {
            if (!form.closest('#monmon-waiting-list-modal')) {
                form.addEventListener('submit', function(e) {
                    e.preventDefault();
                    e.stopPropagation();
                    alert("Your account is on the waiting list pending administrator approval.");
                    return false;
                }, true);
            }
        });

        startPolling();
    }

    function hideModal() {
        var modal = document.getElementById('monmon-waiting-list-modal');
        if (!modal) return;

        modal.style.display = 'none';
        document.body.style.overflow = '';
        var mainNav = document.getElementById('main-nav');
        if (mainNav) mainNav.style.pointerEvents = '';

        if (pollTimer) {
            clearInterval(pollTimer);
            pollTimer = null;
        }
    }

    function handleApproved() {
        var modal = document.getElementById('monmon-waiting-list-modal');
        if (!modal) return;

        var icon = document.getElementById('waiting-list-icon');
        if (icon) {
            icon.className = 'fas fa-check-circle';
        }
        var iconRing = modal.querySelector('.waiting-list-icon-ring');
        if (iconRing) {
            iconRing.style.background = 'rgba(16, 185, 129, 0.15)';
            iconRing.style.borderColor = 'rgba(16, 185, 129, 0.4)';
            iconRing.style.color = '#10b981';
        }

        var title = document.getElementById('waiting-list-modal-title');
        if (title) title.textContent = 'Account Approved!';

        var badge = document.getElementById('waiting-list-badge');
        if (badge) {
            badge.className = 'badge-pill badge-freshness-fresh';
            badge.innerHTML = '<i class="fas fa-check-circle"></i> Approved';
        }

        var msg = document.getElementById('waiting-list-message');
        if (msg) msg.textContent = 'Great news! Your account has been approved by an administrator. Unlocking MonMon...';

        var pollText = document.getElementById('waiting-list-poll-status');
        if (pollText) pollText.textContent = 'Loading your dashboard...';

        var spinner = document.getElementById('waiting-list-spinner');
        if (spinner) spinner.style.color = '#10b981';

        setTimeout(function() {
            window.location.reload();
        }, 900);
    }

    window.monmonCheckWaitingList = function(isManual) {
        if (isChecking) return;
        isChecking = true;

        var statusEl = document.getElementById('waiting-list-poll-status');
        var checkBtn = document.getElementById('waiting-list-check-btn');

        if (isManual && statusEl) {
            statusEl.textContent = 'Checking status now...';
            if (checkBtn) checkBtn.disabled = true;
        }

        fetch('/api/user/accessStatus', {
            method: 'GET',
            headers: {
                'Accept': 'application/json'
            },
            credentials: 'same-origin'
        })
        .then(function(res) {
            return res.json();
        })
        .then(function(data) {
            isChecking = false;
            if (checkBtn) checkBtn.disabled = false;
            if (!data) return;

            if (data.authenticated && !data.approved) {
                // User is authenticated but on the waiting list!
                showModal(data.username || data.email);
                if (statusEl) {
                    statusEl.textContent = 'Checking for approval automatically (every 5s)...';
                }
            } else if (data.authenticated && data.approved) {
                // User is approved! If modal is open, celebrate and reload
                var modal = document.getElementById('monmon-waiting-list-modal');
                if (modal && modal.style.display === 'flex') {
                    handleApproved();
                } else {
                    hideModal();
                }
            } else {
                hideModal();
            }
        })
        .catch(function(err) {
            isChecking = false;
            if (checkBtn) checkBtn.disabled = false;
            if (statusEl) {
                statusEl.textContent = 'Checking for approval automatically...';
            }
        });
    };

    function startPolling() {
        if (pollTimer) return;
        pollTimer = setInterval(function() {
            window.monmonCheckWaitingList(false);
        }, 5000);
    }

    // Run check on DOM load
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function() {
            window.monmonCheckWaitingList(false);
        });
    } else {
        window.monmonCheckWaitingList(false);
    }
})();
