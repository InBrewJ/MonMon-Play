0. Add a page called /dig, on the top nav bar, that exposes a heatmap of transactions from the
   Monzo accounts. The aim is to be able to see WHERE you've spent your money. The larger the sum, the redder the heat. Green is good on the heatmap. Use free mapping libraries / leaflet / openstreetmap, whatever. If there's no data, say so. There might also be a
   'too many data' limit, but I doubt it just now. underneath the map is a histogram of the types of transactions from Monzo - this is roughly HOW you spent your money.

------------THE ABOVE WAS SENT TO AGY -----------

1. Now we have a Monzo webhook API, let's see if we can't craft a PDF statement -> balances +
   transactions -> MonMon. This is to fill the public API gap for ABL accounts. Ideally there would be a:

- a space on MonMon, in my account, to upload PDF files
- this kicks off a pipeline of, say, pdf to text -> Mistral call to extract
  bank, transactions and balances -> sends Monzo API compatible data to our webhook ->
  user is asked to match the statement to the account, extracted bank name is shown
  -> dangerously overwrites ABL balances for that period

This pipeline should be fault tolerant, so we'll run it as a separate docker service on our live
environment. We might try xberg with the tesseract ocr plugin for PDF to text before we send the
text to Mistral - at least this way OCR is fixed cost. There may be failures, there may be
retries to Mistral and so on, all logs and errors (rotated) should be flagged to the user
and stored in log files in a similar manner to run_prod.sh. The service should be called LegMon

PDF files should be deleted after they've been processed.

------------DO NOT DO THIS, the below text, NOW, do it later------------

4. (take a psql backup of the inbrewj account on live) With this as a golden and quite messy
   dataset, let's dig into the /historicSpent tab - it's no obvious to me how the auto injected payday fund influences this. It's also not clear to me why the starting balance is nearly always £32k - is the loan being seen as available cash? It's also no clear to me where the second number in £5195.15 / £1300.0 e.g. in 28 Aug 2026 - 30 Sep 2026 comes from
