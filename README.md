# BiomeShift

[![Build](https://github.com/ItzMeDwii/BiomeShift/actions/workflows/build.yml/badge.svg)](https://github.com/ItzMeDwii/BiomeShift/actions/workflows/build.yml)

Change any biome into any other biome by building with ordinary vanilla blocks.
Server-side only — vanilla clients join without installing anything, and there
are no custom blocks, items, machines or GUI.

- Minecraft Java **26.3**
- Fabric Loader **0.20.1+**, Fabric API **0.166.2+26.3**
- Java **25+**

## How it works

Every 16×16×16 chunk section is scored against per-biome signature block
weights. When a player places a mapped block, that section is queued; on the
next server ticks the section is scanned and, if one biome's score reaches the
threshold, beats the current biome by more than the hysteresis margin, and the
section carries that biome's traits, its biome cells are rewritten and the chunk
is resent to tracking clients so grass, foliage, water and sky colours update
immediately.

Scanning, rewriting and resending are budgeted per tick (`maxSectionsPerTick`)
and resends are throttled per chunk (`cooldownTicks`). Unloaded chunks are
skipped and never force-loaded.

Only **player** block placement triggers scoring. Dispensers, pistons and
command/WorldEdit block writes do not.

### Scores are deltas against the natural baseline

The first time a player touches a section, its existing block scores are recorded
as that section's baseline (minus the placements that triggered the scan). From
then on only the difference counts, so untouched terrain can never win a section
on its own. The baseline is in memory, recaptured after a restart, and cleared by
`/biomeshift reload`.

### Trait gate — the section must *look like* the biome

Every biome's weights are classified into look roles (`trunk`, `leaves`,
`ground`, `plants`, `stone`, `liquid`; decor never gates). A biome may only win
when the section carries every structural role its weights use, and overworld
targets additionally need ground. Twenty cherry logs alone therefore do not make
a cherry grove; logs plus leaves plus ground cover do. Blocks below weight 4 may
score but never create a role requirement, so damped dirt/stone noise cannot
demand anything.

`/biomeshift inspect` shows the coverage, e.g. `cherry_grove 2/4 (missing leaves)`.

### Flip extent

A flip covers the whole chunk column by default (`verticalScope: column`), so
the surface, grass colour and sky stay coherent. `verticalScope: above` limits
it to the flipped section and everything above; `verticalScope: section` keeps it
to the 16-block section that was built in (cave biomes survive).

### Trees

A sapling (or fungus) a player plants is both a signal and a marker: planting it
scores immediately, and the section it sits in stays "warm" for `rescanWindowTicks`.
While warm, one block is read every `rescanIntervalTicks`; once the sapling is
gone — grown into a tree or broken — a full scan runs, so the log and leaf weights
of a grown tree count. Natural growth can take a while, hence the generous window;
nothing is scanned while the sapling is still standing.

## Install

1. Install Fabric Loader 0.20.1+ on a 26.3 server.
2. Put `biomeshift-<mod>+<game>.jar` (e.g. `biomeshift-0.1.0+26.3.jar`, from
   [Releases](https://github.com/ItzMeDwii/BiomeShift/releases)) and Fabric API in `mods/`.
3. Start the server once — `config/biomeshift.json` is created with comments and
   the bundled default weights (67 vanilla biomes). The mod never rewrites an
   existing config; delete the file to regenerate it.

## Config reference — `config/biomeshift.json`

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Master switch; `false` disables all scoring without removing the mod |
| `mode` | `"threshold"` | `threshold` = score-based; `instant` = any mapped block flips the section to the biome giving that block the highest weight |
| `threshold` | `12` | Minimum score before a biome may win a section |
| `hysteresis` | `4` | Extra score the current biome holds, preventing flip-flop near the threshold |
| `scope` | `"section"` | Only `section` is implemented; other values are rejected at load |
| `verticalScope` | `"column"` | How far a flip reaches: `column` (whole chunk column), `above` (section and up) or `section` |
| `wandMaxChunks` | `256` | Largest wand selection a single right-click may sync |
| `cooldownTicks` | `40` | Minimum ticks between two biome resends of the same chunk |
| `maxSectionsPerTick` | `8` | Sections scanned per server tick |
| `rescanIntervalTicks` | `400` | How often a section holding a planted sapling is checked (20 s) |
| `rescanWindowTicks` | `72000` | How long a planted sapling keeps its section warm (1 h) |
| `dimensions` | `["minecraft:overworld"]` | Dimensions where scoring runs; add `minecraft:the_nether` / `minecraft:the_end` to enable them |
| `weights` | 67 biomes | `biome id` → `{ block id: weight }` |

Weights are positive numbers. A block may contribute to several biomes. Use
blocks players can actually place; weights of 8–10 mark a primary signature
block, 2–5 secondary ones. Unknown biome or block ids are logged and skipped,
never fatal. `#tag` selectors are rejected with a warning (not expanded yet).
Comments (`//`) are allowed in the file and survive hand-editing; the parser is
lenient.

## Commands

| Command | Permission node | Effect |
|---|---|---|
| `/biomeshift reload` | `biomeshift.command.reload` | Re-read `config/biomeshift.json` without restarting (also resets baselines) |
| `/biomeshift status` | `biomeshift.command.status` | Print loaded biome/block counts and the active mode |
| `/biomeshift inspect` | `biomeshift.command.inspect` | Chat snapshot of the section you stand in: current biome, top three delta scores, mapped-block count, trait coverage and the would-be winner |
| `/biomeshift inspect on\|off` | `biomeshift.command.inspect` | Toggle an action-bar overlay that refreshes the same numbers once per second |
| `/biomeshift inspect border [on\|off]` | `biomeshift.command.inspect` | Draw the 16×16 chunk column you stand in with cyan dust particles (pulse twice per second); bare `border` flips it |
| `/biomeshift sync` | `biomeshift.command.inspect` | Rewrite the whole chunk column to the biome of the section you stand in (one-shot repair for mixed columns) |
| `/biomeshift wand` | `biomeshift.command.wand` | Give yourself the BiomeShift wand (tagged golden hoe). Left-click corner 1, right-click corner 2: every loaded chunk column between them is synced to the biome at corner 1, capped by `wandMaxChunks` |

The wand never tills or breaks blocks (both interactions are consumed while held).
Unloaded chunks inside a selection are skipped and reported instead of being
force-generated.

### Permissions (LuckPerms)

Checks go through Fabric API's permission API, so any provider implementing it
works — LuckPerms implements `fabric-permission-api` v1 out of the box, no extra
dependency. LuckPerms sees the nodes as `namespace.path`:

```
/lp group admin permission set biomeshift.command.reload true
/lp group admin permission set biomeshift.command.status true
/lp group admin permission set biomeshift.command.inspect true
/lp group admin permission set biomeshift.command.wand true
```

Without a permission provider, all commands fall back to vanilla permission
level 2 (game master), which is what a vanilla op already has. With LuckPerms
installed the plugin's answer wins, so grant the nodes where you need them.

## Where the weights come from

The shipped weights are generated from the Minecraft jar, not hand-written:

```
python3 tools/generate_biome_templates.py [jar]   # worldgen features -> roles + block counts
python3 tools/generate_weights.py [jar]           # -> src/main/resources/biomeshift_defaults.json
```

`generate_biome_templates.py` follows `worldgen/biome` → `placed_feature` →
`feature` → `block_state_provider` (nested feature refs included, features shared
by ≥40% of biomes dropped as underground boilerplate) and writes
`src/main/resources/biomeshift/biome_templates.json`: per biome its blocks grouped
by role, its effect colours and the feature ids. That file is also the human
reference for "what does this biome actually place".

`generate_weights.py` turns that into scores:

| Source | Weight |
|---|---|
| Surface-rule block, first/most specific (`material_rule`, biome-scoped) | 9 |
| Other surface branches, cave-ceiling rules ignored | 6 |
| Deep block (dimension `default_block`: stone/netherrack/end_stone) | 3 |
| Feature block | role base (trunk/ground 9, leaves 8, plants 6, stone 6) minus rank |
| Block repeated by ≥75 % / ≥40 % / ≥20 % of biomes | capped at 2 / 3 / 5 |
| Top block of a biome | promoted to 9 |

Curated build materials live in `tools/weights_overlay.json` (extracted once from
the hand-written weights; edit freely) and win over the generated values.
`generate_weights.py --check` prints how far the current defaults are behind the
jar without writing anything. Delete `config/biomeshift.json` (or edit it) after
regenerating so existing worlds pick up the new defaults.

## Building

```bash
./gradlew build     # compile + test + jar (build/libs/biomeshift-<mod>.jar)
./gradlew test      # JVM-only unit tests
./gradlew runServer # dev server (needs run/eula.txt)
```

CI (`.github/workflows/build.yml`) builds and tests every push and pull request,
and on every push to `master` it publishes
`biomeshift-<mod>+<game>.jar` as release `v<mod>+<game>` (e.g. `v0.1.0+26.3`).
Bump `version` in `gradle.properties` to cut a new release; re-pushing the same
version re-uploads the jar to the existing release instead of creating a new one.

## Manual test checklist

1. Build toward a neighbouring biome in one section; F3 biome changes after the
   trait coverage is complete and the score passes the threshold — without
   relogging, and the whole chunk column changes (`verticalScope: column`).
2. Restart the server; the converted biome persists.
3. `/biomeshift inspect` reports deltas (not natural terrain), trait coverage and
   the would-be winner.
4. `/biomeshift inspect border` outlines the chunk you stand in; `/biomeshift
   sync` repairs a column left mixed by an older build.
5. `/biomeshift wand`: corner 1 left-click, corner 2 right-click; a selection over
   `wandMaxChunks` is rejected.
6. Set `dimensions` to only the overworld; verify the nether is untouched.
7. Join with a vanilla client and confirm colours update.
8. `/biomeshift reload` after editing weights; delete the config and restart to
   confirm the commented template is recreated.

## AI usage disclosure

The code in this repository was written by an AI coding assistant working under
human direction.

- **AI-written:** all Java sources, Gradle configuration, the mixin config, the
  worldgen generator scripts and the generated weights, and the documentation.
- **Human-directed:** the feature decisions (section scope, threshold mode,
  trait gating, column-scoped flips, wand selection, ignored block breaks,
  player-only triggers), the config schema, the config file name and command
  names, and the final review.
- **Mechanically verified before release:** `./gradlew build` and `./gradlew test`
  (26 tests, positive and negative cases); API names checked against the 26.3
  javadoc and the real 26.3 jar via `javap` (`fillBiomesFromNoise`, the
  `placeBlock` mixin target, particle and data-component APIs); clean Fabric 26.3
  server boots with only Fabric API, and with LuckPerms 5.5.85 for the permission
  paths.
- **Not verified without a human:** in-game visuals — biome flip colours, particle
  border, wand selection outcome, persistence across restart. See the manual test
  checklist above.

The upstream BiomeExpansion source was read as reference for the biome-write
technique only; none of its Forge code was copied. Its own provenance is
documented in `NOTICE`.

## License

MIT. Port inspiration and the biome-rewrite technique come from
[BiomeExpansion](https://github.com/Mysticpasta1/BiomeExpansion) by Mysticpasta1;
see `NOTICE`.
