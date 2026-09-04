package art.arcane.iris.modded;

import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisBiomeCustom;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisRange;
import art.arcane.iris.engine.object.InferredType;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.mantle.flag.MantleFlag;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class IrisModdedStructureParityTest {
    @BeforeClass
    public static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void surfaceBiomeFastPathBeginsAboveTheCaveSwitch() {
        assertFalse(IrisModdedBiomeSource.isGuaranteedSurfaceBiome(-2, -256));
        assertTrue(IrisModdedBiomeSource.isGuaranteedSurfaceBiome(-1, -256));
        assertFalse(IrisModdedBiomeSource.isGuaranteedSurfaceBiome(10, 0));
        assertTrue(IrisModdedBiomeSource.isGuaranteedSurfaceBiome(11, 0));
    }

    @Test
    public void visibleCaveBiomeBeginsEightBlocksBelowTheSurface() {
        assertFalse(IrisModdedBiomeSource.isUnderground(93, 100));
        assertTrue(IrisModdedBiomeSource.isUnderground(92, 100));
        assertTrue(IrisModdedBiomeSource.isUnderground(-20, 100));
    }

    @Test
    public void monumentBiomeCubeUsesSurfaceBiomesAtShiftedSeaLevel() {
        assertTrue(IrisModdedBiomeSource.isMonumentSurfaceBiomeQuery(50, 29, -256, 306));
        assertFalse(IrisModdedBiomeSource.isMonumentSurfaceBiomeQuery(51, 29, -256, 306));
        assertFalse(IrisModdedBiomeSource.isMonumentSurfaceBiomeQuery(50, 28, -256, 306));
    }

    @Test
    public void strongholdRingSearchSamplesOneQuartColumnPerChunk() throws IOException {
        assertEquals(4, IrisModdedBiomeSource.horizontalBiomeSearchQuartStep(0, 112));
        assertEquals(1, IrisModdedBiomeSource.horizontalBiomeSearchQuartStep(1, 112));
        assertEquals(1, IrisModdedBiomeSource.horizontalBiomeSearchQuartStep(0, 111));
        String source = Files.readString(Path.of(
                System.getProperty("iris.moddedCommonSources"),
                "art/arcane/iris/modded/IrisModdedBiomeSource.java"));
        assertTrue(source.contains("x, y, z, searchRadius, quartStep, allowed, random, false, sampler"));
        assertTrue(source.contains(
                "return super.findBiomeHorizontal(x, y, z, searchRadius, allowed, random, sampler)"));
    }

    @Test
    public void spawnHeightMatchesPaperFixedSpawnClamp() {
        assertEquals(96, ModdedDimensionMetadata.clampSpawnHeight(-64, 384));
        assertEquals(96, ModdedDimensionMetadata.clampSpawnHeight(0, 128));
        assertEquals(88, ModdedDimensionMetadata.clampSpawnHeight(80, 10));
        assertEquals(101, ModdedDimensionMetadata.clampSpawnHeight(100, 20));
    }

    @Test
    public void freshAndStudioWorldsReconcileToOriginWhileCustomSpawnsRemain() {
        assertTrue(ModdedEngineBootstrap.shouldReconcileSpawn(true, false, 120, -64));
        assertTrue(ModdedEngineBootstrap.shouldReconcileSpawn(false, true, 120, -64));
        assertTrue(ModdedEngineBootstrap.shouldReconcileSpawn(false, false, 0, 0));
        assertFalse(ModdedEngineBootstrap.shouldReconcileSpawn(false, false, 1, 0));
        assertFalse(ModdedEngineBootstrap.shouldReconcileSpawn(false, false, 0, -1));
    }

    @Test
    public void reconciledSpawnUsesOriginAndClampedSurfaceHeight() {
        assertEquals(new BlockPos(0, 73, 0), ModdedEngineBootstrap.reconciledSpawnPosition(73, -64, 384));
        assertEquals(new BlockPos(0, -63, 0), ModdedEngineBootstrap.reconciledSpawnPosition(-100, -64, 384));
        assertEquals(new BlockPos(0, 318, 0), ModdedEngineBootstrap.reconciledSpawnPosition(400, -64, 384));
    }

    @Test
    public void biomeResolutionUsesRawPlatformSeedFormula() {
        long worldSeed = 998877665544L;
        int blockX = -124;
        int blockY = 48;
        int blockZ = 712;
        long expected = worldSeed
                ^ ((long) blockX * 341873128712L)
                ^ ((long) blockY * 132897987541L)
                ^ ((long) blockZ * 42317861L);

        assertEquals(expected, IrisModdedBiomeSource.biomeResolutionSeed(worldSeed, blockX, blockY, blockZ));
    }

    @Test
    public void stackedCustomBiomesUseTheirOwningDimensionNamespace() {
        IrisDimension host = new IrisDimension();
        host.setLoadKey("Host");
        IrisDimension upper = new IrisDimension();
        upper.setLoadKey("Upper");

        assertEquals(
                ModdedWorldgenIds.biomeRef("pack", "Upper", "Aurora"),
                IrisModdedBiomeSource.customBiomeRef("pack", upper, "Aurora")
        );
        assertFalse(IrisModdedBiomeSource.customBiomeRef("pack", host, "Aurora")
                .equals(IrisModdedBiomeSource.customBiomeRef("pack", upper, "Aurora")));
    }

    @Test
    public void visibleBiomeResolutionSelectsStackOwnershipBeforeHostCaves() throws IOException {
        String source = Files.readString(Path.of(
                System.getProperty("iris.moddedCommonSources"),
                "art/arcane/iris/modded/IrisModdedBiomeSource.java"));
        int resolutionStart = source.indexOf("private BiomeResolution resolveBiomeResolution(");
        int resolutionEnd = source.indexOf("private Holder<Biome> resolveRequiredStructureBiome(", resolutionStart);

        assertTrue(resolutionStart >= 0);
        assertTrue(resolutionEnd > resolutionStart);
        String resolution = source.substring(resolutionStart, resolutionEnd);

        assertTrue(resolution.contains("resolveDimensionStackLayer("));
        assertTrue(resolution.contains("!stackLayer.terrainContext().isSelfReferencing()"));
        assertTrue(resolution.indexOf("resolveDimensionStackLayer(")
                < resolution.indexOf("boolean underground"));
        assertTrue(resolution.contains("includeDimensionStack"));
        assertTrue(resolution.contains(
                "return resolveBiomeResolution(engine, quartX, quartY, quartZ, false)"));
        assertTrue(source.contains("resolution.packName(), resolution.dimension(), customBiome.getId()"));
        int helperStart = source.indexOf("private static IrisBiome resolveSurfaceStructureBiome(");
        int helperEnd = source.indexOf("private static Set<String> registeredBiomeKeys(", helperStart);
        String helper = source.substring(helperStart, helperEnd);
        assertTrue(helper.contains("engine.getComplex().getTrueBiomeStream().get(blockX, blockZ)"));
        assertFalse(helper.contains("getDimensionStackContext()"));
        assertFalse(helper.contains("getSurfaceBiome("));
    }

    @Test
    public void stackBiomesAreVisibleButExcludedFromStructureReachability() throws IOException {
        String source = Files.readString(Path.of(
                System.getProperty("iris.moddedCommonSources"),
                "art/arcane/iris/modded/IrisModdedBiomeSource.java"));

        assertTrue(source.contains("return biomeKeySets().structureRequired()"));
        assertTrue(source.contains("generatedBiomeKeys.addAll(keys.visibleRequired())"));
        assertTrue(source.contains("collectStructureBiomeKeys("));
        assertTrue(source.contains("dimension.getReachableBiomes(() -> data)"));
    }

    @Test
    public void biomeLocatorSearchesTheVisibleStack() throws IOException {
        String source = Files.readString(Path.of(
                System.getProperty("iris.moddedCommonSources"),
                "art/arcane/iris/modded/IrisModdedBiomeSource.java"));
        int start = source.indexOf("public Pair<BlockPos, Holder<Biome>> findClosestBiome3d(");
        int end = source.indexOf("private Holder<Biome> getNoiseBiome(", start);
        String method = source.substring(start, end);

        assertTrue(method.contains("modded_locate_visible_biome"));
        assertTrue(method.contains("return super.findClosestBiome3d("));
        assertTrue(method.contains("collectVisibleBiomeHolders()"));
        assertTrue(method.contains("QuartPos.toBlock(quartX), QuartPos.toBlock(quartZ)"));
        assertTrue(method.contains("resolveVisibleBiome("));
        assertFalse(method.contains("Holder<Biome> structureBiome = getNoiseBiome("));
        assertTrue(method.indexOf("tryAcquireGenerationLease(engine, \"modded_locate_visible_biome\")")
                < method.indexOf("engine.getDimensionStackContext()"));
    }

    @Test
    public void surfaceSpawnBiomeUsesTheVisibleQuartCell() throws IOException {
        String source = Files.readString(Path.of(
                System.getProperty("iris.moddedCommonSources"),
                "art/arcane/iris/modded/IrisModdedBiomeSource.java"));
        int start = source.indexOf("Holder<Biome> getVisibleSurfaceBiome(");
        int end = source.indexOf("boolean isStructureReachable(", start);
        String method = source.substring(start, end);

        assertTrue(method.contains("QuartPos.fromBlock(blockX)"));
        assertTrue(method.contains("QuartPos.toBlock(quartX)"));
        assertTrue(method.contains("QuartPos.fromBlock(internalY + engine.getMinHeight())"));
        assertTrue(method.contains("stackContext.getLayout(sampleX, sampleZ).surfaceLayer()"));
        assertTrue(method.contains("visibleBiomeCache"));
        assertTrue(method.contains("isBiomeCacheable(engine, sampleX, sampleZ)"));
        assertTrue(method.indexOf("boolean cacheable = isBiomeCacheable(engine, sampleX, sampleZ)")
                < method.indexOf("Engine.hostHeight(engine, sampleX, sampleZ, true)"));
        assertCacheGuarded(method, "visibleBiomeCache");
    }

    @Test
    public void temporaryNaturalBiomeAnswersAreNotMemoized() throws IOException {
        Engine engine = mock(Engine.class);
        when(engine.answersFromNaturalTerrain(12, -8)).thenReturn(true);
        assertFalse(IrisModdedBiomeSource.isBiomeCacheable(engine, 12, -8));
        when(engine.answersFromNaturalTerrain(12, -8)).thenReturn(false);
        assertTrue(IrisModdedBiomeSource.isBiomeCacheable(engine, 12, -8));

        String source = Files.readString(Path.of(
                System.getProperty("iris.moddedCommonSources"),
                "art/arcane/iris/modded/IrisModdedBiomeSource.java"));
        int structureStart = source.indexOf("private Holder<Biome> getNoiseBiome(Engine engine");
        int structureEnd = source.indexOf("Holder<Biome> getVisibleNoiseBiome(", structureStart);
        String structure = source.substring(structureStart, structureEnd);
        assertTrue(structure.contains("QuartPos.toBlock(quartX)"));
        assertTrue(structure.contains("QuartPos.toBlock(quartZ)"));
        assertCacheGuarded(structure, "structureBiomeCache");

        int visibleStart = source.indexOf(
                "private Holder<Biome> getVisibleNoiseBiomeWithActiveGenerationLease(");
        int visibleEnd = source.indexOf("Holder<Biome> getVisibleSurfaceBiome(", visibleStart);
        String visible = source.substring(visibleStart, visibleEnd);
        assertTrue(visible.contains("QuartPos.toBlock(quartX)"));
        assertTrue(visible.contains("QuartPos.toBlock(quartZ)"));
        assertCacheGuarded(visible, "visibleBiomeCache");

        int predicateStart = source.indexOf("static boolean isBiomeCacheable(");
        int predicateEnd = source.indexOf("static boolean isMonumentSurfaceBiomeQuery(", predicateStart);
        String predicate = source.substring(predicateStart, predicateEnd);
        assertTrue(predicate.contains("return !engine.answersFromNaturalTerrain(blockX, blockZ)"));
        assertFalse(predicate.contains("getDimensionStackContext()"));
    }

    @Test
    public void importedFeaturesCollectOnlyHostOwnedStackCells() throws IOException {
        String source = Files.readString(Path.of(
                System.getProperty("iris.moddedCommonSources"),
                "art/arcane/iris/modded/ModdedImportedFeatureStage.java"));

        assertTrue(source.contains("stackContext.getLayerAt("));
        assertTrue(source.contains("layer.terrainContext().isSelfReferencing()"));
        assertTrue(source.contains("section.getBiomes().get(quartX, quartY, quartZ)"));
    }

    @Test
    public void configuredBiomeKeysContainOnlyPackDerivativesAndCustomBiomes() {
        IrisBiome ocean = new IrisBiome()
                .setDerivative("minecraft:desert")
                .setVanillaDerivative("minecraft:deep_ocean");
        IrisBiome custom = new IrisBiome()
                .setDerivative("forest")
                .setCustomDerivitives(new KList<>(new IrisBiomeCustom().setId("Aurora")));
        IrisBiome shore = new IrisBiome()
                .setVanillaDerivative("minecraft:desert")
                .setInferredType(InferredType.SHORE);
        IrisBiome unsafeSea = new IrisBiome()
                .setVanillaDerivative("minecraft:plains")
                .setInferredType(InferredType.SEA);

        Set<String> keys = ModdedDimensionMetadata.collectConfiguredBiomeKeys(
                List.of(ocean, custom, shore, unsafeSea), "OverWorld");

        assertEquals(Set.of("minecraft:deep_ocean", "minecraft:forest", "minecraft:beach",
                "minecraft:the_void", "overworld:aurora"), keys);
        assertFalse(keys.contains("minecraft:desert"));
        assertFalse(keys.contains("minecraft:plains"));

        Set<String> recursiveKeys = ModdedDimensionMetadata.collectConfiguredBiomeKeys(
                List.of(custom), "Layers/Sky");
        assertTrue(recursiveKeys.contains("layers:sky/aurora"));
    }

    @Test
    public void biomeWriterDerivativeFallbackMatchesCanonicalRecursiveKeys() throws IOException {
        String source = Files.readString(Path.of(
                System.getProperty("iris.moddedCommonSources"),
                "art/arcane/iris/modded/ModdedBiomeWriter.java"));

        assertTrue(source.contains(
                "key.equalsIgnoreCase(dimension.getCustomBiomeKey(custom.getId()))"));
        assertFalse(source.contains("String dimensionLoadKey = key.substring(0, colon)"));
    }

    @Test
    public void structureStateRejectsBiomesOutsideThePackContract() {
        Set<String> generated = Set.of("minecraft:deep_ocean", "minecraft:dark_forest");

        assertTrue(IrisModdedBiomeSource.isGeneratedBiomeKey("minecraft:deep_ocean", generated));
        assertTrue(IrisModdedBiomeSource.isGeneratedBiomeKey("MINECRAFT:DARK_FOREST", generated));
        assertFalse(IrisModdedBiomeSource.isGeneratedBiomeKey("minecraft:desert", generated));
        assertFalse(IrisModdedBiomeSource.isGeneratedBiomeKey(null, generated));
    }

    @Test
    public void structureBiomeContractRejectsAnEmptyConfiguredSet() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> IrisModdedBiomeSource.requireConfiguredStructureBiomeKeys(Set.of()));

        assertEquals("Iris has no configured structure biomes", error.getMessage());
    }

    @Test
    public void structureBiomeContractPreservesConfiguredKeysDuringBootstrap() {
        Set<String> configured = Set.of("minecraft:deep_ocean", "minecraft:dark_forest");

        assertSame(configured, IrisModdedBiomeSource.requireConfiguredStructureBiomeKeys(configured));
    }

    @Test
    public void liveReplacementRequiresTheSameStructureBiomeUniverse() {
        IrisModdedChunkGenerator.requireStructureBiomeUniverseCompatible(
                Set.of("minecraft:plains", "minecraft:beach"),
                Set.of("minecraft:beach", "minecraft:plains"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> IrisModdedChunkGenerator.requireStructureBiomeUniverseCompatible(
                        Set.of("minecraft:plains"), Set.of("minecraft:desert")));

        assertTrue(error.getMessage().contains("Restart the server"));
    }

    @Test
    public void configuredDimensionMetadataIsExactBeforeEngineBinding() {
        IrisDimension dimension = new IrisDimension()
                .setDimensionHeight(new IrisRange(-256, 512))
                .setFluidHeight(50);
        dimension.setLoadKey("bootstrap_contract");

        ModdedDimensionMetadata.DimensionMetadata metadata =
                ModdedDimensionMetadata.dimensionMetadata(dimension);

        assertEquals(-256, metadata.minY());
        assertEquals(512, metadata.maxY());
        assertEquals(768, metadata.depth());
        assertEquals(50, metadata.seaLevel());
    }

    @Test
    public void structureRingWorkersWaitWithoutBlockingLifecycleBinding() throws Exception {
        ModdedEngineBinding<String> binding =
                new ModdedEngineBinding<>(5L, TimeUnit.SECONDS);
        String exactEngine = "exact-engine";
        CountDownLatch workerStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> ringWorker = executor.submit(() -> {
                workerStarted.countDown();
                return binding.await("overworld:overworld");
            });

            assertTrue(workerStarted.await(1L, TimeUnit.SECONDS));
            assertFalse(ringWorker.isDone());
            binding.complete(exactEngine);

            assertSame(exactEngine, ringWorker.get(1L, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void structureRingBindingPropagatesBootstrapFailure() {
        ModdedEngineBinding<String> binding =
                new ModdedEngineBinding<>(1L, TimeUnit.SECONDS);
        IllegalArgumentException failure = new IllegalArgumentException("broken pack");
        binding.fail(failure);

        try {
            binding.await("overworld:overworld");
        } catch (IllegalStateException error) {
            assertSame(failure, error.getCause());
            return;
        }
        throw new AssertionError("Expected failed engine binding to propagate");
    }

    @Test
    public void structureBiomeBootstrapAllowsOnlyPendingBindingsToUseMetadata() {
        ModdedEngineBinding<String> binding =
                new ModdedEngineBinding<>(1L, TimeUnit.SECONDS);

        binding.throwIfFailed("overworld:overworld");
    }

    @Test
    public void structureBiomeBootstrapPropagatesBindingFailure() {
        ModdedEngineBinding<String> binding =
                new ModdedEngineBinding<>(1L, TimeUnit.SECONDS);
        IllegalArgumentException failure = new IllegalArgumentException("broken pack");
        binding.fail(failure);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> binding.throwIfFailed("overworld:overworld"));

        assertSame(failure, error.getCause());
    }

    @Test
    public void initialEntitySpawnsUseThePaperCompletionMarker() {
        assertSame(MantleFlag.INITIAL_SPAWNED_MARKER, ModdedWorldManager.INITIAL_SPAWN_COMPLETION_FLAG);
    }

    private static void assertCacheGuarded(String method, String cacheName) {
        int predicate = method.indexOf("boolean cacheable = isBiomeCacheable(");
        int cache = method.indexOf("BiomeHolderTable cache = " + cacheName, predicate);
        int read = method.indexOf("cache.get(", predicate);
        int readGuard = method.lastIndexOf("if (cacheable)", read);
        int write = method.indexOf("cache.put(", read);
        int writeGuard = method.lastIndexOf("if (cacheable)", write);

        assertTrue(predicate >= 0);
        assertTrue(cache >= 0);
        assertTrue(read > predicate);
        assertTrue(readGuard >= predicate);
        assertTrue(write > read);
        assertTrue(writeGuard > read);
    }

}
