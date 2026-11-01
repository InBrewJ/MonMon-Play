## next up

3. There's something off with the legmon/test_fixtures/TXN_22102026_29072026_29102026_NatWest.pdf example in the system tests, I think.
   There are paid in and paid out values in there but I think only the paid out values are getting into monmon. As a final thing for
   our usage limit, let's:

also investigate the output from xberg for that fixture - does it spit out text in such a way that might confuse a mistral model?
might a larger mistral model have an easier time?
is there a way we can configure xberg / tesseract so it captures information better from transactions
lists / statements?
also try the mistal document model api to see if we can't get structured data out of it: https://docs.mistral.ai/studio/document-processing/overview

then, have a look at the statement and see how much money comes in and out, save them as separate figures

A. run the existing system test with this PDF
B. alongside a version of the pipeline that just sends the PDF to a mistral document API:
legmon/test_fixtures/TXN_22102026_29072026_29102026_NatWest.pdf, the sam API key should work

whichever version of A or B comes out being more accurate (to the penny), we'll build around that one.

This transaction / statement ingest is the only real equivalent to using something like LunchFlow - we're here to make Legmon stand true!

------------DO NOT DO THIS, the below text, NOW, do it later------------

4. The /historicSpent still fails to automatically add something that looks like in 'income' as set by the user. I think this is artificially squashing the values

5. (take a psql backup of the inbrewj account on live) With this as a golden and quite messy
   dataset, let's dig into the /historicSpent tab - it's no obvious to me how the auto injected payday fund influences this - maybe this is always a manual thing like 'did payday happen'? If the live incoming for that time period (which may be soft deleted) isn't seen in the list of availables. It's also not clear to me why the starting balance is nearly always £32k - is the loan being seen as available cash? It's also no clear to me where the second number in £5195.15 / £1300.0 e.g. in 28 Aug 2026 - 30 Sep 2026 comes from
