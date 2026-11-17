package com.biomeshift.util;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Identity of one 16x16x16 section in one dimension. */
public record SectionKey(ResourceKey<Level> dimension, long section) {
}
