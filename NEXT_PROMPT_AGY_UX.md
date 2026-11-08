next up:

0. next steps for rust parity:

- in the main nav, the 'menu' opens the menu but the button to the right also says menu - it should say the name of the page we're on and when clicked, it should navigate to that page (acts as a refresh)
- existing users should have the option to add a passkey to their auth options
- new users should have the option to add a passkey
- going to an authenticated route, e.g. /spog when unauthenticated should route to our styled login page, not the RAuthy one
- general spog parity, especially spending plan and spending pace
- /historicSpent page doesn't seem to exist
- parity of /spendingPace page, with histograms and what not, see the java version

---- above was sent, everything after the 'menu' may not have been completed

0. Why is there a short flash when a page loaded where the button next to the > Meny says 'Menu'? Surely the name of the page should be injected in to the HTML template, not fetched via JS or whatever is happening

1. Let's get the NomMon meal planner up and running with Rust. Just for the meal planner, try the next mistral model up - the small model makes odd mistakes with recipes. Also, all meals in the plan need a recipe, not just the batch cook ones.

2. on the monthly pot page, it says the balances of each account, also add a sum total of the monthly pot

3. on spog, the mini spending plan should also have a note about the _actual_ sum left in the monthly pot
