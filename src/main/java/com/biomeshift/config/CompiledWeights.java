package com.biomeshift.config;

import com.biomeshift.BiomeShift;
import com.biomeshift.score.SectionScorer;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable, registry-resolved view of the JSON weights. */
public final class CompiledWeights {
	/** Compact per-block contribution: parallel arrays of biome index and weight. */
	public record WeightSet(int[] biomes, float[] weights) {
	}

	/** Look roles. A biome may only win when the section carries every role its weights use. */
	public static final int ROLE_TRUNK = 0;
	public static final int ROLE_LEAVES = 1;
	public static final int ROLE_GROUND = 2;
	public static final int ROLE_PLANTS = 3;
	public static final int ROLE_STONE = 4;
	public static final int ROLE_LIQUID = 5;
	public static final int ROLE_DECOR = 6;
	private static final int ROLE_COUNT = 7;
	private static final String[] ROLE_NAMES = {"trunk", "leaves", "ground", "plants", "stone", "liquid", "decor"};

	private static final Set<String> GROUND = Set.of(
			"dirt", "grass_block", "podzol", "mycelium", "coarse_dirt", "rooted_dirt", "moss_block",
			"mud", "clay", "sand", "red_sand", "gravel", "soul_soil", "soul_sand", "farmland",
			"dirt_path", "snow_block", "powder_snow", "ice", "packed_ice", "blue_ice", "frosted_ice", "snow");
	private static final Set<String> PLANTS = Set.of(
			"short_grass", "tall_grass", "fern", "large_fern", "dead_bush", "cactus", "vine", "bamboo",
			"sugar_cane", "kelp", "kelp_plant", "seagrass", "tall_seagrass", "brown_mushroom",
			"red_mushroom", "crimson_roots", "warped_roots", "nether_sprouts", "sweet_berry_bush",
			"glow_lichen", "hanging_roots", "big_dripleaf", "big_dripleaf_stem", "small_dripleaf",
			"spore_blossom", "moss_carpet", "melon", "pumpkin", "sea_pickle", "lily_pad", "pink_petals",
			"wildflowers", "leaf_litter", "bush", "firefly_bush", "short_dry_grass", "tall_dry_grass",
			"cave_vines", "cave_vines_plant", "wheat", "carrots", "potatoes");
	/** Blocks below this weight are noise for the trait gate: they may score, never gate. */
	private static final float MIN_ROLE_WEIGHT = 4.0f;
	private static final Set<String> STONE = Set.of(
			"stone", "deepslate", "andesite", "diorite", "granite", "tuff", "calcite", "dripstone_block",
			"basalt", "smooth_basalt", "polished_basalt", "blackstone", "netherrack", "end_stone",
			"obsidian", "magma_block", "glowstone", "amethyst_block", "budding_amethyst", "sandstone",
			"red_sandstone", "smooth_stone", "cobblestone", "cobbled_deepslate", "mossy_cobblestone",
			"sculk", "gilded_blackstone");

	private final List<Holder<Biome>> biomeList;
	private final Map<ResourceKey<Biome>, Integer> indexByKey;
	private final Map<Block, WeightSet> weightsByBlock;
	private final Set<ResourceKey<Level>> dimensions;
	/** Required role bitmask per biome, derived from the roles of its weighted blocks. */
	private final int[] rolesByBiome;
	private final Map<Block, Integer> roleCache = new IdentityHashMap<>();
	private final boolean enabled;
	private final boolean instant;
	private final String mode;
	private final String verticalScope;
	private final float threshold;
	private final float hysteresis;
	private final int cooldownTicks;
	private final int maxSectionsPerTick;
	private final int rescanIntervalTicks;
	private final int rescanWindowTicks;
	private final int wandMaxChunks;

	private CompiledWeights(List<Holder<Biome>> biomeList, Map<ResourceKey<Biome>, Integer> indexByKey,
			Map<Block, WeightSet> weightsByBlock, int[] rolesByBiome, Set<ResourceKey<Level>> dimensions,
			ConfigData cfg) {
		this.biomeList = biomeList;
		this.indexByKey = indexByKey;
		this.weightsByBlock = weightsByBlock;
		this.rolesByBiome = rolesByBiome;
		this.dimensions = dimensions;
		this.enabled = cfg.enabled;
		this.mode = cfg.mode == null ? "threshold" : cfg.mode;
		this.verticalScope = normalizeVerticalScope(cfg.verticalScope);
		this.instant = "instant".equalsIgnoreCase(this.mode);
		this.threshold = Math.max(0, cfg.threshold);
		this.hysteresis = Math.max(0, cfg.hysteresis);
		this.cooldownTicks = Math.max(0, cfg.cooldownTicks);
		this.maxSectionsPerTick = Math.max(1, cfg.maxSectionsPerTick);
		this.rescanIntervalTicks = Math.max(100, cfg.rescanIntervalTicks);
		this.rescanWindowTicks = Math.max(0, cfg.rescanWindowTicks);
		this.wandMaxChunks = Math.max(1, cfg.wandMaxChunks);
	}

	static String normalizeVerticalScope(String raw) {
		String scope = raw == null ? "column" : raw.toLowerCase(java.util.Locale.ROOT);
		if (!scope.equals("column") && !scope.equals("above") && !scope.equals("section")) {
			throw new IllegalStateException("unsupported verticalScope '" + raw + "': use column, above or section");
		}
		return scope;
	}

	public static CompiledWeights load(MinecraftServer server) {
		ConfigData cfg = ConfigStore.loadOrCreate();

		if (cfg.scope != null && !"section".equalsIgnoreCase(cfg.scope)) {
			throw new IllegalStateException("unsupported scope '" + cfg.scope + "': only 'section' is implemented");
		}
		if (cfg.mode != null && !"threshold".equalsIgnoreCase(cfg.mode) && !"instant".equalsIgnoreCase(cfg.mode)) {
			throw new IllegalStateException("unsupported mode '" + cfg.mode + "': use 'threshold' or 'instant'");
		}

		Registry<Biome> biomes = server.registryAccess().lookupOrThrow(Registries.BIOME);
		List<Holder<Biome>> biomeList = new ArrayList<>();
		Map<ResourceKey<Biome>, Integer> indexByKey = new HashMap<>();
		Map<Block, Map<Integer, Float>> raw = new HashMap<>();

		if (cfg.weights != null) {
			for (Map.Entry<String, Map<String, Double>> entry : cfg.weights.entrySet()) {
				Identifier biomeId = ConfigStore.parseId(entry.getKey(), "biome");
				if (biomeId == null) continue;
				ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, biomeId);
				Biome biome = biomes.getValue(key);
				if (biome == null) {
					BiomeShift.LOG.warn("unknown biome '{}': skipped", biomeId);
					continue;
				}
				int index = biomeList.size();
				biomeList.add(biomes.wrapAsHolder(biome));
				indexByKey.put(key, index);

				if (entry.getValue() == null) continue;
				for (Map.Entry<String, Double> blockEntry : entry.getValue().entrySet()) {
					double weight = blockEntry.getValue() == null ? 0 : blockEntry.getValue();
					if (weight <= 0) continue;
					Block block = resolveBlock(blockEntry.getKey());
					if (block == null) continue;
					raw.computeIfAbsent(block, b -> new HashMap<>()).merge(index, (float) weight, Float::sum);
				}
			}
		}

		Map<Block, WeightSet> frozen = new IdentityHashMap<>();
		int[] rolesByBiome = new int[biomeList.size()];
		for (Map.Entry<Block, Map<Integer, Float>> entry : raw.entrySet()) {
			Map<Integer, Float> perBiome = entry.getValue();
			int[] idx = new int[perBiome.size()];
			float[] w = new float[perBiome.size()];
			int i = 0;
			int roleBit = 1 << classify(entry.getKey());
			for (Map.Entry<Integer, Float> e : perBiome.entrySet()) {
				idx[i] = e.getKey();
				w[i] = e.getValue();
				if (e.getValue() >= MIN_ROLE_WEIGHT) {
					rolesByBiome[e.getKey()] |= roleBit;
				}
				i++;
			}
			frozen.put(entry.getKey(), new WeightSet(idx, w));
		}
		// Decor blocks never gate a shift; only structural roles do.
		for (int b = 0; b < rolesByBiome.length; b++) rolesByBiome[b] &= ~(1 << ROLE_DECOR);

		Set<ResourceKey<Level>> dims = new HashSet<>();
		if (cfg.dimensions != null) {
			for (String rawDim : cfg.dimensions) {
				Identifier id = ConfigStore.parseId(rawDim, "dimension");
				if (id != null) dims.add(ResourceKey.create(Registries.DIMENSION, id));
			}
		}

		return new CompiledWeights(biomeList, indexByKey, frozen, rolesByBiome, dims, cfg);
	}

	private static Block resolveBlock(String raw) {
		if (raw.startsWith("#")) {
			// Tags are not expanded here: resolving tag membership needs the live registry,
			// and the shipped defaults use concrete ids only.
			BiomeShift.LOG.warn("block tag '{}' is not supported yet: skipped", raw);
			return null;
		}
		Identifier id = ConfigStore.parseId(raw, "block");
		if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
			BiomeShift.LOG.warn("unknown block '{}': skipped", raw);
			return null;
		}
		return BuiltInRegistries.BLOCK.getValue(id);
	}

	/** Accumulated section scores plus the role mask the trait gate reads. */
	public record ScanResult(float[] scores, int roles, int mappedBlocks, int nonAirBlocks) {
	}

	/** Sums the weights of every mapped block inside the section. */
	public ScanResult scan(LevelChunkSection section) {
		float[] scores = new float[biomeList.size()];
		int roles = 0;
		int mapped = 0;
		int nonAir = 0;
		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					BlockState state = section.getBlockState(x, y, z);
					if (state.isAir()) continue;
					nonAir++;
					Block block = state.getBlock();
					roles |= 1 << roleOf(block);
					WeightSet set = weightsByBlock.get(block);
					if (set == null) continue;
					mapped++;
					for (int i = 0; i < set.biomes.length; i++) {
						scores[set.biomes[i]] += set.weights[i];
					}
				}
			}
		}
		return new ScanResult(scores, roles, mapped, nonAir);
	}

	/**
	 * Which biomes the section currently "looks like": every structural role the
	 * biome's weights use must be present. Overworld targets additionally need
	 * ground (grass/dirt/sand/...), so building a tree in mid-air never shifts.
	 */
	public boolean[] eligible(int sectionRoles, boolean requireGround) {
		int groundBit = requireGround ? 1 << ROLE_GROUND : 0;
		boolean[] out = new boolean[biomeList.size()];
		for (int b = 0; b < out.length; b++) {
			int required = rolesByBiome[b] | groundBit;
			out[b] = (sectionRoles & required) == required;
		}
		return out;
	}

	public int requiredRoles(int biomeIndex, boolean requireGround) {
		if (biomeIndex < 0 || biomeIndex >= rolesByBiome.length) return 0;
		return rolesByBiome[biomeIndex] | (requireGround ? 1 << ROLE_GROUND : 0);
	}

	public static int roleCount(int mask) {
		return Integer.bitCount(mask);
	}

	public static String roleNames(int mask) {
		StringBuilder builder = new StringBuilder();
		for (int role = 0; role < ROLE_COUNT; role++) {
			if ((mask & (1 << role)) == 0) continue;
			if (builder.length() > 0) builder.append(", ");
			builder.append(ROLE_NAMES[role]);
		}
		return builder.toString();
	}

	private int roleOf(Block block) {
		Integer cached = roleCache.get(block);
		if (cached != null) return cached;
		int role = classify(block);
		roleCache.put(block, role);
		return role;
	}

	private static int classify(Block block) {
		Identifier id = BuiltInRegistries.BLOCK.getKey(block);
		String name = id == null ? "" : id.getPath();
		if (name.endsWith("_log") || name.endsWith("_wood") || name.endsWith("_stem")
				|| name.endsWith("_hyphae") || name.equals("mushroom_stem") || name.equals("bamboo_block")) {
			return ROLE_TRUNK;
		}
		if (name.endsWith("_leaves")) return ROLE_LEAVES;
		if (GROUND.contains(name) || name.endsWith("_nylium")) return ROLE_GROUND;
		if (name.equals("water") || name.equals("lava")) return ROLE_LIQUID;
		if (PLANTS.contains(name) || name.endsWith("_sapling") || name.endsWith("_flower")
				|| name.endsWith("_tulip")) {
			return ROLE_PLANTS;
		}
		if (STONE.contains(name) || name.endsWith("_ore") || name.contains("terracotta")) return ROLE_STONE;
		return ROLE_DECOR;
	}

	public String nameOf(int index) {
		if (index < 0 || index >= biomeList.size()) return "<none>";
		return biomeList.get(index).unwrapKey()
				.map(key -> key.identifier().toString())
				.orElse("<unknown>");
	}

	public SectionScorer.Decision decide(float[] scores, int currentIndex, boolean[] eligible) {
		return SectionScorer.decide(scores, currentIndex, threshold, hysteresis, eligible);
	}

	public int indexOf(Holder<Biome> holder) {
		if (holder == null) return -1;
		return holder.unwrapKey().map(indexByKey::get).orElse(-1);
	}

	public Holder<Biome> biome(int index) {
		return biomeList.get(index);
	}

	public boolean isMapped(Block block) {
		return weightsByBlock.containsKey(block);
	}

	public WeightSet weightsOf(Block block) {
		return weightsByBlock.get(block);
	}

	public boolean allows(ResourceKey<Level> dimension) {
		return dimensions.isEmpty() || dimensions.contains(dimension);
	}

	public boolean enabled() {
		return enabled;
	}

	public boolean instant() {
		return instant;
	}

	public String mode() {
		return mode;
	}

	public String verticalScope() {
		return verticalScope;
	}

	public int wandMaxChunks() {
		return wandMaxChunks;
	}

	public float threshold() {
		return threshold;
	}

	public float hysteresis() {
		return hysteresis;
	}

	public int cooldownTicks() {
		return cooldownTicks;
	}

	public int maxSectionsPerTick() {
		return maxSectionsPerTick;
	}

	public int rescanIntervalTicks() {
		return rescanIntervalTicks;
	}

	public int rescanWindowTicks() {
		return rescanWindowTicks;
	}

	public int biomeCount() {
		return biomeList.size();
	}

	public int blockCount() {
		return weightsByBlock.size();
	}
}
