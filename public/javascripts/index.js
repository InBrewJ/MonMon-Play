/**
 * MonMon Core UI Scripts
 * Handles live ticker, delete confirmation, allowance slider, form autofocus, and global theme toggling.
 */

// 1. Live Ticker
$(function() {
    function formatTicker() {
        var ticker = document.getElementById('ticker');
        if (!ticker) return;

        var now = new Date();
        var day = now.getDate();
        var suffix = "th";
        if (day === 1 || day === 21 || day === 31) {
            suffix = "st";
        } else if (day === 2 || day === 22) {
            suffix = "nd";
        } else if (day === 3 || day === 23) {
            suffix = "rd";
        }

        var dateStr = now.toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' });
        var timeStr = now.toLocaleTimeString('en-GB');
        ticker.innerHTML = "<span style='color: #60a5fa; font-weight: 700; margin-right: 8px;'><i class='far fa-calendar-alt'></i> Today: Day " + day + " (" + day + suffix + ")</span> · <span style='color: #cbd5e1;'>" + dateStr + " " + timeStr + "</span>";
    }

    if ($('#ticker').length && !window.__monmonTickerInterval) {
        formatTicker();
        window.__monmonTickerInterval = setInterval(formatTicker, 1000);
    }

    if (window.location.hash === "#edit-form" || $("#edit-form.editing-mode").length) {
        var editTarget = document.getElementById("edit-form");
        if (editTarget) {
            editTarget.scrollIntoView({ behavior: 'smooth', block: 'start' });
            setTimeout(function() {
                $("#edit-form input:visible:first").focus();
            }, 200);
        }
    }
});

// 2. Allowances dynamic slider
$(function() {
    function updateAllowances(percent) {
        var box = $("#allowances-box");
        if (box.length) {
            var incoming = parseFloat(box.data("incoming")) || 0;
            var outgoing = parseFloat(box.data("outgoing")) || 0;
            var days = parseInt(box.data("days")) || 30;
            if (days <= 0) days = 30;

            var pct = parseFloat(percent) || 0;
            var savingsCost = (pct / 100.0) * incoming;
            var newSurplus = (incoming - outgoing) - savingsCost;
            if (newSurplus < 0) newSurplus = 0;
            var newMaxDay = newSurplus / days;
            var newMaxWeek = newMaxDay * 7;
            var newYearlySurplus = newSurplus * 12;

            $("#dyn-max-day").text(newMaxDay.toFixed(2));
            $("#dyn-max-week").text(newMaxWeek.toFixed(2));
            $("#dyn-max-month").text(newSurplus.toFixed(2));
            $("#dyn-yearly-surplus").text(newYearlySurplus.toFixed(2));
            $("#savings-amount").text("£" + savingsCost.toFixed(2));
            $("#savings-slider-val").text(pct);
            $("#savings-slider-val-target").attr("href", "/plans/updateSavingsPayoffPercentage?percent=" + pct);
        }
    }

    var slider = $("#savings-slider");
    if (slider.length && slider.val() !== undefined) {
        updateAllowances(slider.val());
        slider.on("input change", function(e) {
            updateAllowances(e.target.value);
        });
    }
});

// 3. Deletion safety confirmation
$(function() {
    $(document).on("click", "a[href*='/bin'], .btn-delete, a[title*='Delete'], a[title*='Delete Pot']", function(e) {
        if (!confirm("Are you sure you want to delete this?")) {
            e.preventDefault();
            return false;
        }
    });
});

// 4. Global Dark / Light Theme Toggle
$(function() {
    function updateThemeToggleUI(theme) {
        if (theme === 'dark') {
            $('#theme-toggle-icon').removeClass('fa-moon').addClass('fa-sun');
            $('#theme-toggle-text').text('Light Mode');
            $('#theme-toggle').attr('title', 'Switch to Light Mode');
        } else {
            $('#theme-toggle-icon').removeClass('fa-sun').addClass('fa-moon');
            $('#theme-toggle-text').text('Dark Mode');
            $('#theme-toggle').attr('title', 'Switch to Dark Mode');
        }
    }

    var currentTheme = $('html').attr('data-theme') || (function() {
        try { return localStorage.getItem('monmon-theme'); } catch(e) { return 'dark'; }
    })() || 'dark';
    updateThemeToggleUI(currentTheme);

    $(document).on('click', '#theme-toggle', function(e) {
        e.preventDefault();
        var activeTheme = $('html').attr('data-theme') || 'dark';
        var nextTheme = activeTheme === 'dark' ? 'light' : 'dark';
        $('html').attr('data-theme', nextTheme);
        try {
            localStorage.setItem('monmon-theme', nextTheme);
        } catch (err) {}
        updateThemeToggleUI(nextTheme);
    });
});
