package art.arcane.iris.core.compat;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.object.IrisBlockData;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IrisBlockDataResolutionTest {
    private static CompatFixtures.FakeRegistries registries;

    @BeforeClass
    public static void bindPlatform() {
        registries = CompatFixtures.registries("minecraft:sulfur", "minecraft:brimstone", "minecraft:grass");
        CompatFixtures.bind(registries);
    }

    @AfterClass
    public static void unbindPlatform() {
        CompatFixtures.unbind();
    }

    private static IrisData pack(String dimensionJson, String customBlockJson) throws Exception {
        File folder = Files.createTempDirectory("iris-compat-blockdata").toFile();
        if (dimensionJson != null) {
            File dimensions = new File(folder, "dimensions");
            assertTrue(dimensions.mkdirs());
            Files.writeString(new File(dimensions, "overworld.json").toPath(), dimensionJson);
        }
        if (customBlockJson != null) {
            File blocks = new File(folder, "blocks");
            assertTrue(blocks.mkdirs());
            Files.writeString(new File(blocks, "fancy.json").toPath(), customBlockJson);
        }
        return IrisData.get(folder);
    }

    @Test
    public void presentBlockResolvesWithProperties() throws Exception {
        IrisData data = pack(null, null);
        IrisBlockData entry = new IrisBlockData("minecraft:oak_log");
        entry.getData().put("axis", "y");
        assertEquals("minecraft:oak_log[axis=y]", entry.getBlockData(data).key());
        assertEquals("minecraft:stone", new IrisBlockData("STONE").getBlockData(data).key());
    }

    @Test
    public void backupRevivesMissingBlock() throws Exception {
        IrisData data = pack(null, null);
        IrisBlockData entry = new IrisBlockData("minecraft:sulfur").setBackup(new IrisBlockData("minecraft:sand"));
        assertEquals("minecraft:sand", entry.getBlockData(data).key());

        IrisBlockData chained = new IrisBlockData("minecraft:sulfur")
                .setBackup(new IrisBlockData("minecraft:brimstone").setBackup(new IrisBlockData("minecraft:sand")));
        assertEquals("minecraft:sand", chained.getBlockData(data).key());
    }

    @Test
    public void missingBlockWithoutBackupFallsBackToAir() throws Exception {
        IrisData data = pack(null, null);
        IrisBlockData entry = new IrisBlockData("minecraft:sulfur");
        assertTrue(entry.getBlockData(data).isAir());
        assertTrue(new IrisBlockData("minecraft:sulfur").setBackup(new IrisBlockData("minecraft:brimstone")).getBlockData(data).isAir());
    }

    @Test
    public void dimensionFallbackAppliesBeforeBackup() throws Exception {
        IrisData data = pack("{\"name\":\"overworld\",\"blockFallbacks\":{\"minecraft:sulfur\":\"minecraft:stone\"}}", null);
        IrisBlockData entry = new IrisBlockData("minecraft:sulfur").setBackup(new IrisBlockData("minecraft:sand"));
        assertEquals("minecraft:stone", entry.getBlockData(data).key());
    }

    @Test
    public void legacyRenameAppliesOnEveryPlatform() throws Exception {
        IrisData data = pack(null, null);
        assertEquals("minecraft:short_grass", new IrisBlockData("minecraft:grass").getBlockData(data).key());
        assertTrue(data.getCompatReport().isEmpty());
    }

    @Test
    public void customPackBlockStillResolvesFirstAndUsesItsOwnBackup() throws Exception {
        IrisData data = pack(null, "{\"block\":\"minecraft:sulfur\",\"backup\":{\"block\":\"minecraft:sand\"}}");
        IrisBlockData entry = new IrisBlockData("fancy");
        assertEquals("minecraft:sand", entry.getBlockData(data).key());
        assertFalse(data.getCompatReport().isEmpty());
        assertEquals(CompatAction.SUBSTITUTED, data.getCompatReport().findings().getFirst().action());
        assertEquals("block", data.getCompatReport().findings().getFirst().subjectType());
    }
}
