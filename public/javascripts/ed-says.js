/**
 * Ed Says - Asynchronous AI financial coach powered by Mistral AI
 * Desktop panel & Mobile popup modal
 */
(function () {
    'use strict';

    var cachedResponse = null;

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

    function updateAllContent(html) {
        var targets = document.querySelectorAll('.ed-says-content');
        targets.forEach(function (el) {
            el.innerHTML = html;
        });

        // Wire up any dynamic retry buttons
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

        updateAllContent(
            '<div style="display: flex; align-items: center; gap: 10px; color: var(--text-muted); padding: 0.6rem 0;">' +
            '  <i class="fas fa-spinner fa-spin" style="color: var(--primary); font-size: 1.1rem;"></i>' +
            '  <span>Ed is analyzing your finances...</span>' +
            '</div>'
        );

        var url = '/api/ed-says' + (force ? '?force=true' : '');

        fetch(url, {
            headers: {
                'Accept': 'application/json'
            }
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
                    updateAllContent('<span style="color: var(--text-muted);">Ed remained silent.</span>');
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
                    updateAllContent(setupHtml);
                    return;
                }

                cachedResponse = data.edSays;
                updateAllContent(renderMarkdown(data.edSays));
            })
            .catch(function (err) {
                var errHtml =
                    '<div style="color: #ef4444; font-size: 0.88rem; padding: 0.4rem 0;">' +
                    '  <i class="fas fa-exclamation-triangle" style="margin-right: 6px;"></i>' +
                    '  <span>Could not get Ed\'s advice: ' + (err.message || 'Network error') + '</span> ' +
                    '  <button class="ed-retry-btn w3-button w3-tiny w3-round" style="margin-left: 8px; background: var(--card-bg-subtle); border: 1px solid var(--border-color); color: var(--text-main); cursor: pointer;">Retry</button>' +
                    '</div>';
                updateAllContent(errHtml);
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

        // Wire mobile FAB icon
        var edFab = document.getElementById('btn-ed-fab');
        if (edFab) {
            edFab.addEventListener('click', function (e) {
                e.preventDefault();
                openEdModal();
            });
        }

        // Wire close button on modal
        var closeBtn = document.getElementById('ed-close-btn');
        if (closeBtn) {
            closeBtn.addEventListener('click', function (e) {
                e.preventDefault();
                closeEdModal();
            });
        }

        // Click outside modal content to close
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

        // Initial async load
        loadEdSays(false);
    });
})();
