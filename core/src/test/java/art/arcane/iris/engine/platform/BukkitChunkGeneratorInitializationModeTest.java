package art.arcane.iris.engine.platform;

import art.arcane.iris.engine.IrisEngine;
import art.arcane.iris.engine.object.StudioMode;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BukkitChunkGeneratorInitializationModeTest {
    @Test
    public void runtimeAndOrdinaryStudioWarmGenerationCaches() {
        IrisEngine.InitializationMode runtime =
                BukkitChunkGenerator.selectInitializationMode(false, false);
        IrisEngine.InitializationMode studio =
                BukkitChunkGenerator.selectInitializationMode(true, false);

        assertEquals(IrisEngine.InitializationMode.RUNTIME, runtime);
        assertFalse(runtime.studio());
        assertTrue(runtime.warmGenerationCaches());
        assertEquals(IrisEngine.InitializationMode.STUDIO, studio);
        assertTrue(studio.studio());
        assertTrue(studio.warmGenerationCaches());
    }

    @Test
    public void activeJigsawStudioSkipsGenerationCacheWarm() {
        IrisEngine.InitializationMode mode =
                BukkitChunkGenerator.selectInitializationMode(true, true);

        assertEquals(IrisEngine.InitializationMode.JIGSAW_STUDIO, mode);
        assertTrue(mode.studio());
        assertFalse(mode.warmGenerationCaches());
    }

    @Test
    public void jigsawBootstrapAndInitializationFailureSkipNativeStructureGeneration() {
        assertFalse(BukkitChunkGenerator.shouldGenerateNativeStructures(true, false, false));
        assertFalse(BukkitChunkGenerator.shouldGenerateNativeStructures(true, true, false));
        assertFalse(BukkitChunkGenerator.shouldGenerateNativeStructures(false, true, false));
        assertFalse(BukkitChunkGenerator.shouldGenerateNativeStructures(false, false, true));
        assertTrue(BukkitChunkGenerator.shouldGenerateNativeStructures(false, false, false));
    }

    @Test
    public void onlyOrdinaryOpenStudioRunsThePackHotloader() {
        assertFalse(BukkitChunkGenerator.shouldRunStudioHotload(false, false, false));
        assertFalse(BukkitChunkGenerator.shouldRunStudioHotload(true, true, false));
        assertFalse(BukkitChunkGenerator.shouldRunStudioHotload(true, false, true));
        assertTrue(BukkitChunkGenerator.shouldRunStudioHotload(true, false, false));
    }

    @Test
    public void transientStudioWorldsAreNotPersisted() {
        assertFalse(BukkitChunkGenerator.shouldPersistWorldRegistration(true));
        assertTrue(BukkitChunkGenerator.shouldPersistWorldRegistration(false));
    }

    @Test
    public void syntheticEntryCoversOnlyTheNormalStudioRadiusTwoLobby() {
        assertTrue(BukkitChunkGenerator.shouldGenerateSyntheticStudioEntry(
                true, false, false, false, false, StudioMode.NORMAL, 0, 0));
        assertTrue(BukkitChunkGenerator.shouldGenerateSyntheticStudioEntry(
                true, false, false, false, false, null, 0, 0));
        assertTrue(BukkitChunkGenerator.shouldGenerateSyntheticStudioEntry(
                true, false, false, false, false, StudioMode.NORMAL, 2, 2));
        assertTrue(BukkitChunkGenerator.shouldGenerateSyntheticStudioEntry(
                true, false, false, false, false, StudioMode.NORMAL, -2, -2));
        assertFalse(BukkitChunkGenerator.shouldGenerateSyntheticStudioEntry(
                false, false, false, false, false, StudioMode.NORMAL, 0, 0));
        assertFalse(BukkitChunkGenerator.shouldGenerateSyntheticStudioEntry(
                true, true, false, false, false, StudioMode.NORMAL, 0, 0));
        assertFalse(BukkitChunkGenerator.shouldGenerateSyntheticStudioEntry(
                true, false, true, false, false, StudioMode.NORMAL, 0, 0));
        assertFalse(BukkitChunkGenerator.shouldGenerateSyntheticStudioEntry(
                true, false, false, true, false, StudioMode.NORMAL, 0, 0));
        assertFalse(BukkitChunkGenerator.shouldGenerateSyntheticStudioEntry(
                true, false, false, false, true, StudioMode.NORMAL, 0, 0));
        assertFalse(BukkitChunkGenerator.shouldGenerateSyntheticStudioEntry(
                true, false, false, false, false, StudioMode.BIOME_BUFFET_1x1, 0, 0));
        assertFalse(BukkitChunkGenerator.shouldGenerateSyntheticStudioEntry(
                true, false, false, false, false, StudioMode.OBJECT_BUFFET, 0, 0));
        assertFalse(BukkitChunkGenerator.shouldGenerateSyntheticStudioEntry(
                true, false, false, false, false, StudioMode.REGION_BUFFET, 0, 0));
        assertTrue(BukkitChunkGenerator.shouldGenerateSyntheticStudioEntry(
                true, false, false, false, false, StudioMode.NORMAL, 1, 0));
        assertTrue(BukkitChunkGenerator.shouldGenerateSyntheticStudioEntry(
                true, false, false, false, false, StudioMode.NORMAL, 0, -1));
        assertFalse(BukkitChunkGenerator.shouldGenerateSyntheticStudioEntry(
                true, false, false, false, false, StudioMode.NORMAL, 3, 0));
        assertFalse(BukkitChunkGenerator.shouldGenerateSyntheticStudioEntry(
                true, false, false, false, false, StudioMode.NORMAL, 0, -3));
    }
}
