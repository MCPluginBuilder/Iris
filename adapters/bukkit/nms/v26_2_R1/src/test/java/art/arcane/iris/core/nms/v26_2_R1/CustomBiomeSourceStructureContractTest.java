package art.arcane.iris.core.nms.v26_2_R1;

import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.IrisDimension;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class CustomBiomeSourceStructureContractTest {
    @BeforeClass
    public static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

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
        assertTrue(source.contains("runtimeBiomeState().vanillaSpawnBiomes().get(biome.value())"));
        assertTrue(source.contains("requireGenerationLease(\n                     \"bukkit_structure_biome\""));
        assertTrue(source.contains("tryAcquireGenerationLease(\"bukkit_biomes_within\")"));
        assertTrue(source.contains("requireGenerationLease(\n                     \"bukkit_visible_biome\""));
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
        assertTrue(source.contains(
                "return super.findBiomeHorizontal(x, y, z, searchRadius, allowed, random, sampler)"));
        assertTrue(source.contains("tryAcquireGenerationLease(\"bukkit_structure_ring_biome\")"));
        assertTrue(source.contains("findNaturalSurfaceBiomeHorizontal("));
        assertTrue(source.contains("radius += quartStep"));
        assertTrue(source.contains("offsetZ += quartStep"));
        assertTrue(source.contains("offsetX += quartStep"));
        assertTrue(source.contains("random.nextInt(matches + 1) == 0"));
    }

    @Test
    public void onlyConcentricRingSuitabilityUsesTheNaturalTerrainStream() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.customBiomeSource")));
        int realResolutionStart = source.indexOf("private Holder<Biome> resolveSurfaceStructureBiomeHolder(");
        int naturalResolutionStart = source.indexOf(
                "private Holder<Biome> resolveNaturalSurfaceStructureBiomeHolder(");
        int resolutionEnd = source.indexOf("public Holder<Biome> getVisibleNoiseBiome(", naturalResolutionStart);

        assertTrue(realResolutionStart >= 0);
        assertTrue(naturalResolutionStart > realResolutionStart);
        assertTrue(resolutionEnd > naturalResolutionStart);
        String realResolution = source.substring(realResolutionStart, naturalResolutionStart);
        String naturalResolution = source.substring(naturalResolutionStart, resolutionEnd);

        assertTrue(realResolution.contains("resolveSurfaceStructureBiome(engine, blockX, blockZ)"));
        assertFalse(realResolution.contains("getNaturalTrueBiomeStream()"));
        assertTrue(naturalResolution.contains(
                "engine.getComplex().getNaturalTrueBiomeStream().get(blockX, blockZ)"));
        assertFalse(naturalResolution.contains("getTrueBiomeStream()"));
        int helperStart = source.indexOf("private static IrisBiome resolveSurfaceStructureBiome(");
        int helperEnd = source.indexOf("private static Holder<Biome> resolveBiomeHolder(", helperStart);
        String helper = source.substring(helperStart, helperEnd);
        assertTrue(helper.contains("engine.getComplex().getTrueBiomeStream().get(blockX, blockZ)"));
        assertFalse(helper.contains("getDimensionStackContext()"));
        assertFalse(helper.contains("getSurfaceBiome("));
        assertFalse(source.contains("studioBootstrapSurfaceStructureBiomeCache"));
    }

    @Test
    public void stackedCustomBiomesUseTheirOwningDimensionNamespace() {
        IrisDimension host = new IrisDimension();
        host.setLoadKey("Host");
        IrisDimension upper = new IrisDimension();
        upper.setLoadKey("Layers/Upper");

        assertEquals("layers:upper/aurora", CustomBiomeSource.customBiomeKey(upper, "Aurora"));
        assertNotEquals(
                CustomBiomeSource.customBiomeKey(host, "Aurora"),
                CustomBiomeSource.customBiomeKey(upper, "Aurora")
        );
    }

    @Test
    public void visibleBiomeResolutionSelectsStackOwnershipBeforeHostCaves() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.customBiomeSource")));
        int resolutionStart = source.indexOf("private BiomeResolution resolveBiomeResolution(");
        int resolutionEnd = source.indexOf("private BiomeResolution createBiomeResolution(", resolutionStart);

        assertTrue(resolutionStart >= 0);
        assertTrue(resolutionEnd > resolutionStart);
        String resolution = source.substring(resolutionStart, resolutionEnd);

        assertTrue(resolution.contains("resolveDimensionStackLayer("));
        assertTrue(resolution.contains("!stackLayer.terrainContext().isSelfReferencing()"));
        assertTrue(resolution.indexOf("resolveDimensionStackLayer(")
                < resolution.indexOf("boolean deepUnderground"));
        assertTrue(resolution.contains("includeDimensionStack"));
        assertTrue(source.contains("resolveBiomeResolution(x, y, z, null, false)"));
        assertTrue(source.contains("resolution.dimension, customBiome.getId()"));
    }

    @Test
    public void visibleBiomeBatchReusesTheCallersGenerationLease() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.customBiomeSource")));
        int lookupStart = source.indexOf("public Holder<Biome> getVisibleNoiseBiome(");
        int lookupEnd = source.indexOf("private GenerationSessionLease tryAcquireGenerationLease(", lookupStart);

        assertTrue(lookupStart >= 0);
        assertTrue(lookupEnd > lookupStart);
        String lookup = source.substring(lookupStart, lookupEnd);

        assertTrue(lookup.contains("requireGenerationLease(\n                     \"bukkit_visible_biome\""));
        assertTrue(lookup.contains("prepareVisibleBiomeBatch()"));
        assertTrue(lookup.contains("getVisibleNoiseBiomeWithActiveGenerationLease(x, y, z, sampler)"));
        assertTrue(lookup.indexOf("prepareVisibleBiomeBatch()")
                < lookup.indexOf("getVisibleNoiseBiomeWithActiveGenerationLease(x, y, z, sampler)"));
        assertFalse(source.contains("studioBootstrapNoiseBiomeCache"));
    }

    @Test
    public void stackBiomesAreVisibleButExcludedFromStructureReachability() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.customBiomeSource")));

        assertTrue(source.contains("return possibleBiomes(true).stream()"));
        assertTrue(source.contains("public Set<Holder<Biome>> possibleBiomes()"));
        assertTrue(source.contains("irisBiome.getDerivativeKey()"));
        assertTrue(source.contains("irisBiome.getBiomeScatter()"));
        assertTrue(source.contains("irisBiome.getBiomeSkyScatter()"));
        assertTrue(source.contains("return possibleBiomes(false)"));
        assertTrue(source.contains("for (OwnedBiome ownedBiome : getHostOwnedBiomes(engine))"));
        assertTrue(source.contains("for (OwnedBiome ownedBiome : getOwnedBiomes(engine))"));
    }

    @Test
    public void biomeLocatorSearchesTheVisibleStack() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.customBiomeSource")));
        int start = source.indexOf("public Pair<BlockPos, Holder<Biome>> findClosestBiome3d(");
        int end = source.indexOf("static int horizontalBiomeSearchQuartStep", start);
        String method = source.substring(start, end);

        assertTrue(method.contains("bukkit_locate_visible_biome"));
        assertTrue(method.contains("return super.findClosestBiome3d("));
        assertTrue(method.contains("getVisibleBiomes("));
        assertTrue(method.contains("QuartPos.toBlock(quartX), QuartPos.toBlock(quartZ)"));
        assertTrue(method.contains("resolveVisibleBiomeHolder("));
        assertFalse(method.contains("getStructureNoiseBiomeWithActiveGenerationLease("));
        assertTrue(method.indexOf("tryAcquireGenerationLease(\"bukkit_locate_visible_biome\")")
                < method.indexOf("engine.getDimensionStackContext()"));
    }

    @Test
    public void surfaceSpawnBiomeUsesTheVisibleQuartCell() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.customBiomeSource")));
        int start = source.indexOf("Holder<Biome> getVisibleSurfaceBiome(");
        int end = source.indexOf("private RegistryAccess registry()", start);
        String method = source.substring(start, end);

        assertTrue(method.contains("QuartPos.fromBlock(blockX)"));
        assertTrue(method.contains("QuartPos.toBlock(quartX)"));
        assertTrue(method.contains("QuartPos.fromBlock(internalY + engine.getWorld().minHeight())"));
        assertTrue(method.contains("stackContext.getLayout(sampleX, sampleZ).surfaceLayer()"));
        assertTrue(method.contains("getVisibleNoiseBiomeWithActiveGenerationLease("));
        assertTrue(method.contains("quartX, quartY, quartZ, null, null, cacheable"));
        assertTrue(method.indexOf("boolean cacheable = isBiomeCacheable(engine, sampleX, sampleZ)")
                < method.indexOf("Engine.hostHeight(engine, sampleX, sampleZ, true)"));
    }

    @Test
    public void temporaryNaturalBiomeAnswersAreNotMemoized() throws IOException {
        Engine temporary = engineAnsweringNaturalFallback(true, 12, -8);
        Engine stable = engineAnsweringNaturalFallback(false, 12, -8);
        assertFalse(CustomBiomeSource.isBiomeCacheable(temporary, 12, -8));
        assertTrue(CustomBiomeSource.isBiomeCacheable(stable, 12, -8));

        String source = Files.readString(Path.of(System.getProperty("iris.customBiomeSource")));
        int structureStart = source.indexOf(
                "private Holder<Biome> getStructureNoiseBiomeWithActiveGenerationLease(");
        int structureEnd = source.indexOf("@Override\n    public Pair<BlockPos, Holder<Biome>> findBiomeHorizontal(",
                structureStart);
        assertCacheGuarded(source.substring(structureStart, structureEnd), "structureBiomeCache");

        int firstVisible = source.indexOf(
                "Holder<Biome> getVisibleNoiseBiomeWithActiveGenerationLease(");
        int visibleStart = source.indexOf(
                "Holder<Biome> getVisibleNoiseBiomeWithActiveGenerationLease(", firstVisible + 1);
        int visibleEnd = source.indexOf("private boolean isBiomeCacheable(", visibleStart);
        assertCacheGuarded(source.substring(visibleStart, visibleEnd), "noiseBiomeCache");

        String predicate = source.substring(
                visibleEnd,
                source.indexOf("private GenerationSessionLease tryAcquireGenerationLease(", visibleEnd)
        );
        assertTrue(predicate.contains("QuartPos.toBlock(quartX)"));
        assertTrue(predicate.contains("QuartPos.toBlock(quartZ)"));
        assertTrue(predicate.contains("return !engine.answersFromNaturalTerrain(blockX, blockZ)"));
        assertFalse(predicate.contains("getDimensionStackContext()"));
    }

    @Test
    public void importedFeaturesCollectOnlyHostOwnedStackCells() throws IOException {
        Path biomeSource = Path.of(System.getProperty("iris.customBiomeSource"));
        String source = Files.readString(biomeSource.getParent().resolve("ImportedFeatureStage.java"));

        assertTrue(source.contains("stackContext.getLayerAt("));
        assertTrue(source.contains("layer.terrainContext().isSelfReferencing()"));
        assertTrue(source.contains("section.getBiomes().get(quartX, quartY, quartZ)"));
    }

    private static void assertCacheGuarded(String method, String cacheName) {
        int predicate = method.indexOf("boolean cacheable = isBiomeCacheable(");
        int read = method.indexOf(cacheName + ".get(", predicate);
        int readGuard = method.lastIndexOf("if (cacheable)", read);
        int bypass = method.indexOf("if (!cacheable)", read);
        int write = method.indexOf(cacheName + ".putIfAbsent(", bypass);

        assertTrue(predicate >= 0);
        assertTrue(read > predicate);
        assertTrue(readGuard >= predicate);
        assertTrue(bypass > read);
        assertTrue(write > bypass);
    }

    private static Engine engineAnsweringNaturalFallback(
            boolean naturalFallback,
            int expectedX,
            int expectedZ
    ) {
        return (Engine) Proxy.newProxyInstance(
                Engine.class.getClassLoader(),
                new Class<?>[]{Engine.class},
                (proxy, method, arguments) -> {
                    if (!method.getName().equals("answersFromNaturalTerrain")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    assertEquals(Integer.valueOf(expectedX), arguments[0]);
                    assertEquals(Integer.valueOf(expectedZ), arguments[1]);
                    return naturalFallback;
                }
        );
    }
}
