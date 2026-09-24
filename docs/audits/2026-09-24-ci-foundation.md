# FND-04 remote CI evidence

The M0 source snapshot is commit `0b7a12a3e132df990d99d90fd83ef5a90f4a1081`.
[PR #4](https://github.com/ikanit1/mineclone/pull/4) passed the Windows/Java 17 job:

- [Push run](https://github.com/ikanit1/mineclone/actions/runs/36013236750): success, job 1 minute 25 seconds.
- [Pull-request run](https://github.com/ikanit1/mineclone/actions/runs/36013283797): success, job 1 minute 37 seconds.

Both run compilation, all 528 headless regressions, runner filter/exit-code checks,
and twelve benchmark launcher/GC-parser checks. Their reports are uploaded even
when a preceding test fails. GitGuardian also passed the production PR.

To verify failure propagation in the actual hosted job, an isolated branch added
one deliberate assertion failure to the same registry. [PR #5](https://github.com/ikanit1/mineclone/pull/5)
was explicitly marked "do not merge" and closed after verification. Its
[CI run](https://github.com/ikanit1/mineclone/actions/runs/36013545674) reported
`528 passed, 1 failed`, identified the intentional FND-04 assertion, and failed
the headless-test step with exit code 1. The production branch never contained
that assertion.

Nightly LAN/save-corpus execution is configured separately; these two green runs
are headless CI evidence, not a claim of hosted OpenGL/nightly execution.

`git add --renormalize .` after the M0 commit produced no staged delta: the index
already used LF. Working-tree Java/docs/data use LF; PowerShell and batch scripts
use CRLF according to `.gitattributes`. No empty normalization commit was created.
