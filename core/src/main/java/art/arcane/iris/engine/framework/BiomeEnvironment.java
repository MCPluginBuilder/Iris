package art.arcane.iris.engine.framework;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisRegion;

import java.util.Objects;

public record BiomeEnvironment(long activationId, IrisBiome biome, IrisRegion region,
                               IrisDimension dimension, IrisData data) {
    public BiomeEnvironment {
        if (activationId < 0) {
            throw new IllegalArgumentException("Biome activation cannot be negative.");
        }
        Objects.requireNonNull(biome, "biome");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(data, "data");
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
