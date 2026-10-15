# ZepDriver

Android app for Burning Man art car drivers. The product spec is in `SPEC.md`.

## Annual rules review (required)

A few weeks before every burn, review the current DMV Mutant Vehicle rules and
sound policy on burningman.org and update the coded ruleset (see SPEC.md §13).
Users never configure rules themselves, so the ruleset in the app must match
the current year. The same review covers the location embargo: code the
year's release dates in `core/embargo/DataRelease.kt`, add the year to
`tools/data/build_playa_data.py`, and set the year in CI (SPEC.md §6.1a, §13).

Never commit, print or log embargoed camp or art data or the
`BURNING_MAN_API_KEY` secret: CI logs and this repo are public.
