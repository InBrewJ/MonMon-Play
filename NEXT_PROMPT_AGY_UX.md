next up:

0. next steps for rust parity:

- in the main nav, the 'menu' opens the menu but the button to the right also says menu - it should say the name of the page we're on and when clicked, it should navigate to that page (acts as a refresh)
- existing users should have the option to add a passkey to their auth options
- new users should have the option to add a passkey
- going to an authenticated route, e.g. /spog when unauthenticated should route to our styled login page, not the RAuthy one
- general spog parity, especially spending plan and spending pace
- /historicSpent page doesn't seem to exist
- parity of /spendingPace page, with histograms and what not, see the java version
0. Why is there a short flash when a page loaded where the button next to the > Meny says 'Menu'? Surely the name of the page should be injected in to the HTML template, not fetched via JS or whatever is happening

0. The 'add passkey' button seems to create a passkey, somewhere. I should be able to add the passkey to my local password manager e.g. BitWarden

1. Let's get the NomMon meal planner up and running with Rust. Just for the meal planner, try the next mistral model up - the small model makes odd mistakes with recipes. Also, all meals in the plan need a recipe, not just the batch cook ones.

2. on the monthly pot page, it says the balances of each account, also add a sum total of the monthly pot

2.5. on the java version, which we need to match, the /myPlans pages goes to a page with all plans, savings, spending and meal plans as well as bill and rent sharing agreements. If we don't have a spending plan notion in the rust version, we need it. The plans must be at parity with the java version otherwise we'll create orphaned data in the database

3. on spog, there is no mini spending plan, reach parity with the java version on this

4. on spog, the mini spending plan should also have a note about the _actual_ sum left in the monthly pot

---- above was sent

0. If I do this: /myBalances?accountId=19, is there any authz checking on that id? Or can I plausibly update the balance in any account on the platform? Is there any authz checking at all on the rust platform at large?

1. In the java version, we have monthly outgoings but also yearly and n-yearly outgoings. Do this one TDD style, get the behaviour from the java code, write some failing rust tests, then reach feature parity
