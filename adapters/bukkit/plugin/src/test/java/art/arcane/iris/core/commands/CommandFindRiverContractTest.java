package art.arcane.iris.core.commands;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CommandFindRiverContractTest {
    @Test
    public void riverLookupMergesRecordedAndEligibleActiveFeatures() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.commandFindSource")));
        int methodStart = source.indexOf("public void river(");
        int methodEnd = source.indexOf("public void structure(", methodStart);
        String method = source.substring(methodStart, methodEnd);

        assertTrue(method.contains("GenerationSemanticQueries.nearestRiver("));
        assertTrue(method.contains("runtime == null"));
        assertTrue(method.contains("feature.y()"));
        assertFalse(method.contains("runtime.nearestFeature("));
        assertFalse(method.contains("feature.y() + activeEngine.getDimension().getMinHeight()"));
    }
}
