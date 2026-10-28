-0.5. It's still not clear to me how the priority values are calculated in /savingsPlan. Sometimes I can't press the down arrow on a given item. When a new version deploys the items are ordered nicely
like P1, P2, P3 etc but as soon as I change anything they change to P1, P23, P24 and so on. Why is this? Explain the algorithm in a README to yourself first, then fix, then update the README. Show me the README afterwards.

0. Let's add a way to add custom budget headings to a Spending plan. At the moment, we have Food, Fuel, Transport and Fun. We should be able to make N arbitrary sections of a custom budget

1. Custom plans created should be saved for the individual user's account so they can select them again when creating a new monthly pot

2. This is a big one, and needs planning and research. Let's explore the idea of an 'accurate' mode. This will call out to the TrueLayer or something else to fetch the recent transactions for all accounts. I have 32 accounts on my personal live env, let's see how it handles that many accounts. Generally, it will only be able to handle bank accounts and credit cards I guess - but even so, better than nothing. In late 2026, what is the most developer (me) friendly bank transactions based API? And then pensions and savings accounts will be manual entry? Choose an API like TrueLayer that is developer friendly that doesn't need a 'talk to us' button. If one exists - plaid seems friendly: https://plaid.com/en-gb/pricing/. Build this as a repo interface service, get it all working with a mock provider first, then integrate something like Plaid.

3. As part of 2, we might also accept file uploads of bank statements / transactions and then dangerously rewrite history? Or perhaps we can introduce the idea of 'available based ledger' (ABL) and 'transaction based ledger' (TBL). Where the TBL can come from transactions in file format or a TrueLayer style API.

------------THE ABOVE WAS SENT TO AGY -----------

------------DO NOT DO THIS, the below text, NOW, do it later------------

4. (take a psql backup of the inbrewj account on live) With this as a golden and quite messy
   dataset, let's dig into the /historicSpent tab - it's no obvious to me how the auto injected payday fund influences this. It's also not clear to me why the starting balance is nearly always £32k - is the loan being seen as available cash? It's also no clear to me where the second number in £5195.15 / £1300.0 e.g. in 28 Aug 2026 - 30 Sep 2026 comes from
