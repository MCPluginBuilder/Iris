package art.arcane.iris.modded;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModdedGenerationHistoryRoutingContractTest {
    private static final String SOURCE_ROOT_PROPERTY = "iris.moddedCommonSources";

    @Test
    public void startupPublishesOnlyAnImmutableHistoryBoundRuntime() throws IOException {
        String source = source("ModdedWorldEngines.java");
        String create = method(source, "private static Engine create(");
        String createHistoryEngine = method(source, "private static Engine createHistoryEngine(");
        String buildEngine = method(source, "private static IrisEngine buildEngine(");

        assertTrue(create.contains("ModdedGenerationHistoryStorage.openOrAdopt("));
        assertFalse(create.contains("resolvePack("));
        assertTrue(createHistoryEngine.contains("candidate.attachGenerationHistory("));
        assertTrue(createHistoryEngine.contains("IrisBoundarySignatureSampler.INSTANCE"));
        assertTrue(createHistoryEngine.contains("getGenerationTransitionWidthBlocks()"));
        assertTrue(createHistoryEngine.contains("router.preloadActiveRuntimes();"));
        assertBefore(createHistoryEngine, "history.prepareCurrentGenerator(",
                "opened = ModdedGenerationHistoryStorage.resolveActive(history)");
        assertBefore(createHistoryEngine, "opened = ModdedGenerationHistoryStorage.resolveActive(history)",
                "IrisEngine candidate = buildEngine(level, seed, opened)");
        String promoted = method(createHistoryEngine,
                "if (history.activeActivation().activationId() != openedActivationId)");
        assertBefore(promoted, "close(candidate);", "ModdedGenerationHistoryStorage.resolveActive(history)");
        assertBefore(promoted, "ModdedGenerationHistoryStorage.resolveActive(history)",
                "candidate = buildEngine(level, seed, promoted)");
        assertTrue(createHistoryEngine.contains("GenerationHistoryRuntimeRouter.attach("));
        assertTrue(buildEngine.contains("active.packRoot().toFile()"));
        assertTrue(buildEngine.contains("history.paths().activationMantleRoot(activation.activationId())"));
        assertTrue(buildEngine.contains("epoch.kernelVersion()"));
        assertTrue(buildEngine.contains("transitionPlan"));
    }

    @Test
    public void onlyExplicitTransientStudioEnginesBypassHistory() throws IOException {
        String manager = source("ModdedDimensionManager.java");
        String transientCreate = method(manager, "public static Handle createTransientStudio(");
        String persistentCreate = method(manager, "public static Handle createPersistent(");
        String engines = source("ModdedWorldEngines.java");
        String studio = method(engines, "private static IrisEngine createTransientStudioEngine(");
        String generator = source("IrisModdedChunkGenerator.java");
        String bypass = method(generator, "boolean allowsGenerationHistoryBypass(");
        String route = method(generator, "private GenerationHistoryRuntimeRouter.RuntimeRoute openHistoryRoute(");

        assertTrue(transientCreate.contains("ModdedGenerationMode.TRANSIENT_STUDIO"));
        assertTrue(persistentCreate.contains("ModdedGenerationMode.PERSISTENT_CREATE"));
        assertTrue(studio.contains("IrisData.openRuntime(packDir)"));
        assertTrue(studio.contains("IrisEngine.InitializationMode.STUDIO"));
        assertFalse(studio.contains("ModdedGenerationHistoryStorage"));
        assertFalse(studio.contains("attachGenerationHistory("));
        assertTrue(bypass.contains("generationMode != ModdedGenerationMode.TRANSIENT_STUDIO"));
        assertTrue(bypass.contains("irisEngine.isStudio()"));
        assertTrue(bypass.contains("Only an explicitly transient Iris Studio engine"));
        assertBefore(route, "allowsGenerationHistoryBypass(current)", "requireHistoryRouter(current, operation)");
    }

    @Test
    public void everyCoordinateBearingChunkStageOpensItsRoutedRuntime() throws IOException {
        String source = source("IrisModdedChunkGenerator.java");

        assertRuntimeRoute(source, "public Pair<BlockPos, Holder<Structure>> findNearestMapStructure(");
        assertCoordinateRoute(source, "public WeightedList<MobSpawnSettings.SpawnerData> getMobsAt(");
        assertRuntimeRoute(source, "public CompletableFuture<ChunkAccess> createBiomes(");
        assertRuntimeRoute(source, "public void applyCarvers(");
        assertRuntimeRoute(source, "public void buildSurface(");
        assertRuntimeRoute(source, "public void applyBiomeDecoration(");
        assertRuntimeRoute(source, "public void createStructures(");
        assertRuntimeRoute(source, "public void createReferences(");
        assertRuntimeRoute(source, "public void spawnOriginalMobs(");
        assertCoordinateRoute(source, "public int getBaseHeight(");
        assertCoordinateRoute(source, "public NoiseColumn getBaseColumn(");
    }

    @Test
    public void asyncTerrainKeepsTheRouteUntilDurableSemanticClaim() throws IOException {
        String source = source("IrisModdedChunkGenerator.java");
        String fill = method(source, "public CompletableFuture<ChunkAccess> fillFromNoise(");
        String terrain = method(source, "private ChunkAccess generateTerrain(");
        String completion = method(source, "private static CompletableFuture<ChunkAccess> closeRouteOnCompletion(");

        assertBefore(fill, "openHistoryRoute(", "CompletableFuture.supplyAsync(");
        assertTrue(fill.contains("generateTerrain(chunk, generationEngine, pos, air, route)"));
        assertTrue(fill.contains("return closeRouteOnCompletion(pipeline, route);"));
        assertTrue(terrain.contains("openHistoryRuntimeScope(route)"));
        assertBefore(terrain, "generationEngine.generate(", "writeBlocks(chunk, blocks, dimMinY, height)");
        assertBefore(terrain, "writeBlocks(chunk, blocks, dimMinY, height)", "route.claimGeneratedSemantics(");
        assertTrue(terrain.contains("chunk.getBlockState(position.set(x, dimMinY + y, z))"));
        assertTrue(terrain.contains("state.isAir() || state.liquid()"));
        assertBefore(terrain, "route.claimGeneratedSemantics(", "ModdedNativeTerrainReceipts.persist(chunk, route)");
        assertBefore(terrain, "ModdedNativeTerrainReceipts.persist(chunk, route)", "return chunk;");
        assertTrue(completion.contains("pipeline.whenComplete("));
        assertTrue(completion.contains("route.close();"));
        assertBefore(completion, "pipeline.whenComplete(", "route.detachThread()");
        assertBefore(completion, "route.detachThread()", "return completion;");
    }

    @Test
    public void biomeQueriesRouteEachCoordinateAndKeepRuntimeSpecificCaches() throws IOException {
        String source = source("IrisModdedBiomeSource.java");
        String structure = method(source, "public Holder<Biome> getNoiseBiome(");
        String visible = method(source, "Holder<Biome> getVisibleNoiseBiome(");
        String batch = method(source, "public Set<Holder<Biome>> getBiomesWithin(");
        String resolution = method(source, "private Holder<Biome> resolveVisibleBiome(");

        assertTrue(structure.contains("openHistoryCoordinateScope("));
        assertTrue(visible.contains("openHistoryCoordinateScope("));
        assertTrue(batch.contains("quartX << 2, quartZ << 2"));
        assertTrue(source.contains("int runtimeIdentity = engine.getCacheID();"));
        assertTrue(source.contains("cache.get(runtimeIdentity, key)"));
        assertTrue(source.contains("cache.put(runtimeIdentity, key, resolved)"));
        assertBefore(resolution, "historicalPhysicalBiomeKeyAt(", "ModdedWorldgenIds.biomeRef(engine");
    }

    @Test
    public void pendingNativeStagesPreserveSavedStartsAndGuardNewFeatureFootprints() throws IOException {
        String generator = source("IrisModdedChunkGenerator.java");
        String discrete = method(generator, "private boolean allowsRoutedDiscreteGeneration(");
        String decoration = method(generator, "public void applyBiomeDecoration(");
        String structures = method(generator, "public void createStructures(");
        String mobs = method(generator, "public void spawnOriginalMobs(");
        String nativeStage = source("ModdedNativeStructureStage.java");
        String adjust = method(nativeStage, "void adjustGeneratedStructures(");
        String place = method(nativeStage, "void placeVanillaStructures(");
        String savedStart = method(nativeStage, "private boolean isHistoricalStructureStart(");

        assertTrue(discrete.contains("chunk.getPersistedStatus().isOrAfter(stage)"));
        assertFalse(discrete.contains("router.history().activeActivation().activationId()"));
        assertFalse(discrete.contains("return true;"));
        assertTrue(discrete.contains("allowsNewGenerationChunk("));
        assertTrue(discrete.contains("NativeGenerationWriteGuard.allowsPendingStage(current, chunk, stage)"));
        assertTrue(decoration.contains("ChunkStatus.FEATURES"));
        assertTrue(structures.contains("ChunkStatus.STRUCTURE_STARTS"));
        assertTrue(mobs.contains("ChunkStatus.SPAWN"));
        assertBefore(decoration, "chunk.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES)",
                "nativeStructures.placeVanillaStructures(");
        assertBefore(decoration, "nativeStructures.placeVanillaStructures(", "allowsRoutedDiscreteGeneration(");
        assertBefore(decoration, "allowsRoutedDiscreteGeneration(", "importedFeatures.run(");
        assertBefore(decoration, "NativeGenerationWriteGuard.allowsDecoration(", "importedFeatures.run(");
        assertBefore(structures, "allowsRoutedDiscreteGeneration(", "super.createStructures(");
        assertBefore(mobs, "allowsRoutedDiscreteGeneration(", "NaturalSpawner.spawnMobsForChunkGeneration(");
        assertBefore(adjust, "allowsNewGenerationFootprint(", "recordWorldCheckStructureShift(");
        assertBefore(place, "!isHistoricalStructureStart(current, world, start)", "allowsNewGenerationFootprint(");
        assertTrue(savedStart.contains("world.getChunk(origin.x(), origin.z(), ChunkStatus.EMPTY, false)"));
        assertTrue(savedStart.contains("ModdedNativeTerrainReceipts.structureActivation(source)"));
        assertBefore(structures, "super.createStructures(", "ModdedNativeTerrainReceipts.persistStructureActivation(");
    }

    private static void assertRuntimeRoute(String source, String signature) {
        String body = method(source, signature);
        assertTrue(signature + " must open a generation-history route", body.contains("openHistoryRoute("));
        assertTrue(signature + " must open its routed runtime", body.contains("openHistoryRuntimeScope(route)"));
    }

    private static void assertCoordinateRoute(String source, String signature) {
        String body = method(source, signature);
        assertTrue(signature + " must route its coordinate", body.contains("openHistoryCoordinateScope("));
    }

    private static void assertBefore(String source, String first, String second) {
        int firstIndex = source.indexOf(first);
        int secondIndex = source.indexOf(second);
        assertTrue("Missing source contract token: " + first, firstIndex >= 0);
        assertTrue("Missing source contract token: " + second, secondIndex >= 0);
        assertTrue(first + " must occur before " + second, firstIndex < secondIndex);
    }

    private static String source(String fileName) throws IOException {
        String sourceRoot = System.getProperty(SOURCE_ROOT_PROPERTY);
        assertTrue("Missing system property " + SOURCE_ROOT_PROPERTY,
                sourceRoot != null && !sourceRoot.isBlank());
        return Files.readString(Path.of(sourceRoot, "art", "arcane", "iris", "modded", fileName));
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue("Missing source contract signature: " + signature, start >= 0);
        int openBrace = source.indexOf('{', start);
        assertTrue("Missing source contract method body: " + signature, openBrace >= 0);
        int depth = 0;
        for (int index = openBrace; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, index + 1);
                }
            }
        }
        throw new IllegalArgumentException("Unclosed source contract method: " + signature);
    }
}
