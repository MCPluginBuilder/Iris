package art.arcane.iris.core.commands;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CommandFindStructureHistoryContractTest {
    @Test
    public void admitsRetainedStructuresBeforeActiveOnlyRejectionsAndUsesSemanticSearch() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.commandFindSource")));
        int structureStart = source.indexOf("public void structure(");
        int retainedAdmission = source.indexOf("GenerationFindCatalog.hasRetainedStructurePlacement(e, structureKey)", structureStart);
        int unknownRejection = source.indexOf("if (route == StructureLookupRoute.UNKNOWN)", structureStart);
        assertTrue(retainedAdmission > structureStart && retainedAdmission < unknownRejection);

        int locateStart = source.indexOf("private void locateIrisStructure(");
        int locateEnd = source.indexOf("public void object(", locateStart);
        String locate = source.substring(locateStart, locateEnd);
        assertTrue(locate.contains("GenerationSemanticQueries.nearestStructure("));
        assertFalse(locate.contains("IrisStructureLocator.locate("));
    }
}
