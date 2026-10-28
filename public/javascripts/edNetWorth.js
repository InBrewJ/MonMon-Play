/**
 * Ed Says - Net Worth & Retirement Horizon AI Coach
 * Floating Bottom Left Button & Popup Modal
 */
(function () {
    'use strict';

    var cachedResponse = null;
    var lastSpokeTimestamp = null;

    function formatTimeAgo(timestampMs) {
        if (!timestampMs) return '';
        var diffMs = Date.now() - timestampMs;
        if (diffMs < 0) diffMs = 0;
        var sec = Math.floor(diffMs / 1000);
        var min = Math.floor(sec / 60);
        var hr = Math.floor(min / 60);
        var day = Math.floor(hr / 24);
        var week = Math.floor(day / 7);

        if (week >= 1) return week === 1 ? '1 week ago' : (week + ' weeks ago');
        if (day >= 1) return day === 1 ? '1 day ago' : (day + ' days ago');
        if (hr >= 1) return hr === 1 ? '1 hour ago' : (hr + ' hours ago');
        if (min >= 1) return min === 1 ? '1 minute ago' : (min + ' minutes ago');
        return sec <= 1 ? '1 second ago' : (sec + ' seconds ago');
    }

    function updateFooter(timestampMs) {
        if (!timestampMs) return;
        lastSpokeTimestamp = timestampMs;
        var formatted = formatTimeAgo(timestampMs);
        var text = 'Ed last spoke: ' + formatted;

        document.querySelectorAll('.ed-last-spoke-text').forEach(function (el) {
            el.textContent = text;
        });
        document.querySelectorAll('.ed-says-footer').forEach(function (el) {
            el.style.display = 'block';
        });
    }

    function renderMarkdown(md) {
        if (!md) return '';

        // Escape raw HTML entities first for safety
        var escaped = md
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;');

        // Inline formatting
        escaped = escaped.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
        escaped = escaped.replace(/\*(.+?)\*/g, '<em>$1</em>');
        escaped = escaped.replace(/`([^`]+)`/g, '<code style="background: rgba(255,255,255,0.08); padding: 1px 5px; border-radius: 4px; font-size: 0.85em;">$1</code>');

        var lines = escaped.split('\n');
        var html = '';
        var inList = false;

        for (var i = 0; i < lines.length; i++) {
            var line = lines[i].trim();

            if (!line) {
                if (inList) {
                    html += '</ul>';
                    inList = false;
                }
                continue;
            }

            // Headers
            if (line.indexOf('### ') === 0) {
                if (inList) { html += '</ul>'; inList = false; }
                html += '<h4 style="margin: 0.75rem 0 0.35rem 0; font-size: 0.98rem; font-weight: 700; color: var(--primary);">' + line.substring(4) + '</h4>';
                continue;
            }
            if (line.indexOf('## ') === 0) {
                if (inList) { html += '</ul>'; inList = false; }
                html += '<h3 style="margin: 0.85rem 0 0.4rem 0; font-size: 1.05rem; font-weight: 800; color: var(--primary);">' + line.substring(3) + '</h3>';
                continue;
            }

            // List items (- or * or • or 1.)
            var listMatch = line.match(/^([*\-•]|\d+\.)\s+(.+)$/);
            if (listMatch) {
                if (!inList) {
                    html += '<ul style="margin: 0.4rem 0 0.6rem 1.25rem; padding: 0; line-height: 1.55;">';
                    inList = true;
                }
                html += '<li style="margin-bottom: 0.35rem;">' + listMatch[2] + '</li>';
                continue;
            }

            if (inList) {
                html += '</ul>';
                inList = false;
            }

            html += '<p style="margin: 0 0 0.5rem 0; line-height: 1.55;">' + line + '</p>';
        }

        if (inList) {
            html += '</ul>';
        }

        return html;
    }

    function updateContent(html) {
        var targets = document.querySelectorAll('.ed-says-content');
        targets.forEach(function (el) {
            el.innerHTML = html;
        });

        // Wire dynamic retry buttons
        document.querySelectorAll('.ed-retry-btn').forEach(function (btn) {
            btn.addEventListener('click', function (e) {
                e.preventDefault();
                loadEdSays(true);
            });
        });
    }

    function setLoading(isLoading) {
        document.querySelectorAll('.ed-refresh-btn').forEach(function (btn) {
            btn.disabled = isLoading;
        });
        document.querySelectorAll('.ed-refresh-icon').forEach(function (icon) {
            if (isLoading) {
                icon.classList.add('fa-spin');
            } else {
                icon.classList.remove('fa-spin');
            }
        });
    }

    function loadEdSays(force) {
        setLoading(true);

        updateContent(
            '<div style="display: flex; align-items: center; gap: 10px; color: var(--text-muted); padding: 0.6rem 0;">' +
            '  <i class="fas fa-spinner fa-spin" style="color: var(--primary); font-size: 1.1rem;"></i>' +
            '  <span>Ed is analyzing your net worth and retirement horizon...</span>' +
            '</div>'
        );

        var url = '/api/ed-says-networth' + (force ? '?force=true' : '');

        fetch(url, {
            headers: { 'Accept': 'application/json' }
        })
            .then(function (res) {
                if (!res.ok) {
                    return res.json().then(function (err) {
                        throw new Error(err.edSays || ('HTTP ' + res.status));
                    }).catch(function () {
                        throw new Error('HTTP ' + res.status);
                    });
                }
                return res.json();
            })
            .then(function (data) {
                if (!data) {
                    updateContent('<span style="color: var(--text-muted);">Ed remained silent.</span>');
                    return;
                }

                if (data.configured === false) {
                    var setupHtml =
                        '<div style="background: rgba(139, 92, 246, 0.08); border: 1px dashed var(--primary); border-radius: 8px; padding: 0.85rem;">' +
                        '  <div style="font-weight: 700; color: var(--text-main); margin-bottom: 0.3rem;">' +
                        '    <i class="fas fa-key" style="color: var(--primary); margin-right: 6px;"></i>Mistral API Key Needed' +
                        '  </div>' +
                        '  <div style="font-size: 0.84rem; color: var(--text-muted); line-height: 1.5;">' +
                        '    To activate Ed\'s advice, add your free Mistral API key from <a href="https://console.mistral.ai/" target="_blank" style="color: var(--primary); text-decoration: underline;">console.mistral.ai</a>.<br/>' +
                        '    You can set it in <code>conf/application.conf</code> under <code>mistral.apiKey = "..."</code> or as the <code>MISTRAL_API_KEY</code> environment variable.' +
                        '  </div>' +
                        '</div>';
                    updateContent(setupHtml);
                    return;
                }

                cachedResponse = data.edSays;
                updateContent(renderMarkdown(data.edSays));

                if (data.lastSpoke) {
                    try {
                        localStorage.setItem('monmon_ed_networth_last_spoke', data.lastSpoke);
                    } catch (e) {}
                    updateFooter(data.lastSpoke);
                } else if (!lastSpokeTimestamp) {
                    try {
                        var savedTime = localStorage.getItem('monmon_ed_networth_last_spoke');
                        if (savedTime) updateFooter(parseInt(savedTime, 10));
                    } catch (e) {}
                }
            })
            .catch(function (err) {
                var errHtml =
                    '<div style="color: #ef4444; font-size: 0.88rem; padding: 0.4rem 0;">' +
                    '  <i class="fas fa-exclamation-triangle" style="margin-right: 6px;"></i>' +
                    '  <span>Could not get Ed\'s advice: ' + (err.message || 'Network error') + '</span> ' +
                    '  <button class="ed-retry-btn w3-button w3-tiny w3-round" style="margin-left: 8px; background: var(--card-bg-subtle); border: 1px solid var(--border-color); color: var(--text-main); cursor: pointer;">Retry</button>' +
                    '</div>';
                updateContent(errHtml);
            })
            .finally(function () {
                setLoading(false);
            });
    }

    function openEdModal() {
        var modal = document.getElementById('ed-modal');
        if (modal) {
            modal.style.display = 'flex';
            if (!cachedResponse) {
                loadEdSays(false);
            }
        }
    }

    function closeEdModal() {
        var modal = document.getElementById('ed-modal');
        if (modal) {
            modal.style.display = 'none';
        }
    }

    document.addEventListener('DOMContentLoaded', function () {
        // Wire refresh buttons
        document.querySelectorAll('.ed-refresh-btn').forEach(function (btn) {
            btn.addEventListener('click', function (e) {
                e.preventDefault();
                loadEdSays(true);
            });
        });

        // Wire bottom left floating action button (FAB)
        var edFab = document.getElementById('btn-ed-fab');
        if (edFab) {
            edFab.addEventListener('click', function (e) {
                e.preventDefault();
                openEdModal();
            });
        }

        // Wire any "Ed says" buttons on the page
        document.querySelectorAll('.open-ed-modal-btn').forEach(function (btn) {
            btn.addEventListener('click', function (e) {
                e.preventDefault();
                openEdModal();
            });
        });

        // Wire close button on modal
        var closeBtn = document.getElementById('ed-close-btn');
        if (closeBtn) {
            closeBtn.addEventListener('click', function (e) {
                e.preventDefault();
                closeEdModal();
            });
        }

        // Click outside modal backdrop to close
        var modal = document.getElementById('ed-modal');
        if (modal) {
            modal.addEventListener('click', function (e) {
                if (e.target === modal) {
                    closeEdModal();
                }
            });
        }

        // Escape key closes modal
        document.addEventListener('keydown', function (e) {
            if (e.key === 'Escape' && modal && modal.style.display === 'flex') {
                closeEdModal();
            }
        });

        // Check localStorage for previous timestamp
        try {
            var savedTime = localStorage.getItem('monmon_ed_networth_last_spoke');
            if (savedTime) {
                updateFooter(parseInt(savedTime, 10));
            }
        } catch (e) {}

        // Periodically refresh relative time readout
        setInterval(function () {
            if (lastSpokeTimestamp) {
                updateFooter(lastSpokeTimestamp);
            }
        }, 5000);
    });
})();
