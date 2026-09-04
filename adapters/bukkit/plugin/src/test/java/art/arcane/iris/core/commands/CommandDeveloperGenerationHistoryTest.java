package art.arcane.iris.core.commands;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CommandDeveloperGenerationHistoryTest {
    @Test
    public void updateWorldStagesThePackWithTheLiveWorldSeed() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/core/commands/CommandDeveloper.java"
        )).replace("\r\n", "\n");
        int updateStart = source.indexOf("public void updateWorld(");
        int updateEnd = source.indexOf("@Director(description = \"Test\"", updateStart);
        String update = source.substring(updateStart, updateEnd);

        assertTrue(update.contains(
                "replaceIntoWorld(sender(), pack, folder, world.getSeed())"
        ));
        assertFalse(update.contains("iris/pack"));
    }
}
