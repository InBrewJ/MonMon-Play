2. On the outgoings screen, we need an extra tab on the outgoings or a form switch for monthly or yearly bills. We have monthly at the moment. Yearly bills should take a day and a month and, when they roll around, should appear in the general outgoings list

3. I don't understand how the priority system works on the /savingPlans page, if I have P1 then the
   next item is normally P23 P24 P25 P26. I cannot asign a P7. Explain why and document or fix if wrong.
   Ideally there would be arrows on screen to shunt up items in the priority list. In general, also document in a README and in a helpful floating tooltip somewhere how priority affects the system of interlocked sliders.

4. on /historicSpent, it looks like the spent figures are quite low. We should probably reconstruct the spending based on all of the monthly pot configurations, not just the last one. This would likely make sense as a single sql query rather than rebuilding the window in Java. What do you think?

5. the sainsburys store picker has a URL like this: https://stores.sainsburys.co.uk/list/place/@55.95965,-3.45707/1/all -> so we need to get the lat lon from the postcode first, or perhaps open street map would be more reliable? (or the sensible option, just use the google maps link)

---

1. on /savingsPlan, it would also be great to add an 'Add all credit cards' button, where all credit cards with a balance are added to the priority list, with a priority lower than anything already in the list
