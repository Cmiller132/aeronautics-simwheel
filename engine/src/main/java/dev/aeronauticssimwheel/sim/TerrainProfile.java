package dev.aeronauticssimwheel.sim;

import java.util.Arrays;

/**
 * 1D Minecraft-style terrain for the offline driving sim: heights are piecewise
 * constant per 1 m block column (that's the world's real granularity), plus a
 * per-block material roughness rendered as 0.25 m sub-cells of deterministic
 * noise (gravel/dirt texture). Part of the headless feel harness (DESIGN.md
 * §10.5) — a stand-in for Rapier terrain until record/replay from the real game
 * exists.
 */
public final class TerrainProfile {

    private final float[] blockHeights;   // meters, per 1 m column
    private final float[] roughness;      // noise amplitude per column, meters
    private final long seed;

    private TerrainProfile(float[] blockHeights, float[] roughness, long seed) {
        this.blockHeights = blockHeights;
        this.roughness = roughness;
        this.seed = seed;
    }

    public static Builder builder(int lengthMeters, long seed) {
        return new Builder(lengthMeters, seed);
    }

    public double height(double x) {
        int block = clampIndex((int) Math.floor(x));
        double h = blockHeights[block];
        float amp = roughness[block];
        if (amp > 0f) {
            // 0.25 m sub-cells of hash noise: blocky micro-texture, not smooth
            h += amp * (hashNoise(seed, (long) Math.floor(x * 4.0)) - 0.5) * 2.0;
        }
        return h;
    }

    /** Height of the block column itself (no micro-texture) — for seam detection. */
    public double blockHeight(double x) {
        return blockHeights[clampIndex((int) Math.floor(x))];
    }

    private int clampIndex(int i) {
        return Math.max(0, Math.min(blockHeights.length - 1, i));
    }

    private static double hashNoise(long seed, long cell) {
        long h = seed ^ (cell * 0x9E3779B97F4A7C15L);
        h ^= h >>> 32;
        h *= 0xD6E8FEB86659FD93L;
        h ^= h >>> 32;
        return (h >>> 11) / (double) (1L << 53);
    }

    public static final class Builder {
        private final float[] heights;
        private final float[] rough;
        private final long seed;

        private Builder(int lengthMeters, long seed) {
            this.heights = new float[lengthMeters];
            this.rough = new float[lengthMeters];
            this.seed = seed;
        }

        /** Random block-height steps (whole terrain stays 1 m-quantized in x). */
        public Builder bumpyBlocks(int fromM, int toM, float stepChance, float stepHeight) {
            java.util.Random rng = new java.util.Random(seed ^ 0xB10C5L);
            float h = heights[Math.max(0, fromM - 1)];
            for (int i = fromM; i < toM; i++) {
                if (rng.nextFloat() < stepChance) {
                    h += (rng.nextBoolean() ? 1 : -1) * stepHeight;
                }
                heights[i] = h;
            }
            // settle back to the entry height so later sections stay comparable
            if (toM < heights.length) {
                Arrays.fill(heights, toM, heights.length, heights[Math.max(0, fromM - 1)]);
            }
            return this;
        }

        /** Material roughness (gravel/dirt micro-texture) over a range. */
        public Builder rough(int fromM, int toM, float amplitude) {
            Arrays.fill(rough, fromM, Math.min(toM, rough.length), amplitude);
            return this;
        }

        /** A curb: a single sustained step up at x (e.g. +0.5 m half-slab curb). */
        public Builder curb(int atM, int lengthM, float heightM) {
            float base = heights[Math.max(0, atM - 1)];
            for (int i = atM; i < Math.min(atM + lengthM, heights.length); i++) {
                heights[i] = base + heightM;
            }
            return this;
        }

        public TerrainProfile build() {
            return new TerrainProfile(heights, rough, seed);
        }
    }
}
