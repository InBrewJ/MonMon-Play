next up:

0. let's have a general think about the UX of the spog page - there's a lot of data and
   I'd say the most commonly looked at things are: left per day, spent this month and also last month's spent this month, savings slider and plan and the account overview

1. all I really need to see for each account is if the last 'available' was submitted before the last payment was due

2. Ed says on desktop can probably be the same as on mobile - accessible via a floating icon on the bottom left

3. I don't understand how the priority system works on the /savingPlans page, if I have P1 then the
   next item is normally P23 P24 P25 P26. I cannot asign a P7. Explain why and document or fix if wrong.
   Ideally there would be arrows on screen to shunt up items in the priority list. In general, also document in a README and in a helpful floating tooltip somewhere how priority affects the system of interlocked sliders.

4. on /historicSpent, it looks like the spent figures are quite low. We should probably reconstruct the spending based on all of the monthly pot configurations, not just the last one. This would likely make sense as a single sql query rather than rebuilding the window in Java. What do you think?
