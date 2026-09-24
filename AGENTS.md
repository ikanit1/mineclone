# Agent entry point
Read [CLAUDE.md](CLAUDE.md), then the linked subsystem document before edits.
Plan: [docs/ROADMAP_1_1.md](docs/ROADMAP_1_1.md).
Compile: `.\run.ps1 -CompileOnly`; play: `.\run.ps1`.
Tests: `.\run-tests.ps1`; filter: `-Only save,net`; list: `-List`.
LAN: `.\run-net-test.ps1`; server: `.\run-server-test.ps1` (compile first).
Gradle: `.\gradlew.bat test serverZip`; package smoke: `.\tools\TestServerPackage.ps1`.
Rules: append-only block IDs, protected saves, restore-before-publish, host authority.
Map: [docs/PROJECT_MAP.md](docs/PROJECT_MAP.md); regenerate: `java tools/GenProjectMap.java`.
`/graphify` explicitly invokes the installed graphify skill; old graphs are historical.
