next up:

0. finish off dark mode - some cards here and there are very light in dark mode

1. only showing last two month's balances
   on /myBalances in a filterable graph

2. On /spog, let's arrange the accounts in a grid on desktop and a list on mobile, with headings grouping the account types, within the account type groups, sort in alphabetical order

3. longer trends for a year overlaid with events from calendar if query doesn't take forever
   (how to integrate with google calendar?). Write all the code necessary for the gcal integration but make sure the app works without
   it. The idea is to see where the money dipped after what events.

Perhaps before 0 - 3 though, I'm not sure if the 'Spent This
Month' thing works properly - perhaps it should be based just on the monthly pot. This month, for example, I've had to spend
hundreds of pounds on a new phone and such but admitedly I forgot to change the balance when I got paid, so 'spent this month' is
only 29.01 - which looks inaccurate. In absence of live account balance APIs, perhaps we should stick to the facts?

-> but even
before THAT, we need to have a settings page with one setting, region, with one option, UK. This governs bank holidays. We need to
store the uk bank holidays in some static file on the backend, JSON will do, and these bank holidays should influence the last
working day (so if payday is set to 31st aug, the last working day would be Friday 28th in 2026). This also encodes the timezone - so that all date line calculations still make sense around the window of midnight.

Essentially, we need all of those
things in reverse order.

Finally, after everything, for CREDIT accounts on /spog, we should have 'Balance: £650/£1500', i.e. Balance: £(available)/£(credit limit). Linked to this, on the /myBalances page, the form prompt should read 'What's the available?'. We're really focussed on 'available' funds in MonMon

Then, and only then, let's make the nav menu collapsible on mobile. The currently selected route is always visible, a right chevron icon is tappable and opens the rest of the options
