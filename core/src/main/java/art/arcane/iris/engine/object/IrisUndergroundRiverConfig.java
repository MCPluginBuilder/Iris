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

    private static IrisStyledRange range(double min, double max, double zoom) {
        return new IrisStyledRange(min, max, new IrisGeneratorStyle(NoiseStyle.IRIS).zoomed(zoom));
    }
}
