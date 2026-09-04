package art.arcane.iris.core.nms.v26_2_R1;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class GenerationHistoryRoutingContractTest {
    @Test
    public void everyChunkStageScopesItsHistoryRuntimeBeforeSessionAdmission() throws IOException {
        String source = chunkGeneratorSource();

        assertRoutedStage(source, "public void createStructures(", "private void adjustGeneratedStructures");
        assertRoutedStage(source, "public void createReferences(", "public CompletableFuture<ChunkAccess> createBiomes");
        assertRoutedStage(source, "public CompletableFuture<ChunkAccess> createBiomes", "public void buildSurface");
        assertRoutedStage(source, "public void buildSurface(", "public void applyCarvers(");
        assertRoutedStage(source, "public void applyCarvers(", "public CompletableFuture<ChunkAccess> fillFromNoise");
        assertRoutedStage(source, "public void spawnOriginalMobs(", "private boolean allowsRoutedDiscreteGeneration(");
        assertRoutedStage(
                source,
                "public void applyBiomeDecoration(WorldGenLevel generatoraccessseed, ChunkAccess ichunkaccess, StructureManager structuremanager, boolean vanilla)",
                "public BiomeGenerationSettings getBiomeGenerationSettings");

        String baseHeight = method(source, "public int getBaseHeight(", "public NoiseColumn getBaseColumn(");
        String baseColumn = method(source, "public NoiseColumn getBaseColumn(", "private GenerationSessionLease requireGenerationLease");
        assertBefore(baseHeight, "openHistoryCoordinateScope(", "engine.acquireGenerationLease(");
        assertBefore(baseColumn, "openHistoryCoordinateScope(", "engine.acquireGenerationLease(");
    }

    @Test
    public void asyncNoiseKeepsRouteUntilCompletionAndReopensThreadLocalScopes() throws IOException {
        String source = chunkGeneratorSource();
        String noise = method(
                source,
                "public CompletableFuture<ChunkAccess> fillFromNoise",
                "private static boolean isCancellationFailure");

        assertBefore(noise, "requireNoiseGenerationStage(", "openHistoryRoute(");
        assertBefore(noise, "openHistoryRoute(", "requireGenerationLease(");
        assertTrue(count(noise, "openHistoryRuntimeScope(route)") >= 2);
        assertBefore(noise, ".thenApply(filled ->", "pipeline.whenComplete(");
        assertTrue(noise.contains("closeNoisePipelineResources(\n                        failure, lease, route, stage)"));
    }

    @Test
    public void transitionBandSuppressesDiscreteGenerationAndCrossingStarts() throws IOException {
        String source = chunkGeneratorSource();
        String structures = method(source, "public void createStructures(", "private void adjustGeneratedStructures");
        String adjustment = method(source, "private void adjustGeneratedStructures", "public ChunkGeneratorStructureState createState");
        String decoration = method(
                source,
                "public void applyBiomeDecoration(WorldGenLevel generatoraccessseed, ChunkAccess ichunkaccess, StructureManager structuremanager, boolean vanilla)",
                "public BiomeGenerationSettings getBiomeGenerationSettings");

        assertTrue(structures.contains("allowsRoutedDiscreteGeneration(access, ChunkStatus.STRUCTURE_STARTS)"));
        assertTrue(decoration.contains("allowsRoutedDiscreteGeneration(ichunkaccess, ChunkStatus.FEATURES)"));
        String discrete = method(source, "private boolean allowsRoutedDiscreteGeneration(",
                "private static WeightedList<MobSpawnSettings.SpawnerData> mergeSpawnTables(");
        assertTrue(discrete.contains("chunk.getPersistedStatus().isOrAfter(stage)"));
        assertTrue(discrete.contains("engine.getComplex().allowsNewGenerationChunk(chunkPos.x(), chunkPos.z())"));
        assertBefore(decoration, "delegate.applyBiomeDecoration(", "claimGeneratedSemantics(route,");
        assertTrue(adjustment.contains("start.getBoundingBox()"));
        assertTrue(adjustment.contains("allowsNewGenerationFootprint("));
        assertTrue(adjustment.contains("NativeStructureOwnershipStore.discard("));
    }

    @Test
    public void biomeCachesAndVisibleHistoricalBiomesAreRuntimeAware() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.customBiomeSource")))
                .replace("\r\n", "\n");
        String visibleResolution = method(
                source,
                "private Holder<Biome> resolveVisibleBiomeHolder(",
                "private Holder<Biome> resolveCustomHolder(");
        String activeBatch = method(
                source,
                "Holder<Biome> getVisibleNoiseBiomeWithActiveGenerationLease(\n            int x,",
                "private GenerationSessionLease tryAcquireGenerationLease");

        assertTrue(source.contains("record RuntimeNoiseKey(int runtimeId, long coordinateKey)"));
        assertTrue(source.contains("record RuntimeColumnKey(int runtimeId, long coordinateKey)"));
        assertTrue(source.contains("ConcurrentHashMap<Integer, RuntimeBiomeState> runtimeBiomeStates"));
        assertTrue(source.contains("addGenerationRuntimeRetirementListener(this::evictRuntimeCaches)"));
        assertTrue(source.contains("runtimeBiomeStates.remove(runtimeId)"));
        assertTrue(source.contains("noiseBiomeCache.keySet().removeIf(key -> key.runtimeId() == runtimeId)"));
        assertBefore(visibleResolution, "historicalPhysicalBiomeKeyAt(", "resolveBiomeResolution(");
        assertFalse(activeBatch.contains("openHistoryCoordinateScope("));

        String chunkGenerator = chunkGeneratorSource();
        assertTrue(chunkGenerator.contains("addGenerationRuntimeRetirementListener(this::evictRuntimeCaches)"));
        assertTrue(chunkGenerator.contains("mergedSpawnTables.keySet().removeIf(key -> key.runtimeId() == runtimeId)"));
    }

    @Test
    public void importedFeatureTablesAreRuntimeScopedAndRetiredCentrally() throws IOException {
        String stage = Files.readString(Path.of(System.getProperty("iris.nmsChunkGeneratorSource"))
                        .resolveSibling("ImportedFeatureStage.java"))
                .replace("\r\n", "\n");
        String generator = chunkGeneratorSource();

        assertTrue(stage.contains("ConcurrentHashMap<Integer, RuntimeFeatureState> runtimeStates"));
        assertTrue(stage.contains("runtimeStates.get(engine.getCacheID())"));
        assertTrue(stage.contains("runtimeStates.remove(runtimeId)"));
        assertFalse(stage.contains("volatile FeatureTable featureTable"));
        assertFalse(stage.contains("volatile int settledRuntimeId"));
        assertTrue(generator.contains("importedFeatures.evictRuntime(runtimeId)"));
    }

    @Test
    public void nativeReferencesResolvePolicyInTheOriginRuntime() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.nativeStructureVolumeIndexSource"))
                        .resolveSibling("NativeStructureReferenceRepair.java"))
                .replace("\r\n", "\n");
        String references = method(source, "public static void createReferences(",
                "private static GenerationHistoryRuntimeRouter.CoordinateScope openOriginRuntimeScope(");

        assertBefore(references, "openOriginRuntimeScope(engine, originChunkX, originChunkZ)", "scanStart(");
        assertBefore(references, "scanStart(", "isTargetRelevant(");
        assertTrue(source.contains("irisEngine.openGenerationHistoryCoordinateScope(chunkX << 4, chunkZ << 4)"));
        assertFalse(references.contains("allowsNewGenerationFootprint("));
    }

    private static void assertRoutedStage(String source, String start, String end) {
        String stage = method(source, start, end);
        assertBefore(stage, "requireGenerationStage(", "openHistoryRoute(");
        assertBefore(stage, "openHistoryRoute(", "requireGenerationLease(");
        assertBefore(stage, "openHistoryRuntimeScope(route)", "requireGenerationLease(");
    }

    private static String chunkGeneratorSource() throws IOException {
        return Files.readString(Path.of(System.getProperty("iris.nmsChunkGeneratorSource")))
                .replace("\r\n", "\n");
    }

    private static String method(String source, String startToken, String endToken) {
        int start = source.indexOf(startToken);
        int end = source.indexOf(endToken, start);
        assertTrue("Missing source contract token: " + startToken, start >= 0);
        assertTrue("Missing source contract token: " + endToken, end > start);
        return source.substring(start, end);
    }

    private static void assertBefore(String source, String first, String second) {
        int firstIndex = source.indexOf(first);
        int secondIndex = source.indexOf(second);
        assertTrue("Missing source contract token: " + first, firstIndex >= 0);
        assertTrue("Missing source contract token: " + second, secondIndex >= 0);
        assertTrue(first + " must occur before " + second, firstIndex < secondIndex);
    }

    private static int count(String source, String token) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }
}
