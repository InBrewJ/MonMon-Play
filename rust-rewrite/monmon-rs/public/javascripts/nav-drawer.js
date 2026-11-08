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
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initNavDrawer);
    } else {
        initNavDrawer();
    }
})();
