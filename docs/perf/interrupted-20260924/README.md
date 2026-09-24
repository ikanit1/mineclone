# Interrupted M0 benchmark, 2026-09-24

Stopped at the user's request. **17 of 33 planned runs completed; this is not an
accepted performance baseline.** `baseline_eligible` remains false. Each completed
run used 10 seconds of warmup and 60 seconds of measurement. The preserved summary
reports INCOMPLETE scenes and an overall FAIL; its preliminary medians must not be
used as final benchmark claims. Flat-idle already exceeded the 5% repeatability
limit across its two completed repeats.

Measured source: `0b7a12a3e132df990d99d90fd83ef5a90f4a1081`, from a clean, frozen M0
snapshot. These results are not measurements of the unchanged historical release.
The unfinished M1 work was authored separately and was not measured.

The original manifest, summary, completed raw reports, launch arguments, application
logs and GC logs are preserved byte-for-byte. `publication.json` maps the original
paths to published paths and records SHA-256 and sizes. Logs have a `.txt` suffix
here; references ending in `.log` in the unchanged summary/commands resolve through
that map. JSON and log bytes are excluded from Git newline conversion to retain
hash validity.

The larger private frozen source/classes/assets, screenshots, disposable runtime
worlds and interrupted run outputs remain locally under
`out-test/bench/m0-baseline-20260924`; they are not included in Git. No process remains
running for this suite, and no automatic restart is scheduled. Resume with a new
controlled full run and retain these interrupted results as provenance.
