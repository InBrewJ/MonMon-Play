next up:

0. the default view on /myOutgoings should be 'Monthly and due now'

1. There should also be a 'test' button on /dig - this uses a small dataset that only lives 
in the frontend to show a large amount of spending in London UK, a small amount of spending in Edinburgh UK and a very large spend in Vatican City

------- general experimental UX shakeup -------

0. a few UX patterns have emerged in development that should likely be used as primitives. Save a clearer version of the following in AGENTS.md:

On mobile, a good way to reduce clutter is via the swipe-able cards and card headings with a dot. Use this for all data heavy pages

On mobile for mobile tables, the implementation on /myAccounts is the cleanest. Long text on one line followed by action buttons below, we should make this a named pattern and use it on all tables

For mobile and desktop and for the swipe-able cards and desktop cards, we should make a decision up front on what the user needs to see 'at a glance', this should inform the order of the page

App wide search should search all primitives (within reason and with proper indexes) and clicked results should route back to somewhere on the app where it lives, never in isolation
