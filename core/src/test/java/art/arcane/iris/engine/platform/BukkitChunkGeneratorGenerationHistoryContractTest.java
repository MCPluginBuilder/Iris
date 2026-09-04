package art.arcane.iris.engine.platform;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public final class BukkitChunkGeneratorGenerationHistoryContractTest {
    @Test
    public void setupPromotesBeforePublicationAndRebuildsFullServicesFromTheNewPack() throws IOException {
        String source = source();
        String setup = method(source, "private void setupEngine()");

        assertBefore(setup, "IrisEngine createdEngine = createEngine(engineTarget);",
                "createdEngine.attachGenerationHistory(");
        assertBefore(setup, "createdEngine.attachGenerationHistory(",
                "generationHistory.activeActivation().activationId() != initialActivationId");
        assertBefore(setup, "createdEngine.close();", "createEngine(loadActiveGenerationHistoryTarget())");
        assertTrue(count(setup, "createdEngine.attachGenerationHistory(") == 2);
        assertBefore(setup, "createEngine(loadActiveGenerationHistoryTarget())", "engine = createdEngine;");
        assertBefore(setup, "IrisBoundarySignatureSampler.INSTANCE", "engine = createdEngine;");
        assertTrue(setup.contains("getGenerationTransitionWidthBlocks()"));
    }

    @Test
    public void historyEngineStartsWithTheActiveMantleKernelAndTransition() throws IOException {
        String createEngine = method(source(), "private IrisEngine createEngine(EngineTarget engineTarget)");

        assertTrue(createEngine.contains("generationHistory.paths().activationMantleRoot(active.activationId())"));
        assertTrue(createEngine.contains("epoch.kernelVersion()"));
        assertTrue(createEngine.contains("generationHistory.transitionPlan(active.activationId())"));
        assertTrue(createEngine.contains("if (generationHistory == null)"));
    }

    @Test
    public void publicBukkitTerrainClaimsSemanticsOnlyAfterSuccessfulApplication() throws IOException {
        String generateNoise = method(
                source(),
                "public void generateNoise(@NotNull WorldInfo world, @NotNull Random random, int x, int z, @NotNull ChunkGenerator.ChunkData d)"
        );

        assertBefore(generateNoise, "engine.generate(x << 4, z << 4", "blocks.apply()");
        assertBefore(generateNoise, "blocks.apply()", "historyScope.claimGeneratedSemantics()");
    }

    private static String source() throws IOException {
        return Files.readString(Path.of(System.getProperty("iris.bukkitChunkGeneratorSource")))
                .replace("\r\n", "\n");
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue("Missing source contract token: " + signature, start >= 0);
        int openingBrace = source.indexOf('{', start);
        assertTrue(openingBrace >= 0);
        int depth = 0;
        for (int index = openingBrace; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, index + 1);
                }
            }
        }
        throw new AssertionError("Method body did not close: " + signature);
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
