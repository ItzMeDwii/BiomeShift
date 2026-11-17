#!/usr/bin/env python3
"""Generate biomeshift default weights from a Minecraft jar's worldgen data.

Usage:
    python3 tools/generate_biome_templates.py     # first: templates (features -> roles)
    python3 tools/generate_weights.py [jar] [--check]

Weights = what the biome's generation actually puts on the ground:
  * surface-rule blocks (material_rule trees, biome-scoped)  -> 9 / 6 / 4
  * dimension default block (stone/netherrack/end_stone)     -> 3
  * feature blocks (biome_templates.json, per-role ranking)  -> 8..3
Curated build materials live in tools/weights_overlay.json and win over both.

Writes src/main/resources/biomeshift_defaults.json ({"weights": ...}). With
--check, writes nothing and prints the diff size instead.
"""
import json
import sys
import zipfile
from pathlib import Path

DEFAULT_JAR = Path.home() / ".gradle/caches/fabric-loom/26.3/minecraft-merged.jar"
ROOT = Path(__file__).resolve().parent.parent
TEMPLATES = ROOT / "src/main/resources/biomeshift/biome_templates.json"
OVERLAY = Path(__file__).resolve().parent / "weights_overlay.json"
OUT = ROOT / "src/main/resources/biomeshift_defaults.json"

EXCLUDED_SUFFIX = ("_ore",)
EXCLUDED_NAMES = {"air", "bedrock", "infested_stone", "infested_deepslate", "water", "lava",
                  "flowing_water", "flowing_lava"}
# Look weight per role: trunk/ground define a biome most, liquids least.
ROLE_BASE = {"trunk": 9.0, "leaves": 8.0, "ground": 9.0, "plants": 6.0,
             "stone": 6.0, "liquid": 3.0, "decor": 4.0}
ROLE_STEP = 0.5
ROLE_MIN = 3.0
UNDER = {"minecraft:under_floor", "minecraft:deep_under_floor", "minecraft:very_deep_under_floor",
         "minecraft:stone_depth"}
CEILING = {"minecraft:on_ceiling", "minecraft:under_ceiling"}


def name_of(block_id: str) -> str:
    return block_id.split(":", 1)[-1]


def excluded(block_id: str) -> bool:
    name = name_of(block_id)
    return name in EXCLUDED_NAMES or name.endswith(EXCLUDED_SUFFIX)


def load_rules(jar):
    rules = {}
    prefix = "data/minecraft/worldgen/material_rule/"
    for entry in jar.namelist():
        if entry.startswith(prefix) and entry.endswith(".json"):
            rules[entry[len(prefix):-5]] = json.loads(jar.read(entry))
    return rules


def load_biome_tags(jar):
    tags = {}
    prefix = "data/minecraft/tags/worldgen/biome/"
    for entry in jar.namelist():
        if entry.startswith(prefix) and entry.endswith(".json") and "/" not in entry[len(prefix):]:
            tags[entry[len(prefix):-5]] = json.loads(jar.read(entry))["values"]
    return tags


def resolve_tag(tag, tags, seen=None):
    seen = seen or set()
    if tag in seen:
        return set()
    seen.add(tag)
    out = set()
    for value in tags.get(tag, []):
        if value.startswith("#"):
            out |= resolve_tag(value[1:].replace("minecraft:", "", 1), tags, seen)
        else:
            out.add(value)
    return out


def walk_rule(node, biome_set, rules, tags, tier, out, seen_rules, layered=True, scoped=False):
    if isinstance(node, str):
        ref = node[len("minecraft:"):] if node.startswith("minecraft:") else node
        if ref in rules and ref not in seen_rules:
            # The biome-dispatch subtree is the surface proper; ore veins/underground are deep,
            # cave-ceiling rules are not what the biome looks like from above.
            child_tier = 0 if ref.endswith("surface") or "/biome_surface" in ref else tier
            walk_rule(rules[ref], biome_set, rules, tags, child_tier,
                      out, seen_rules | {ref}, layered, scoped)
        return
    if isinstance(node, list):
        for value in node:
            walk_rule(value, biome_set, rules, tags, tier, out, seen_rules, layered, scoped)
        return
    if not isinstance(node, dict):
        return

    kind = node.get("type", "")
    if kind == "minecraft:block":
        state = node.get("result_state")
        block = state if isinstance(state, str) else (state or {}).get("Name")
        if block and not excluded(block):
            for biome in biome_set:
                out[biome].setdefault(tier, []).append((block, scoped))
        return
    if kind == "minecraft:sequence":
        for value in node.get("sequence", []):
            walk_rule(value, biome_set, rules, tags, tier, out, seen_rules, layered, scoped)
        return
    if kind == "minecraft:condition":
        condition = node.get("if_true")
        narrowed = biome_set
        if isinstance(condition, str) and condition in CEILING:
            tier = 2
        elif isinstance(condition, dict) and condition.get("type") in CEILING:
            tier = 2
        elif layered and isinstance(condition, str) and condition in UNDER:
            tier = max(tier, 1)
        elif layered and isinstance(condition, dict) and condition.get("type") in UNDER:
            tier = max(tier, 1)
        if isinstance(condition, dict):
            ctype = condition.get("type")
            if ctype == "minecraft:biome":
                ids = condition.get("biome_is")
                ids = ids if isinstance(ids, list) else [ids]
                allowed = set()
                for entry in ids:
                    if isinstance(entry, str) and entry.startswith("#"):
                        allowed |= resolve_tag(entry[1:].replace("minecraft:", "", 1), tags)
                    elif entry:
                        allowed.add(entry)
                narrowed = biome_set & allowed
            elif ctype in ("minecraft:biome_tag", "minecraft:tag"):
                allowed = resolve_tag(condition.get("tag", "").replace("minecraft:", "", 1), tags)
                narrowed = biome_set & allowed
        if not narrowed:
            return
        walked = scoped or narrowed != biome_set
        walk_rule(node.get("then_run"), narrowed, rules, tags, tier, out, seen_rules, layered, walked)
        if "else_run" in node:
            walk_rule(node["else_run"], narrowed, rules, tags, tier, out, seen_rules, layered, walked)
        return
    # Unknown wrapper (and/or/not/y_above/noise_threshold/...): take every branch.
    for value in node.values():
        walk_rule(value, biome_set, rules, tags, tier, out, seen_rules, layered, scoped)


def surface_blocks(jar, rules, tags, biome_ids):
    """biome -> {tier: [blocks]}, scoped to the dimension each biome belongs to."""
    out = {biome: {} for biome in biome_ids}
    for dimension, tag in (("overworld", "is_overworld"), ("nether", "is_nether"), ("end", "is_end")):
        biomes = set(biome_ids) & resolve_tag(tag, tags)
        if not biomes:
            continue
        settings = json.loads(jar.read(f"data/minecraft/worldgen/noise_settings/{dimension}.json"))
        rule = settings.get("material_rule")
        layered = dimension == "overworld"
        if isinstance(rule, str):
            rule = rule[len("minecraft:"):] if rule.startswith("minecraft:") else rule
            walk_rule(rule, biomes, rules, tags, 0, out, set(), layered)
        else:
            walk_rule(rule, biomes, rules, tags, 0, out, set(), layered)
        default_block = settings.get("default_block")
        block = default_block if isinstance(default_block, str) else (default_block or {}).get("Name")
        if block and not excluded(block):
            for biome in biomes:
                out[biome].setdefault(2, []).append((block, False))
    return out


def surface_weight(tier, rank):
    if tier == 0:
        return 9.0 if rank == 0 else 6.0 if rank <= 3 else 4.0
    return 6.0 if tier == 1 else 3.0


def damp_common_blocks(weights):
    """A block repeated by most biomes (stone, dirt, gravel) cannot single out one look."""
    count = {}
    for blocks in weights.values():
        for block in blocks:
            count[block] = count.get(block, 0) + 1
    total = max(len(weights), 1)
    for blocks in weights.values():
        for block in list(blocks):
            share = count[block] / total
            if share >= 0.75:
                blocks[block] = min(blocks[block], 2.0)
            elif share >= 0.4:
                blocks[block] = min(blocks[block], 3.0)
            elif share >= 0.2:
                blocks[block] = min(blocks[block], 5.0)
    return weights


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    check = "--check" in sys.argv
    jar_path = Path(args[0]) if args else DEFAULT_JAR
    if not jar_path.is_file():
        print(f"jar not found: {jar_path}", file=sys.stderr)
        return 1

    templates = json.loads(TEMPLATES.read_text())["biomes"]
    with zipfile.ZipFile(jar_path) as jar:
        rules = load_rules(jar)
        tags = load_biome_tags(jar)
        surfaces = surface_blocks(jar, rules, tags, set(templates))

    overlay = json.loads(OVERLAY.read_text()) if OVERLAY.exists() else {}

    weights = {}
    for biome, template in sorted(templates.items()):
        blocks = {}
        for tier, entries in sorted(surfaces.get(biome, {}).items()):
            best = {}
            for index, (block, scoped) in enumerate(entries):
                candidate = (0 if scoped else 1, index)
                if block not in best or candidate < best[block]:
                    best[block] = candidate
            ordered = [block for block, _ in sorted(best.items(), key=lambda kv: kv[1])]
            for rank, block in enumerate(ordered):
                blocks[block] = max(blocks.get(block, 0.0), surface_weight(tier, rank))
        for role, entries in template.get("roles", {}).items():
            base = ROLE_BASE.get(role, ROLE_BASE["decor"])
            for rank, block in enumerate(entries):
                if excluded(block):
                    continue
                value = max(ROLE_MIN, base - ROLE_STEP * rank)
                blocks[block] = max(blocks.get(block, 0.0), round(value, 1))
        for block, value in overlay.get(biome, {}).items():
            blocks[block] = float(value)
        if blocks:
            weights[biome] = dict(sorted(blocks.items()))

    damp_common_blocks(weights)
    for blocks in weights.values():
        top = max(blocks.values(), default=0.0)
        if 3.0 < top < 9.0:
            for block, value in blocks.items():
                if value == top:
                    blocks[block] = 9.0
    payload = {"weights": weights}
    text = json.dumps(payload, indent="\t") + "\n"
    if check:
        old = json.loads(OUT.read_text())["weights"]
        added = sum(len(set(weights[b]) - set(old.get(b, {}))) for b in weights)
        removed = sum(len(set(old.get(b, {})) - set(weights[b])) for b in weights)
        print(f"check: {len(weights)} biomes, {sum(map(len, weights.values()))} entries "
              f"(+{added} / -{removed} vs current defaults)")
        return 0

    OUT.write_text(text)
    print(f"wrote {OUT} ({len(weights)} biomes, {sum(map(len, weights.values()))} entries)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
