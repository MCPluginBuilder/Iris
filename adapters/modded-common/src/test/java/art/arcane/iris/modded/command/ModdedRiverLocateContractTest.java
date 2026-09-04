package art.arcane.iris.modded.command;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ModdedRiverLocateContractTest {
    @Test
    public void riverLookupMergesRecordedAndEligibleActiveFeatures() throws IOException {
        Path sourcePath = Path.of(System.getProperty("iris.moddedCommonSources"))
                .resolve("art/arcane/iris/modded/command/ModdedLocateCommands.java");
        String source = Files.readString(sourcePath);
        int methodStart = source.indexOf("static int gotoRiver(");
        int methodEnd = source.indexOf("static int gotoStructure(", methodStart);
        String method = source.substring(methodStart, methodEnd);

        assertTrue(method.contains("GenerationSemanticQueries.nearestRiver("));
        assertTrue(method.contains("runtime == null"));
        assertTrue(method.contains("feature.y() + 2"));
        assertFalse(method.contains("runtime.nearestFeature("));
        assertFalse(method.contains("feature.y() + engine.getDimension().getMinHeight()"));
    }
}
