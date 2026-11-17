package com.biomeshift.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;


/**
 * Renders {@code config/biomeshift.json} with explanatory comments. The parser is
 * lenient, so {@code //} comments survive hand-editing and a regenerated file is
 * still valid input.
 */
public final class ConfigTemplate {
	private static final Gson GSON = new GsonBuilder()
			.disableHtmlEscaping()
			.setPrettyPrinting()
			.create();

	private ConfigTemplate() {
	}

	public static String render(ConfigData data) {
		StringBuilder out = new StringBuilder();
		out.append("// BiomeShift configuration\n");
		out.append("//\n");
		out.append("// Created on first start and never rewritten by the mod afterwards. Delete this file\n");
		out.append("// to restore the generated defaults. Comments (//) are allowed and preserved.\n");
		out.append("{\n");
		line(out, "enabled", data.enabled,
				"Master switch. false disables every trigger and scan.");
		line(out, "mode", data.mode,
				"\"threshold\" = a section must build up enough score; \"instant\" = the placed block's best biome wins at once.");
		line(out, "threshold", data.threshold,
				"Score a biome needs before the section may change.");
		line(out, "hysteresis", data.hysteresis,
				"Extra score the current biome defends, so neighbours cannot flip-flop.");
		line(out, "scope", data.scope,
				"Only \"section\" is implemented; reserved for future scopes.");
		line(out, "verticalScope", data.verticalScope,
				"How far a flip reaches: \"column\" (whole chunk column, default), \"above\" (section and everything above) or \"section\" (16-block box only).");
		line(out, "wandMaxChunks", data.wandMaxChunks,
				"Largest wand selection (in chunks) a single right-click may sync.");
		line(out, "cooldownTicks", data.cooldownTicks,
				"Minimum ticks between two biome resends for the same chunk.");
		line(out, "maxSectionsPerTick", data.maxSectionsPerTick,
				"Sections scanned per server tick; lower is smoother but slower.");
		line(out, "rescanIntervalTicks", data.rescanIntervalTicks,
				"How often a warm section (player planted a sapling) is polled, in ticks.");
		line(out, "rescanWindowTicks", data.rescanWindowTicks,
				"How long a planted sapling keeps its section warm, in ticks (72000 = 1 hour).");
		line(out, "dimensions", data.dimensions,
				"Dimensions where the mod runs. Add \"minecraft:the_nether\" and/or \"minecraft:the_end\" to enable them.");
		out.append("  // biome id -> block id -> weight, generated from Minecraft worldgen plus\n");
		out.append("  // tools/weights_overlay.json (README: \"Where the weights come from\"). Blocks below\n");
		out.append("  // weight 4 may score but never demand a trait role; the trait gate ignores them.\n");
		out.append("  \"weights\": ");
		out.append(indent(GSON.toJson(data.weights)));
		out.append('\n');
		out.append("}\n");
		return out.toString();
	}

	private static void line(StringBuilder out, String key, Object value, String comment) {
		out.append("  // ").append(comment).append('\n');
		out.append("  \"").append(key).append("\": ").append(indent(GSON.toJson(value))).append(",\n");
	}

	/** Re-indents a pretty-printed block so it lines up under its key. */
	private static String indent(String json) {
		return json.replace("\n", "\n  ");
	}
}
