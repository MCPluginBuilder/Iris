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
}
