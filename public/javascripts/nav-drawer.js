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
            document.documentElement.classList.add('modal-scroll-locked');
            document.body.classList.add('modal-scroll-locked');
            document.body.classList.add('nav-drawer-locked');
            if (window.lockModalScroll) window.lockModalScroll();
            if (navToggle) {
                navToggle.classList.add('nav-open');
                navToggle.setAttribute('aria-expanded', 'true');
            }
        }

        function closeDrawer() {
            navDrawer.classList.remove('open');
            if (navBackdrop) navBackdrop.classList.remove('open');
            document.documentElement.classList.remove('modal-scroll-locked');
            document.body.classList.remove('modal-scroll-locked');
            document.body.classList.remove('nav-drawer-locked');
            if (window.unlockModalScroll) window.unlockModalScroll();
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

        // Nested Accordion Groups toggle
        var groupToggles = navDrawer.querySelectorAll('.drawer-group-toggle');
        for (var g = 0; g < groupToggles.length; g++) {
            groupToggles[g].addEventListener('click', function(e) {
                e.preventDefault();
                e.stopPropagation();
                var group = this.closest('.drawer-group');
                if (!group) return;
                var isOpen = group.classList.contains('open');
                if (isOpen) {
                    group.classList.remove('open');
                    this.setAttribute('aria-expanded', 'false');
                } else {
                    group.classList.add('open');
                    this.setAttribute('aria-expanded', 'true');
                }
            });
        }

        // Auto-expand group if current page matches a child link & highlight active item
        var currentPathname = window.location.pathname.toLowerCase();
        var drawerLinks = navDrawer.querySelectorAll('.drawer-crumb a');
        for (var l = 0; l < drawerLinks.length; l++) {
            var link = drawerLinks[l];
            var href = link.getAttribute('href');
            if (href) {
                var cleanHref = href.toLowerCase().split('?')[0].split('#')[0];
                var isMatch = (currentPathname === cleanHref) ||
                    (cleanHref !== '/spog' && cleanHref !== '/' && currentPathname.indexOf(cleanHref) === 0);
                if (isMatch) {
                    var crumb = link.closest('.drawer-crumb');
                    if (crumb) crumb.classList.add('active');
                    var parentGroup = link.closest('.drawer-group');
                    if (parentGroup) {
                        parentGroup.classList.add('open', 'has-active');
                        var btn = parentGroup.querySelector('.drawer-group-toggle');
                        if (btn) btn.setAttribute('aria-expanded', 'true');
                    }
                }
            }
        }

        if (currentPathname === '/myplans' || currentPathname === '/plans') {
            var plansGroup = document.getElementById('nav-group-plans');
            if (plansGroup) {
                plansGroup.classList.add('open', 'has-active');
                var btn = plansGroup.querySelector('.drawer-group-toggle');
                if (btn) btn.setAttribute('aria-expanded', 'true');
            }
        }

        // "until a menu item is clicked" - clicking any navigation link closes the drawer
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
                { path: '/nommon', name: 'NomMon', icon: 'fas fa-utensils' },
                { path: '/savingsPlan', name: 'Savings Plan', icon: 'fas fa-piggy-bank' },
                { path: '/myMonthlyPot', name: 'Spending Plan', icon: 'fas fa-archive' },
                { path: '/spendingPlan', name: 'Spending Plan', icon: 'fas fa-archive' },
                { path: '/myPlans', name: 'Plans', icon: 'fas fa-sliders-h' },
                { path: '/netWorth', name: 'Net Worth', icon: 'fas fa-landmark' },
                { path: '/transfers', name: 'Transfer Window', icon: 'fas fa-exchange-alt' },
                { path: '/myOutgoings', name: 'Outgoings', icon: 'fas fa-credit-card' },
                { path: '/myAccounts', name: 'Accounts', icon: 'fas fa-university' },
                { path: '/myIncomings', name: 'Incomings', icon: 'fas fa-money-bill-wave' },
                { path: '/myBalances', name: 'Balances', icon: 'fas fa-coins' },
                { path: '/spendingPace', name: 'Spending Pace', icon: 'fas fa-tachometer-alt' },
                { path: '/historicSpent', name: 'Historic Spent', icon: 'fas fa-history' },
                { path: '/bank', name: 'Bank Sync', icon: 'fas fa-sync-alt' },
                { path: '/monzo-dig', name: 'Monzo Dig', icon: 'fas fa-map-marked-alt' },
                { path: '/dig', name: 'Monzo Dig', icon: 'fas fa-map-marked-alt' },
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
