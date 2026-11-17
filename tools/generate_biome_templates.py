#!/usr/bin/env python3
"""Generate biome look-templates from a Minecraft jar's worldgen data.

Usage:
    python3 tools/generate_biome_templates.py [path/to/minecraft-merged.jar]

Reads data/minecraft/worldgen/{biome,placed_feature,configured_feature} and writes
src/main/resources/biomeshift/biome_templates.json: per biome the blocks its
features place, grouped into roles (trunk / leaves / ground / plants / stone /
liquid), plus the effect colours. The mod uses the roles as a "does this section
look like that biome" gate; the file doubles as human-readable reference.
"""
import json
import re
import sys
import zipfile
from collections import Counter
from pathlib import Path

DEFAULT_JAR = Path.home() / ".gradle/caches/fabric-loom/26.3/minecraft-merged.jar"
OUT = Path(__file__).resolve().parent.parent / "src/main/resources/biomeshift/biome_templates.json"

GROUND = {
    "dirt", "grass_block", "podzol", "mycelium", "coarse_dirt", "rooted_dirt",
    "moss_block", "mud", "clay", "sand", "red_sand", "gravel", "soul_soil",
    "soul_sand", "farmland", "dirt_path", "snow_block", "powder_snow", "ice",
    "packed_ice", "blue_ice", "frosted_ice", "snow",
}
LIQUID = {"water", "lava", "flowing_water", "flowing_lava"}
PLANTS = {
    "short_grass", "tall_grass", "fern", "large_fern", "dead_bush", "cactus",
    "vine", "bamboo", "sugar_cane", "kelp", "kelp_plant", "seagrass",
    "tall_seagrass", "brown_mushroom", "red_mushroom", "crimson_roots",
    "warped_roots", "nether_sprouts", "sweet_berry_bush", "glow_lichen",
    "hanging_roots", "big_dripleaf", "big_dripleaf_stem", "small_dripleaf",
    "spore_blossom", "moss_carpet", "melon", "pumpkin", "sea_pickle",
    "lily_pad", "pink_petals", "wildflowers", "leaf_litter", "bush",
    "firefly_bush", "short_dry_grass", "tall_dry_grass", "cave_vines",
    "cave_vines_plant", "wheat", "carrots", "potatoes",
}
FLOWER_SUFFIX = ("_flower", "_tulip", "_blossom", "_orchid", "_allium", "_lily")


def role_of(block_id: str) -> str:
    name = block_id.split(":", 1)[1]
    if name.endswith(("_log", "_wood", "_stem", "_hyphae")) or name in ("mushroom_stem", "bamboo_block"):
        return "trunk"
    if name.endswith("_leaves"):
        return "leaves"
    if name in GROUND or name.endswith("_nylium"):
        return "ground"
    if name in LIQUID:
        return "liquid"
    if name in PLANTS or name.endswith("_sapling") or name.endswith(FLOWER_SUFFIX) or name.startswith("potted_"):
        return "plants"
    return "stone"


def collect_blocks(node, out: Counter, blocks: set, providers: dict, configured: dict,
                   seen: set) -> None:
    """Walks a feature config, following provider refs and nested configured features."""
    if isinstance(node, dict):
        for key, value in node.items():
            if isinstance(value, str):
                if value in blocks:
                    out[value] += 1
                elif value in configured and value not in seen:
                    seen.add(value)
                    nested = configured[value]
                    collect_blocks(nested.get("config", nested), out, blocks, providers,
                                   configured, seen)
                elif key.endswith("_provider") or key.endswith("_state"):
                    collect_blocks(providers.get(value, {}), out, blocks, providers,
                                   configured, seen)
            else:
                collect_blocks(value, out, blocks, providers, configured, seen)
    elif isinstance(node, list):
        for value in node:
            collect_blocks(value, out, blocks, providers, configured, seen)


def main() -> int:
    jar_path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_JAR
    if not jar_path.is_file():
        print(f"jar not found: {jar_path}", file=sys.stderr)
        return 1

    with zipfile.ZipFile(jar_path) as jar:
        def load(prefix: str) -> dict:
            entries = {}
            for name in jar.namelist():
                if not name.startswith(prefix) or not name.endswith(".json"):
                    continue
                with jar.open(name) as handle:
                    entries["minecraft:" + name[len(prefix):-5]] = json.load(handle)
            return entries

        biomes = load("data/minecraft/worldgen/biome/")
        placed = load("data/minecraft/worldgen/placed_feature/")
        configured = load("data/minecraft/worldgen/feature/")
        providers = load("data/minecraft/worldgen/block_state_provider/")
        lang = json.loads(jar.read("assets/minecraft/lang/en_us.json"))
        blocks = {"minecraft:" + key[len("block.minecraft."):]
                  for key in lang if key.startswith("block.minecraft.")}

    # Features shared by most biomes (ores, lava lakes, monster rooms, geodes) are
    # underground boilerplate, not biome look. Drop them from the templates.
    feature_counts = Counter()
    for biome in biomes.values():
        for step in biome.get("features", []):
            feature_counts.update(step)
    universal = {feature for feature, count in feature_counts.items()
                 if count >= len(biomes) * 0.4}

    result = {}
    missing = 0
    for biome_id, biome in sorted(biomes.items()):
        features = []
        for step in biome.get("features", []):
            features.extend(f for f in step if f not in universal)
        found = Counter()
        for feature_id in features:
            placed_json = placed.get(feature_id)
            if placed_json is None:
                missing += 1
                continue
            configured_json = configured.get(placed_json.get("feature", ""))
            if configured_json is None:
                missing += 1
                continue
            collect_blocks(configured_json.get("config", configured_json), found, blocks, providers,
                           configured, {placed_json.get("feature", "")})

        roles = {}
        for block, count in found.most_common():
            if block == "minecraft:air":
                continue
            roles.setdefault(role_of(block), []).append(block)
        effects = biome.get("effects", {})
        result[biome_id] = {
            "temperature": biome.get("temperature"),
            "has_precipitation": biome.get("has_precipitation"),
            "colors": {key: effects.get(key) for key in
                       ("grass_color", "foliage_color", "water_color", "sky_color", "fog_color")
                       if effects.get(key) is not None},
            "roles": roles,
            "blocks": {block: count for block, count in found.most_common()
                       if block != "minecraft:air"},
            "features": features,
        }

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({"source": f"minecraft worldgen from {jar_path.name}",
                               "biomes": result}, indent="\t") + "\n")
    print(f"wrote {OUT} ({len(result)} biomes, {missing} unresolved feature refs)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
