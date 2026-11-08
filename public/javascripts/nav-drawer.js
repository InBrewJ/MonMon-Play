/**
 * MonMon Left-Hand Slide-out Navigation Drawer
 */
(function() {
    function initNavDrawer() {
        var navToggle = document.getElementById('nav-toggle');
        var navDrawer = document.getElementById('nav-drawer');
        var navBackdrop = document.getElementById('nav-backdrop');
        var navClose = document.getElementById('nav-drawer-close');

        if (!navDrawer) return;

        function openDrawer() {
            navDrawer.classList.add('open');
            if (navBackdrop) navBackdrop.classList.add('open');
            document.body.classList.add('nav-drawer-locked');
            if (navToggle) {
                navToggle.classList.add('nav-open');
                navToggle.setAttribute('aria-expanded', 'true');
            }
        }

        function closeDrawer() {
            navDrawer.classList.remove('open');
            if (navBackdrop) navBackdrop.classList.remove('open');
            document.body.classList.remove('nav-drawer-locked');
            if (navToggle) {
                navToggle.classList.remove('nav-open');
                navToggle.setAttribute('aria-expanded', 'false');
            }
        }

        function toggleDrawer(e) {
            if (e) {
                e.preventDefault();
                e.stopPropagation();
            }
            if (navDrawer.classList.contains('open')) {
                closeDrawer();
            } else {
                openDrawer();
            }
        }

        if (navToggle) {
            navToggle.addEventListener('click', toggleDrawer);
        }

        if (navClose) {
            navClose.addEventListener('click', function(e) {
                e.preventDefault();
                closeDrawer();
            });
        }

        if (navBackdrop) {
            navBackdrop.addEventListener('click', function(e) {
                e.preventDefault();
                closeDrawer();
            });
        }

        // Close on ESC key
        document.addEventListener('keydown', function(e) {
            if (e.key === 'Escape' && navDrawer.classList.contains('open')) {
                closeDrawer();
            }
        });

        // "until a menu item is clicked" - clicking any navigation link closes the drawer
        var drawerLinks = navDrawer.querySelectorAll('a');
        for (var i = 0; i < drawerLinks.length; i++) {
            drawerLinks[i].addEventListener('click', function() {
                closeDrawer();
            });
        }

        // Current page crumb pill parity & refresh handler
        var currentCrumb = document.querySelector('.current-crumb-link');
        if (currentCrumb) {
            var PAGE_MAP = [
                { path: '/spog', name: 'SPOG', icon: 'fas fa-chart-pie' },
                { path: '/myOutgoings', name: 'Outgoings', icon: 'fas fa-credit-card' },
                { path: '/myAccounts', name: 'Accounts', icon: 'fas fa-university' },
                { path: '/myIncomings', name: 'Incomings', icon: 'fas fa-hand-holding-usd' },
                { path: '/myBalances', name: 'Balances', icon: 'fas fa-money-check-alt' },
                { path: '/savingsPlan', name: 'Savings Plan', icon: 'fas fa-sliders-h' },
                { path: '/spendingPace', name: 'Spending Pace', icon: 'fas fa-tachometer-alt' },
                { path: '/historicSpent', name: 'Historic Spent', icon: 'fas fa-history' },
                { path: '/netWorth', name: 'Net Worth', icon: 'fas fa-landmark' },
                { path: '/nommon', name: 'NomMon', icon: 'fas fa-utensils' },
                { path: '/bank', name: 'Bank Sync', icon: 'fas fa-exchange-alt' },
                { path: '/monzo-dig', name: 'Monzo Dig', icon: 'fas fa-map-marked-alt' },
                { path: '/settings', name: 'Settings', icon: 'fas fa-cog' },
                { path: '/legmon', name: 'LegMon Statements', icon: 'fas fa-file-invoice' }
            ];

            var pathname = window.location.pathname.toLowerCase();
            var matched = null;
            for (var m = 0; m < PAGE_MAP.length; m++) {
                if (pathname === PAGE_MAP[m].path.toLowerCase() || 
                    (PAGE_MAP[m].path !== '/spog' && pathname.startsWith(PAGE_MAP[m].path.toLowerCase()))) {
                    matched = PAGE_MAP[m];
                    break;
                }
            }
            if (!matched && (pathname === '/' || pathname === '')) {
                matched = PAGE_MAP[0]; // default to SPOG
            }
            if (matched) {
                currentCrumb.setAttribute('href', window.location.pathname);
                currentCrumb.innerHTML = '<i class="' + matched.icon + '"></i> ' + matched.name;
                currentCrumb.setAttribute('title', 'Reload ' + matched.name);
                currentCrumb.addEventListener('click', function(e) {
                    if (window.location.pathname.toLowerCase() === matched.path.toLowerCase()) {
                        e.preventDefault();
                        window.location.reload();
                    }
                });
            }
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initNavDrawer);
    } else {
        initNavDrawer();
    }
})();
