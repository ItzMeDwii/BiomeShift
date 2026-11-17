# Changelog

## 0.1.0 — unreleased

First implementation, targeting Minecraft 26.3 / Fabric Loader 0.20.1 / Fabric API 0.166.2+26.3.

- Server-side only; vanilla clients join unmodified (`environment: server`).
- Trigger: mixin on `BlockItem#placeBlock` tail, players only (dispensers, pistons and
  command/WorldEdit block writes never trigger).
- Scoring: per-biome signature-block weights summed over the 16x16x16 section that
  received the placement, with `threshold` and `hysteresis` so sections do not flip-flop.
- Rewrite: all 64 biome cells of the winning section via `LevelChunkSection#fillBiomesFromNoise`,
  `ChunkAccess#markUnsaved()`, then `ChunkMap#resendBiomesForChunks` so client colours update
  without relogging.
- Work is queued per section and drained on the server tick (`maxSectionsPerTick`,
  `cooldownTicks`); unloaded chunks are skipped, never force-loaded.
- Config: `config/biomeshift.json`, created from bundled defaults for 67 vanilla
  biomes (819 weight entries over 164 blocks), reloadable with `/biomeshift reload`.
  The file is written with explanatory `//` comments on first start and never
  rewritten afterwards; the parser is lenient, so comments survive hand-editing.
- Permissions: `/biomeshift reload` and `/biomeshift status` are gated by
  `biomeshift.command.reload` / `biomeshift.command.status` via Fabric API's
  permission API, so LuckPerms can grant or deny them; vanilla level 2 is the
  fallback when no provider answers.
- Tree support: every sapling/propagule/azalea/fungus now carries a low weight in
  its tree biomes (forest 4, biome-specific saplings 3–5), so planting scores
  immediately. Planting also warms the section: one block read every
  `rescanIntervalTicks` (400) for up to `rescanWindowTicks` (72000); once the
  sapling is gone a full scan runs, so a grown tree's logs/leaves count even
  though growth never fires the placement trigger.
- Scores are measured against a per-section **baseline**: the first time a player
  touches a section, its existing block scores are recorded (minus the triggering
  placements) and only the difference counts afterwards. Untouched terrain can
  never win a section on its own, and tree growth after a planted sapling counts
  as player-built. The baseline is in memory only and is recaptured after a
  restart; `/biomeshift reload` also resets it.
- Default weights are now generated from the Minecraft jar's worldgen data
  (`tools/generate_biome_templates.py` + `tools/generate_weights.py`), with
  `tools/weights_overlay.json` for curated build materials. Surface rules supply
  the ground blocks, features the body, and blocks shared by most biomes are
  damped so stone/dirt noise cannot decide a biome.
- Flips now cover the whole chunk column by default so the surface, grass colour and
  sky stay coherent; `verticalScope` accepts `column` (default), `above` or
  `section`. `/biomeshift sync` rewrites the column you stand in to the current
  section's biome, repairing chunks from earlier builds.
- `/biomeshift wand` gives a tagged golden hoe: left-click corner 1, right-click
  corner 2, and every loaded chunk column in between is synced to the biome at
  corner 1. `wandMaxChunks` (default 256) bounds one selection. Node
  `biomeshift.command.wand`.
- Dimensions now default to `minecraft:overworld` only; add more ids to
  `dimensions` in the config to enable the mod elsewhere.
- Trait gate: a biome only wins when the section carries every look role its
  weights use (trunk/leaves/plants/stone/...), and overworld targets also need
  ground. Blocks are classified into roles by block id, so one material spam
  (20 cherry logs) never flips a section on its own.
- `src/main/resources/biomeshift/biome_templates.json` + `tools/generate_biome_templates.py`:
  reference of what each biome's world generation places, grouped by role, derived
  from the vanilla worldgen data in the Minecraft jar.
- Debugging: `/biomeshift inspect` prints the section you stand in (current biome,
  top three scores, mapped-block count, verdict), and `/biomeshift inspect on|off`
  toggles a once-per-second action-bar overlay with the same numbers, and
  `/biomeshift inspect border [on|off]` draws the 16x16 chunk column you stand in
  with cyan dust particles. Numbers shown are player-built deltas, so a fresh
  section reads as zero.
- Node `biomeshift.command.inspect`.
- `enabled` master switch and a `dimensions` allow-list.
- Tests (26): scorer rules (threshold, hysteresis, unknown current biome,
  eligibility edges), top-score selection, baseline deltas, chunk-area limits,
  vertical-scope parsing, commented-config round-trip, bundled-defaults integrity.
