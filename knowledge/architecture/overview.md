# Architecture overview

Status: superseded

The old two-pass world/four-pass render summary was a May snapshot. Current entry
points are [CLAUDE.md](../../CLAUDE.md), [PROJECT_MAP](../../docs/PROJECT_MAP.md) and
[the architecture documents](../../docs/architecture/). Generation now separates
terrain/caves/ores/vegetation and saved-state restoration before publication;
rendering includes shadow, water, HDR/post, sky, entities, precipitation and UI.
