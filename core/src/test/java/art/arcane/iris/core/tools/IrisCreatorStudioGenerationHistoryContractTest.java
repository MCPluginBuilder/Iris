package art.arcane.iris.core.tools;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IrisCreatorStudioGenerationHistoryContractTest {
    @Test
    public void studioPublishesHistoryBeforePreparingItsGeneratorAndKeepsTheAuthoringSource() throws Exception {
        Path creatorPath = Path.of(System.getProperty("iris.irisCreatorSource"));
        String creator = Files.readString(creatorPath);
        String factory = Files.readString(creatorPath.resolveSibling("IrisWorldCreator.java"));
        int snapshot = creator.indexOf("studioService.installIntoWorld(sender, resolvedDimension, dimensionRoot, seed)");
        int generator = creator.indexOf("new IrisWorldCreator()");

        assertTrue(snapshot >= 0);
        assertTrue(generator > snapshot);
        assertFalse(creator.contains("if (!studio() || benchmark)"));
        assertTrue(creator.contains(".dimension(installedDimension)"));
        assertTrue(creator.contains(".studioPackSource(resolvedDimension.getLoader().getDataFolder())"));
        assertTrue(factory.contains("GenerationHistory generationHistory = persistent || studio"));
        assertTrue(factory.contains("File packRoot = studio\n                ? Objects.requireNonNull(studioPackSource, \"Studio authoring source\")"));
    }
}
