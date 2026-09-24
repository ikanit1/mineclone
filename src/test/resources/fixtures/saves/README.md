# Historical save fixtures

These files are immutable compatibility evidence. Tests copy each world to a
temporary directory before upgrading it. Never open this directory as a playable
save root or regenerate it with the current writer.

The three `alpha-*` worlds were written by the actual compiled `v1.0.0-alpha`
tag at commit `448347596c1c3d944df9720de17b7e7b5c99a655`, in a detached worktree.
The old build's classes and item assets were used; current `SaveManager` was not
on the writer classpath. `tools/MakeSaveFixtures.java` drives that old public API.

| World | Level/chunk format | Coverage |
| --- | --- | --- |
| alpha-small | v10 / v6 | Five generated chunks with distinct edits and metadata, inventory, pending cursor stack |
| alpha-chests | v10 / v6 | Chest, worn tools, furnace slots and all timers, dropped worn tool and position/age |
| alpha-creative | v10 / v6 | Creative inventory, creative mode, pending stack |
| legacy-level-v6-chunk-v4 | v6 / v4 | Old block/count inventory, default health/hunger, raw block arrays, tagged legacy container stacks |
| legacy-level-v8-chunk-v4 | v8 / v4 | Tagged tools and wear, saved health, default hunger |
| legacy-level-v9-chunk-v5 | v9 / v5 | Saved hunger, handwritten RLE arrays, legacy container and dropped stacks |

The three `legacy-*` files are explicitly synthetic historical layouts, not
claimed as output from their historical writers. Their field ordering follows
`SaveManager`/`SaveFormat`/`RunLengthCodec` in history at `13e2c24` (level v9/chunk
v5, with older-version branches) and `4ccd389` (level v6 introduction). The fixture
generator writes those bytes directly rather than calling current codecs.

Each `fixture.json` contains the expected level fields, occupied inventory slots,
tool damage, chest/furnace/item state, opaque sections, raw block/metadata SHA-256
hashes, and hashes of the original `.dat` files. Expected values come from fixture
inputs, before reading the files. The six worlds total about 23 KiB including their
manifests, comfortably below the 2 MiB cap.

Reproduce into a **new empty directory** (repository-root PowerShell):

```powershell
git worktree add --detach build/alpha-fixture-writer v1.0.0-alpha
$fixtureOut = Join-Path $PWD 'build/alpha-fixture-writer/out-fixtures'
New-Item -ItemType Directory -Force $fixtureOut | Out-Null
$fixtureJars = (Get-ChildItem libs -Filter *.jar | ForEach-Object FullName) -join ';'
$fixtureSources = @(Get-ChildItem build/alpha-fixture-writer/src/main/java -Recurse -Filter *.java | ForEach-Object FullName)
$fixtureList = Join-Path $fixtureOut 'sources.txt'
[IO.File]::WriteAllLines($fixtureList, [string[]]$fixtureSources, [Text.UTF8Encoding]::new($false))
javac -encoding UTF-8 -d $fixtureOut -cp $fixtureJars "@$fixtureList"
$fixtureGenerator = Join-Path $PWD 'tools/MakeSaveFixtures.java'
$fixtureFresh = Join-Path $PWD 'build/regenerated-save-fixtures'
$fixtureLibraries = Join-Path $PWD 'libs/*'
Push-Location build/alpha-fixture-writer
try { java -cp "out-fixtures;$fixtureLibraries" $fixtureGenerator $fixtureFresh }
finally { Pop-Location }
```

`SaveMigrationTests` checks all manifest values before and after migration to the
current level/chunk formats and verifies source hashes remain unchanged. Nightly
CI also runs the read-only `tools/CheckSaves.java` sweep over this corpus. Adding
future v7 chunk/v2 entity coverage must preserve these original golden files.
