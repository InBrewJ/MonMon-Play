DO NOT COMMIT ANYTHING A MISTRAL SECRET IS BAKED INTO CODE


0. Are there any secrets exposed in this repo? If there are, let's work towards rotating them
   and getting everything set from gitignored files and one single, root level .env file

1. For NomMon, let's add a lock button to the meal plan generated. The cache only lives as long as the server, which is fairly short time just now. Add a lock button to the /nommon page
   and save it to the database to act as a non-volatile cache

------------THE ABOVE WAS SENT TO AGY -----------

------------DO NOT DO THIS, the below text, NOW, do it later------------

4. (take a psql backup of the inbrewj account on live) With this as a golden and quite messy
   dataset, let's dig into the /historicSpent tab - it's no obvious to me how the auto injected payday fund influences this. It's also not clear to me why the starting balance is nearly always £32k - is the loan being seen as available cash? It's also no clear to me where the second number in £5195.15 / £1300.0 e.g. in 28 Aug 2026 - 30 Sep 2026 comes from
