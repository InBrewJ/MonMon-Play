## next up

0. Something about the NS&I statement and why it won't parse - it would be nice if NS&I and pension statements would parse and be added as available balances

------------DO NOT DO THIS, the below text, NOW, do it later------------

4. I've added bank statements that seem to be parsed correctly to record the income - but for a given month it still doesn't show up in spending. What is the exact window for each? Do I need to re-upload the statements to recapture the correct timestamp for the incoming?

5. (take a psql backup of the inbrewj account on live) With this as a golden and quite messy
   dataset, let's dig into the /historicSpent tab - it's no obvious to me how the auto injected payday fund influences this - maybe this is always a manual thing like 'did payday happen'? If the live incoming for that time period (which may be soft deleted) isn't seen in the list of availables. It's also not clear to me why the starting balance is nearly always £32k - is the loan being seen as available cash? It's also no clear to me where the second number in £5195.15 / £1300.0 e.g. in 28 Aug 2026 - 30 Sep 2026 comes from
