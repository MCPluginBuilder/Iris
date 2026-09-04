package art.arcane.iris.engine.history;

import art.arcane.iris.engine.IrisComplex;
import art.arcane.iris.engine.UpperDimensionContext;
import art.arcane.iris.engine.IrisEngine;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisBiomeCustom;
import art.arcane.volmlib.util.math.RNG;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

public final class IrisBoundarySignatureSampler implements GenerationBoundarySignatureSampler {
    public static final IrisBoundarySignatureSampler INSTANCE = new IrisBoundarySignatureSampler();

    static final int VERTICAL_SAMPLE_STEP = 4;
    private IrisBoundarySignatureSampler() {
    }

    @Override
    public TerrainBoundarySignature sample(IrisEngine engine, int blockX, int blockZ) throws IOException {
        IrisEngine requiredEngine = Objects.requireNonNull(engine, "Iris engine");
        try {
            IrisComplex complex = Objects.requireNonNull(requiredEngine.getComplex(), "Iris complex");
            int height = requiredEngine.getHeight();
            if (height <= 0) {
                throw new IOException("Iris boundary sampling requires a positive world height.");
            }

            int minimumY = requiredEngine.getMinHeight();
            int terrainHeight = clampHeight(
                    (int) Math.round(complex.getHeightStream().getDouble(blockX, blockZ)),
                    height
            );
            int fluidHeight = clampHeight(
                    (int) Math.round(complex.getRiverWaterSurfaceStream().getDouble(blockX, blockZ)),
                    height
            );
            OptionalInt fluid = fluidHeight > terrainHeight
                    ? OptionalInt.of(fluidHeight)
                    : OptionalInt.empty();
            int sampleCount = Math.ceilDiv(height, VERTICAL_SAMPLE_STEP);
            TerrainBoundarySignature.VerticalLayout layout = new TerrainBoundarySignature.VerticalLayout(
                    minimumY,
                    VERTICAL_SAMPLE_STEP,
                    sampleCount
            );
            short[] biomeIndices = new short[sampleCount];
            LinkedHashMap<String, Short> paletteIndices = new LinkedHashMap<>();

            for (int sampleIndex = 0; sampleIndex < sampleCount; sampleIndex++) {
                int worldY = layout.sampleY(sampleIndex);
                int internalY = worldY - minimumY;
                String biomeKey = physicalBiomeKey(
                        requiredEngine,
                        complex,
                        blockX,
                        worldY,
                        blockZ,
                        internalY,
                        terrainHeight
                );
                biomeIndices[sampleIndex] = paletteIndex(paletteIndices, biomeKey);
            }

            int visibleSurfaceHeight = Math.max(terrainHeight, fluidHeight);
            UpperDimensionContext upperContext = requiredEngine.getUpperContext();
            int upperDepth = upperContext == null ? 0
                    : height - 1 - upperContext.getEffectiveSurfaceY(blockX, blockZ);
            return new TerrainBoundarySignature(
                    new TerrainBoundarySignature.Column(
                            blockX,
                            blockZ,
                            visibleSurfaceHeight,
                            terrainHeight,
                            fluid,
                            upperDepth > 0 ? OptionalInt.of(upperDepth) : OptionalInt.empty()
                    ),
                    new TerrainBoundarySignature.Samples(
                            layout,
                            new TerrainBoundarySignature.BiomeEncoding(
                                    List.copyOf(paletteIndices.keySet()),
                                    biomeIndices
                            )
                    )
            );
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("Unable to sample Iris terrain boundary at "
                    + blockX + "," + blockZ + ".", exception);
        }
    }

    private static int clampHeight(int value, int height) {
        return Math.max(0, Math.min(height - 1, value));
    }

    private static short paletteIndex(Map<String, Short> indices, String biomeKey) {
        Short existing = indices.get(biomeKey);
        if (existing != null) {
            return existing;
        }
        int next = indices.size();
        if (next > Short.MAX_VALUE) {
            throw new IllegalStateException("Iris boundary biome palette exceeds compact index capacity.");
        }
        short index = (short) next;
        indices.put(biomeKey, index);
        return index;
    }

    private static String physicalBiomeKey(
            IrisEngine engine,
            IrisComplex complex,
            int blockX,
            int worldY,
            int blockZ,
            int internalY,
            int terrainHeight
    ) {
        String historicalKey = complex.historicalPhysicalBiomeKeyAt(blockX, worldY, blockZ).orElse(null);
        if (historicalKey != null) {
            return historicalKey;
        }
        int caveSwitchY = Math.max(-8 - engine.getMinHeight(), 40);
        boolean underground = internalY <= caveSwitchY && internalY <= terrainHeight - 8;
        IrisBiome biome = underground
                ? engine.getCaveBiome(blockX, internalY, blockZ)
                : complex.getTrueBiomeStream().get(blockX, blockZ);
        if (biome == null && underground) {
            biome = complex.getTrueBiomeStream().get(blockX, blockZ);
        }
        if (biome == null) {
            return "minecraft:plains";
        }

        RNG rng = new RNG(engine.getWorld().getRawWorldSeed()
                ^ ((long) blockX * 341873128712L)
                ^ ((long) worldY * 132897987541L)
                ^ ((long) blockZ * 42317861L));
        if (biome.isCustom()) {
            IrisBiomeCustom custom = biome.getCustomBiome(rng, engine, blockX, worldY, blockZ);
            return custom == null
                    ? "minecraft:plains"
                    : engine.getData().customBiomeResourceKey(engine.getDimension(), custom);
        }
        String key = underground
                ? biome.getGroundBiomeKey(rng, engine, blockX, worldY, blockZ)
                : biome.getSkyBiomeKey(rng, engine, blockX, worldY, blockZ);
        return key == null || key.isBlank() ? "minecraft:plains" : key;
    }
}
