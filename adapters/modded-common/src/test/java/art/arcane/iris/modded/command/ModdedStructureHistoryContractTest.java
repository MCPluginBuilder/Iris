package art.arcane.iris.modded.command;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ModdedStructureHistoryContractTest {
    @Test
    public void retainedStructureNamesReachTheSemanticSearchOnBothAdmissionBranches() throws IOException {
        Path root = Path.of(System.getProperty("iris.moddedCommonSources"));
        String source = Files.readString(root.resolve("art/arcane/iris/modded/command/ModdedLocateCommands.java"));
        int start = source.indexOf("static int gotoStructure(");
        int end = source.indexOf("static String registeredStructureUnavailableMessage(", start);
        String admission = source.substring(start, end);
        String retainedCheck = "GenerationFindCatalog.hasRetainedStructurePlacement(engine, key)";
        int firstCheck = admission.indexOf(retainedCheck);
        assertTrue(firstCheck >= 0);
        assertTrue(admission.indexOf(retainedCheck, firstCheck + retainedCheck.length()) >= 0);

        int locateStart = source.indexOf("private static void locateIrisStructure(");
        int locateEnd = source.indexOf("private static void runNativeStructureLocate(", locateStart);
        String locate = source.substring(locateStart, locateEnd);
        assertTrue(locate.contains("GenerationSemanticQueries.nearestStructure("));
        assertFalse(locate.contains("IrisStructureLocator.locate("));

        String suggestions = Files.readString(root.resolve("art/arcane/iris/modded/command/ModdedCommandSuggestions.java"));
        assertTrue(suggestions.contains("GenerationFindCatalog.retainedStructureKeys(engine)"));
    }
}
