# Historical generators

These one-shot generators were moved from the repository root by FND-02.
They document the original asset process; current runtime textures are in
`assets/textures/blocks/` and the current importer is `tools/import_equipment_pack.py`.

`SaveRoundTrip.java.txt` is the historical save harness, retained as a reference.
Its level/chunk/options round trips are covered by `CoreTests.runSave` and the
new save safety suite. Its obsolete Options API is intentionally not compiled.

Generated root classes, old logs and the incorrectly named historical server
test world were preserved locally in `out-test/legacy-root-20260924/`.
The current server configuration reader already preserves Windows path
backslashes (`ServerConfig.sanitize`); network tests cover this regression.
