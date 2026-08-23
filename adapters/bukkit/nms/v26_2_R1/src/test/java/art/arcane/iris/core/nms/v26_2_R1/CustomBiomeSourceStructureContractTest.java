package art.arcane.iris.core.nms.v26_2_R1;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CustomBiomeSourceStructureContractTest {
    @Test
    public void nativeStructuresUseTerrainSafeDerivativeAtEveryBiomeBoundary() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.customBiomeSource")));

        assertTrue(source.contains("resolveBiomeHolder(registry, i.getStructureDerivativeKey())"));
        assertTrue(source.contains("resolveBiomeHolder(biomeRegistry, irisBiome.getStructureDerivativeKey())"));
        assertTrue(source.contains("resolution.irisBiome.getStructureDerivativeKey()"));
        assertFalse(source.contains("resolution.irisBiome.getVanillaDerivative()"));
    }

    @Test
    public void biomeRuntimeReadsAreGenerationLeased() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.customBiomeSource")));

        assertTrue(source.contains("tryAcquireGenerationLease(\"bukkit_possible_biomes\")"));
        assertTrue(source.contains("tryAcquireGenerationLease(\"bukkit_spawn_biome\")"));
        assertFalse(source.contains("Iris spawn biome lookup was rejected during an engine transition"));
        assertFalse(source.contains("Iris spawn biome lookup has no active engine runtime"));
        assertTrue(source.contains("vanillaSpawnBiomes.get(biome.value())"));
        assertTrue(source.contains("tryAcquireGenerationLease(\"bukkit_structure_biome\")"));
        assertTrue(source.contains("tryAcquireGenerationLease(\"bukkit_biomes_within\")"));
        assertTrue(source.contains("tryAcquireGenerationLease(\"bukkit_visible_biome\")"));
        assertTrue(source.contains("if (engine.isClosed())"));
        assertTrue(source.contains("engine.isClosing() || e.isExpectedTeardown()"));
        assertTrue(source.contains("catch (GenerationSessionException e)"));
        assertTrue(source.contains("e.isExpectedTeardown()"));
    }

    @Test
    public void strongholdRingSearchSamplesOneQuartColumnPerChunk() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.customBiomeSource")));

        assertTrue(source.contains("private static final int STRONGHOLD_RING_SEARCH_Y = 0"));
        assertTrue(source.contains("private static final int STRONGHOLD_RING_SEARCH_RADIUS = 112"));
        assertTrue(source.contains("private static final int STRONGHOLD_RING_SEARCH_QUART_STEP = 4"));
        assertTrue(source.contains("x, y, z, searchRadius, quartStep, allowed, random, false, sampler"));
        assertTrue(source.contains(
                "return super.findBiomeHorizontal(x, y, z, searchRadius, allowed, random, sampler)"));
    }

    @Test
    public void standardStudioBootstrapUsesNaturalSurfaceBiomesWithAnIsolatedCache() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.customBiomeSource")));
        int lookupStart = source.indexOf("private Holder<Biome> getSurfaceStructureBiomeHolder(");
        int lookupEnd = source.indexOf("private boolean isStandardStudioEntryBootstrapActive()", lookupStart);
        int modeEnd = source.indexOf("private boolean isGuaranteedSurfaceBiome(", lookupEnd);
        int resolutionStart = source.indexOf("private Holder<Biome> resolveSurfaceStructureBiomeHolder(", modeEnd);
        int resolutionEnd = source.indexOf("public Holder<Biome> getVisibleNoiseBiome(", resolutionStart);

        assertTrue(lookupStart >= 0);
        assertTrue(lookupEnd > lookupStart);
        assertTrue(modeEnd > lookupEnd);
        assertTrue(resolutionStart > modeEnd);
        assertTrue(resolutionEnd > resolutionStart);

        String lookup = source.substring(lookupStart, lookupEnd);
        String mode = source.substring(lookupEnd, modeEnd);
        String resolution = source.substring(resolutionStart, resolutionEnd);

        assertTrue(source.contains("studioBootstrapSurfaceStructureBiomeCache = new ConcurrentHashMap<>()"));
        assertTrue(lookup.contains("? studioBootstrapSurfaceStructureBiomeCache"));
        assertTrue(lookup.contains(": surfaceStructureBiomeCache"));
        assertTrue(lookup.contains("resolveSurfaceStructureBiomeHolder(x, z, studioEntryBootstrap)"));
        assertTrue(mode.contains("platformGenerator.isStudioEntryBootstrapActive()"));
        assertTrue(mode.contains("platformGenerator.isSyntheticStudioEntryChunk("));
        assertTrue(mode.contains("StudioEntryChunkGenerator.ENTRY_CHUNK_X"));
        assertTrue(mode.contains("StudioEntryChunkGenerator.ENTRY_CHUNK_Z"));
        assertFalse(mode.contains("engine.isStudio"));
        assertTrue(resolution.contains("? engine.getComplex().getNaturalTrueBiomeStream().get(blockX, blockZ)"));
        assertTrue(resolution.contains(": engine.getComplex().getTrueBiomeStream().get(blockX, blockZ)"));
    }

    @Test
    public void standardStudioBootstrapVisibleBiomesBypassRiversWithAnIsolatedCache() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.customBiomeSource")));
        int lookupStart = source.indexOf("public Holder<Biome> getVisibleNoiseBiome(");
        int lookupEnd = source.indexOf("private GenerationSessionLease tryAcquireGenerationLease(", lookupStart);
        int resolutionStart = source.indexOf(
                "private BiomeResolution resolveBiomeResolution(int x, int y, int z, boolean studioEntryBootstrap)");
        int naturalStart = source.indexOf(
                "private BiomeResolution resolveNaturalVisibleBiomeResolution(", resolutionStart);
        int naturalEnd = source.indexOf("private BiomeResolution createBiomeResolution(", naturalStart);

        assertTrue(lookupStart >= 0);
        assertTrue(lookupEnd > lookupStart);
        assertTrue(resolutionStart >= 0);
        assertTrue(naturalStart > resolutionStart);
        assertTrue(naturalEnd > naturalStart);

        String lookup = source.substring(lookupStart, lookupEnd);
        String resolution = source.substring(resolutionStart, naturalStart);
        String natural = source.substring(naturalStart, naturalEnd);

        assertTrue(source.contains("studioBootstrapNoiseBiomeCache = new ConcurrentHashMap<>()"));
        assertTrue(lookup.contains("? studioBootstrapNoiseBiomeCache"));
        assertTrue(lookup.contains(": noiseBiomeCache"));
        assertTrue(lookup.contains("resolveVisibleBiomeHolder(x, y, z, studioEntryBootstrap)"));
        assertTrue(resolution.contains("if (studioEntryBootstrap)"));
        assertTrue(resolution.contains("return resolveNaturalVisibleBiomeResolution(blockX, blockY, blockZ);"));
        assertTrue(resolution.contains("engine.getComplex().getHeightStream()"));
        assertTrue(resolution.contains("engine.getCaveBiome(blockX, internalY, blockZ)"));
        assertTrue(resolution.contains("engine.getComplex().getTrueBiomeStream()"));
        assertTrue(natural.contains("engine.getComplex().getNaturalTrueBiomeStream()"));
        assertFalse(natural.contains("getTrueBiomeStream()"));
        assertFalse(natural.contains("getHeightStream()"));
        assertFalse(natural.contains("getCaveBiome("));
    }
}
