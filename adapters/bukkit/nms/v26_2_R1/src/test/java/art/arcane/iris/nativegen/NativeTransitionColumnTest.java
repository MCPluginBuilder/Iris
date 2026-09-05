package art.arcane.iris.nativegen;

import art.arcane.iris.engine.history.BoundaryColumnGeometry;
import art.arcane.iris.engine.history.TerrainBoundarySignature;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class NativeTransitionColumnTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void preservesNativePropertiesCavesAndFluids() {
        NoiseColumn column = NativeTransitionColumn.column(signature("minecraft:oak_log[axis=x]"),
                LevelHeightAccessor.create(-16, 32));
        assertEquals(Direction.Axis.X, column.getBlock(-15).getValue(RotatedPillarBlock.AXIS));
        assertTrue(column.getBlock(-14).is(Blocks.CAVE_AIR));
        assertEquals(Integer.valueOf(3), column.getBlock(-13).getValue(LiquidBlock.LEVEL));
        assertTrue(column.getBlock(0).isAir());
    }

    @Test
    public void usesNativeHeightmapPredicatesForWaterAndOpenSpace() {
        TerrainBoundarySignature signature = signature("minecraft:oak_log[axis=x]");
        LevelHeightAccessor height = LevelHeightAccessor.create(-16, 32);
        assertEquals(-12, NativeTransitionColumn.height(signature, Heightmap.Types.WORLD_SURFACE_WG, height));
        assertEquals(-14, NativeTransitionColumn.height(signature, Heightmap.Types.OCEAN_FLOOR_WG, height));
    }

    @Test
    public void rejectsUnknownSavedStatesInsteadOfReplacingTerrain() {
        assertThrows(IllegalArgumentException.class, () -> NativeTransitionColumn.column(
                signature("missing:removed_block"), LevelHeightAccessor.create(-16, 32)));
    }

    private static TerrainBoundarySignature signature(String solidState) {
        BoundaryColumnGeometry geometry = BoundaryColumnGeometry.fromVoxels(-16, List.of(
                new BoundaryColumnGeometry.Voxel("minecraft:stone", BoundaryColumnGeometry.Phase.SOLID, "", false),
                new BoundaryColumnGeometry.Voxel(solidState, BoundaryColumnGeometry.Phase.SOLID, "", false),
                new BoundaryColumnGeometry.Voxel("minecraft:cave_air", BoundaryColumnGeometry.Phase.AIR, "", false),
                new BoundaryColumnGeometry.Voxel("minecraft:water[level=3]", BoundaryColumnGeometry.Phase.FLUID,
                        "minecraft:water[level=3]", false)));
        return new TerrainBoundarySignature(
                new TerrainBoundarySignature.Column(0, 0, 3, 1, OptionalInt.of(3), OptionalInt.empty()),
                new TerrainBoundarySignature.Samples(new TerrainBoundarySignature.VerticalLayout(-16, 4, 1),
                        new TerrainBoundarySignature.BiomeEncoding(List.of("minecraft:plains"), new short[]{0})),
                geometry);
    }
}
