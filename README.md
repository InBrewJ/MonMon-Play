## MonMon

- This became my testbed to stress test DHH's claims and manifesto: 

"pencils down, rewrite everything in Rust, get performance gains, don't read the code"

Spoilers: I did end up reading some of the code. Literally how can you not. I also modified about four lines, some config and some small bit in the Rust that deals with logging out. But that was it.

## HOW IT WENT

In short, Gemini 3.8 Flash started with a [vague plan](modules/README.md), then:

- wrote migration / playwright tests for most GETs and some POSTs to compare the Java and Rust versions
- chose to use its own hand rolled auth (monmon-auth) which I then steered back towards RAuthy
- hardcoded a demo user into the rust system
- Did not initially get the notion of a shared header or nav system at all
- The biggest glaring problem was auth: even though I'd heavily hinted to use RAuthy,
Gem went ahead with hardcoding usernames and passwords everywhere, messing up passkeys, and in the finish deployed and ran Rauthy but left it unattached to the app
- and for RAuthy, gem has no qualms about commited secrets into the repo. This repo is public and so generally, this is bad

The 'Ship of Theseus' is, in my view, the only real way for rewrites to work. We didn't have a stunning amount of tests to begin with so only some portions of the ship received the Theseus treatment. 

Generally, it got about 30% of the way there, then I went through and picked the UI apart
and asked Gem to fix them all piecemeal.

And the point? An elaborate way to try and speed up MonMon on my RPi5 (greenstreet)

## VERDICT

Driven mainly by `agy --dangerously-skip-permissions`, the whole rewrite took two and a half days.

Genuinely impressive in 2026-11
