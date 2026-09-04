package art.arcane.iris.core.pregenerator.methods;

import org.junit.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PregenMantleCleanupContractTest {
    @Test
    public void normalEngineCleanupRetriesCandidatesCoveredByTheNewestRealChunk() throws IOException {
        String source = read("art/arcane/iris/engine/framework/Engine.java");
        String cleanup = method(source, "default void cleanupMantleChunk(int x, int z)");

        assertTrue(cleanup.contains("cleanupChunksCoveredBy("));
        assertTrue(cleanup.contains("EngineMantle.ChunkCleanupCallback.NONE"));
        assertFalse(cleanup.contains(".cleanupChunk(x, z)"));
    }

    @Test
    public void asyncPregenReportsOnlyCleanupCallbacksFromCoveredTargets() throws IOException {
        String source = read("art/arcane/iris/core/pregenerator/methods/AsyncPregenMethod.java");
        String completion = method(source, "private void completeChunk(");
        String cleanup = method(source, "private void cleanupMantleChunksCoveredBy(");

        assertTrue(completion.contains("cleanupMantleChunksCoveredBy(x, z, listener)"));
        assertFalse(completion.contains("listener.onChunkCleaned"));
        assertTrue(cleanup.contains("cleanupChunksCoveredBy(x, z, true, listener::onChunkCleaned)"));
        assertFalse(cleanup.contains("forceCleanupChunk"));
    }

    @Test
    public void finalChunkFlushUsesTargetedPersistenceWithoutFullWorldSave() throws IOException {
        String source = read("art/arcane/iris/core/pregenerator/methods/AsyncPregenMethod.java");
        String flush = method(source, "private void flushAllRemainingChunks()");
        String medievalSource = read("art/arcane/iris/core/pregenerator/methods/MedievalPregenMethod.java");
        String medievalFlush = method(medievalSource, "private void unloadAndSaveAllChunks(");

        assertTrue(flush.contains("evictRegion(regionKey)"));
        assertTrue(flush.contains("INMS.get().flushChunkIO(world)"));
        assertFalse(flush.contains("world.save()"));
        assertTrue(medievalFlush.contains("i.unload(true)"));
        assertTrue(medievalFlush.contains("INMS.get().flushChunkIO(world)"));
        assertFalse(medievalFlush.contains("world.save()"));
    }

    @Test
    public void foliaFinalEvictionUsesAwaitableRegionOwnedTasks() throws IOException {
        String source = read("art/arcane/iris/core/pregenerator/methods/AsyncPregenMethod.java");
        String eviction = method(source, "private CompletableFuture<Void> evictFoliaRegion(");

        assertTrue(eviction.contains("J.runRegionFuture("));
        assertTrue(eviction.contains("CompletableFuture.allOf("));
        assertFalse(eviction.contains("J.runRegion("));
    }

    @Test
    public void shutdownReclaimsMantleBeforeReleasingTheJob() throws IOException {
        String source = read("art/arcane/iris/core/pregenerator/IrisPregenerator.java");
        String shutdown = method(source, "private void shutdown()");
        int generator = shutdown.indexOf("shutdownStep(\"generator\"");
        int interruptBoundary = shutdown.indexOf("Thread.interrupted()", generator);
        int mantle = shutdown.indexOf("shutdownStep(\"mantle\"");
        int protocol = shutdown.indexOf("shutdownStep(\"protocol\"");
        int listener = shutdown.indexOf("shutdownStep(\"listener\"");

        assertTrue(generator >= 0);
        assertTrue(interruptBoundary > generator);
        assertTrue(mantle > interruptBoundary);
        assertTrue(protocol > mantle);
        assertTrue(listener > protocol);
    }

    @Test
    public void directStopDoesNotReleaseTheActiveJobBeforeWorkerCleanup() throws IOException {
        String source = read("art/arcane/iris/core/gui/PregeneratorJob.java");
        String stop = method(source, "public void stop()");
        String close = method(source, "public void onClose()");

        assertTrue(stop.contains("requestStop()"));
        assertFalse(stop.contains("instance.compareAndSet"));
        assertTrue(close.contains("instance.compareAndSet(this, null)"));
    }

    @Test
    public void performanceProfilePreparationRunsOnThePregenWorker() throws IOException {
        String source = read("art/arcane/iris/core/gui/PregeneratorJob.java");
        String constructor = method(source, "public PregeneratorJob(Configuration configuration)");
        String worker = method(source, "private void runWorker(Runnable preparation)");

        assertTrue(constructor.contains("runWorker(configuration.preparation())"));
        assertBefore(worker, "preparation.run()", "pregenerator.start()");
    }

    private static String read(String relativePath) throws IOException {
        Path current;
        try {
            current = Path.of(PregenMantleCleanupContractTest.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
        } catch (URISyntaxException e) {
            throw new IOException("Unable to resolve the core source root", e);
        }
        while (current != null && !Files.isDirectory(current.resolve("src/main/java"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IOException("Unable to locate the core source root");
        }
        return Files.readString(current.resolve("src/main/java").resolve(relativePath));
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

    private static void assertBefore(String source, String first, String second) {
        int firstIndex = source.indexOf(first);
        int secondIndex = source.indexOf(second);
        assertTrue(firstIndex >= 0);
        assertTrue(secondIndex > firstIndex);
    }
}
