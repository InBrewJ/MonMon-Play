0. note that most bank statements I can get in apps are PDF files, all seem to include transactions and balances,
   but all are PDF. Given that we'd need either an OCR model or a very solid PDF -> text -> balances and transactions pipeline to make
   this happen. So we can get rid of the CSV import for now. I'll add the PDF statement -> transactions + balances pipeline to mirror
   the monzo one you're working on to the NEXT_PROMPT file

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
and stored in log files in a similar manner to run_prod.sh

------------DO NOT DO THIS, the below text, NOW, do it later------------

4. (take a psql backup of the inbrewj account on live) With this as a golden and quite messy
   dataset, let's dig into the /historicSpent tab - it's no obvious to me how the auto injected payday fund influences this. It's also not clear to me why the starting balance is nearly always £32k - is the loan being seen as available cash? It's also no clear to me where the second number in £5195.15 / £1300.0 e.g. in 28 Aug 2026 - 30 Sep 2026 comes from
