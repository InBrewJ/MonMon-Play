0. 

------------THE ABOVE WAS SENT TO AGY -----------

1. Let's add an expandable search bar to the header. make a huge postgres view (maybe materialized?) of everything in the user's account. I should be able to search for anything in the app, an account, an outgoing, a balance, a meal plan, a pot, a saving plan and so on. The list of results is simple and
   when I click on a result it takes me to the relevant page and scrolls down to the result on the page in the given context and highlights the thing, briefly

------------DO NOT DO THIS, the below text, NOW, do it later------------

3. The /historicSpent still fails to automatically add something that looks like in 'income' as set by the user. I think this is artificially squashing the values

4. (take a psql backup of the inbrewj account on live) With this as a golden and quite messy
   dataset, let's dig into the /historicSpent tab - it's no obvious to me how the auto injected payday fund influences this. It's also not clear to me why the starting balance is nearly always £32k - is the loan being seen as available cash? It's also no clear to me where the second number in £5195.15 / £1300.0 e.g. in 28 Aug 2026 - 30 Sep 2026 comes from
