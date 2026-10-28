// Plain JavaScript for Net Worth 'Ed Says' asynchronous loading and refreshing

document.addEventListener('DOMContentLoaded', function () {
    var edContainer = document.getElementById('ed-networth-content');
    var refreshBtn = document.getElementById('ed-networth-refresh-btn');
    var statusText = document.getElementById('ed-networth-status');

    if (!edContainer) {
        return;
    }

    function renderMarkdown(rawText) {
        if (!rawText) return '';
        // Escape basic HTML
        var safe = rawText
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;');

        // Headers
        safe = safe.replace(/^### (.*$)/gim, '<h5 style="margin: 0.75rem 0 0.35rem 0; font-weight: 700; color: var(--text-main);">$1</h5>');
        safe = safe.replace(/^## (.*$)/gim, '<h4 style="margin: 0.9rem 0 0.4rem 0; font-weight: 700; color: var(--primary);">$1</h4>');
        safe = safe.replace(/^# (.*$)/gim, '<h3 style="margin: 1rem 0 0.5rem 0; font-weight: 800; color: var(--primary);">$1</h3>');

        // Bold & Italic
        safe = safe.replace(/\*\*(.*?)\*\*/gim, '<b>$1</b>');
        safe = safe.replace(/\*(.*?)\*/gim, '<i>$1</i>');

        // Bullet points
        safe = safe.replace(/^\s*[-•*]\s+(.*$)/gim, '<li style="margin-bottom: 0.3rem;">$1</li>');
        safe = safe.replace(/(<li.*<\/li>)/gims, '<ul style="padding-left: 1.25rem; margin: 0.5rem 0;">$1</ul>');

        // Paragraphs
        var paras = safe.split(/\n\n+/);
        var formatted = paras.map(function (p) {
            var trimmed = p.trim();
            if (trimmed.startsWith('<h') || trimmed.startsWith('<ul') || trimmed.startsWith('<li')) {
                return trimmed;
            }
            return '<p style="margin: 0.5rem 0; line-height: 1.55;">' + trimmed + '</p>';
        }).join('');

        return formatted;
    }

    function loadEdAdvice(force) {
        if (statusText) statusText.textContent = force ? 'Consulting Ed...' : 'Reading portfolio...';
        if (refreshBtn) refreshBtn.disabled = true;

        var url = '/api/ed-says-networth' + (force ? '?force=true' : '');

        fetch(url, {
            headers: { 'Accept': 'application/json' }
        })
            .then(function (res) {
                if (!res.ok) throw new Error('HTTP ' + res.status);
                return res.json();
            })
            .then(function (data) {
                var advice = data.edSays || 'Ed looked at the portfolio but had no words today.';
                edContainer.innerHTML = renderMarkdown(advice);
                if (statusText) statusText.textContent = '';
            })
            .catch(function (err) {
                edContainer.innerHTML = '<p style="color: var(--danger);"><i class="fas fa-exclamation-triangle"></i> Ed had trouble reading the stars: ' + err.message + '</p>';
                if (statusText) statusText.textContent = '';
            })
            .finally(function () {
                if (refreshBtn) refreshBtn.disabled = false;
            });
    }

    if (refreshBtn) {
        refreshBtn.addEventListener('click', function () {
            loadEdAdvice(true);
        });
    }

    // Initial load
    loadEdAdvice(false);
});
