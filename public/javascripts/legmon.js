// LegMon Frontend Status & Log Viewer (Plain JavaScript)
(function() {
    'use strict';

    function initLegmon() {
        checkStatus();
        // Check if upload was recently triggered or pending statements exist
        var pendingCard = document.querySelector('[data-has-pending-statement="true"]');
        if (pendingCard) {
            // Check status immediately
            checkStatus();
        }
    }

    function checkStatus() {
        var badge = document.getElementById('legmon-status-badge');
        if (!badge) return;

        fetch('/api/legmon/status')
            .then(function(res) { return res.json(); })
            .then(function(data) {
                if (data.status === 'ok') {
                    badge.style.background = '#dcfce7';
                    badge.style.color = '#15803d';
                    badge.innerHTML = '<i class="fas fa-check-circle"></i> LegMon Online';
                } else if (data.status === 'warning') {
                    badge.style.background = '#fef3c7';
                    badge.style.color = '#b45309';
                    badge.innerHTML = '<i class="fas fa-exclamation-circle"></i> LegMon Warning';
                } else {
                    badge.style.background = '#fee2e2';
                    badge.style.color = '#b91c1c';
                    badge.innerHTML = '<i class="fas fa-times-circle"></i> LegMon Offline';
                }
            })
            .catch(function(err) {
                badge.style.background = '#fee2e2';
                badge.style.color = '#b91c1c';
                badge.innerHTML = '<i class="fas fa-times-circle"></i> LegMon Offline';
            });
    }

    window.toggleLegmonLogs = function() {
        var container = document.getElementById('legmon-logs-container');
        if (!container) return;
        if (container.style.display === 'none' || container.style.display === '') {
            container.style.display = 'block';
            window.fetchLegmonLogs();
        } else {
            container.style.display = 'none';
        }
    };

    window.fetchLegmonLogs = function() {
        var pre = document.getElementById('legmon-logs-output');
        var errBanner = document.getElementById('legmon-error-banner');
        var errText = document.getElementById('legmon-error-text');
        if (!pre) return;

        pre.textContent = 'Fetching LegMon service logs...';

        fetch('/api/legmon/logs')
            .then(function(res) { return res.json(); })
            .then(function(data) {
                if (data.hasError && data.lastError) {
                    if (errBanner && errText) {
                        errBanner.style.display = 'block';
                        errText.textContent = data.lastError;
                    }
                } else if (errBanner) {
                    errBanner.style.display = 'none';
                }

                if (data.logs && data.logs.length > 0) {
                    var lines = data.logs.map(function(item) {
                        var color = '#94a3b8';
                        if (item.level === 'ERROR') color = '#f87171';
                        else if (item.level === 'WARN') color = '#fbbf24';
                        else if (item.level === 'INFO') color = '#e2e8f0';
                        return '[' + item.time.substring(11, 19) + '] ' + item.level + ' - ' + item.msg;
                    });
                    pre.textContent = lines.join('\n');
                    pre.scrollTop = pre.scrollHeight;
                } else {
                    pre.textContent = data.error ? ('Service reported: ' + data.error) : 'No logs recorded yet.';
                }
            })
            .catch(function(err) {
                pre.textContent = 'Could not retrieve LegMon logs: ' + err.message;
            });
    };

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initLegmon);
    } else {
        initLegmon();
    }
})();
