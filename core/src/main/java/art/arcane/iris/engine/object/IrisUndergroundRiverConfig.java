package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Controls an independent underground river network.")
@Data
public class IrisUndergroundRiverConfig {
    @Desc("Enable the independent underground-river source budget.")
    private boolean enabled = true;

    @Desc("Underground-river source allocation.")
    private IrisUndergroundRiverSourceConfig sources = new IrisUndergroundRiverSourceConfig();

    @Desc("Underground river fluid elevation in world Y.")
    private IrisStyledRange fluidLevel = range(-48D, 50D, 1024D);

    @Desc("Underground wet channel width in blocks.")
    private IrisStyledRange channelWidth = range(3D, 8D, 768D);

    @Desc("Underground wet bed depth in blocks.")
    private IrisStyledRange depth = range(1D, 3D, 768D);

    @Desc("Dry vertical clearance above underground river fluid.")
    private IrisStyledRange headroom = range(6D, 14D, 768D);

    @Desc("Allow accepted underground courses to connect transactionally to existing caves.")
    private boolean connectToExistingCaves = true;

    @MinNumber(16)
    @MaxNumber(512)
    @Desc("Distance in blocks over which an ocean-bound underground river levels to sea level before its mouth.")
    private int mouthLevelingDistance = 64;

    @MinNumber(0)
    @MaxNumber(4)
    @Desc("Extra underground courses an outlet may accept as tributaries joining its main passage; they are budgeted on top of the source density, and 0 keeps one passage per outlet.")
    private int tributaries = 1;

    @MinNumber(1)
    @MaxNumber(64)
    @Desc("Blocks of solid rock kept between the top of a passage's headroom and the surface above it; a passage that cannot keep this much cover is lowered or rejected.")
    private int minimumRockCover = 1;

    @MinNumber(1)
    @MaxNumber(32)
    @Desc("Blocks of solid rock kept between the bottom of a passage's bed and the world floor below it.")
    private int minimumFloorCover = 1;

    @MinNumber(1)
    @MaxNumber(64)
    @Desc("Number of joined tributary sources at which a passage reaches its full sampled width; fewer contributing sources carve a proportionally narrower passage, and 1 gives every passage its full width.")
    private int wideningSources = 8;

    @Desc("Optional palette painted over the floor layers under an underground river instead of the cave biome's own layers. Disabled by default, which keeps the biome's layers.")
    private IrisRiverMaterialConfig bedMaterial = new IrisRiverMaterialConfig();

    private static IrisStyledRange range(double min, double max, double zoom) {
        return new IrisStyledRange(min, max, new IrisGeneratorStyle(NoiseStyle.IRIS).zoomed(zoom));
    }
}
