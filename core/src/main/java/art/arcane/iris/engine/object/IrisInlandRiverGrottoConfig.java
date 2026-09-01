package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Controls contained inland grotto outlets.")
@Data
public class IrisInlandRiverGrottoConfig {
    @Desc("Allow a contained inland grotto to serve as an explicit accepted outlet.")
    private boolean enabled = true;

    @Desc("Continue eligible surface rivers through a falling sinkhole into the contained inland grotto.")
    private boolean connectSurfaceRivers = false;

    @MinNumber(1)
    @MaxNumber(128)
    @Desc("Maximum horizontal radius in blocks of an accepted inland grotto.")
    private int horizontalRadius = 10;

    @MinNumber(1)
    @MaxNumber(64)
    @Desc("Maximum vertical radius in blocks of an accepted inland grotto.")
    private int verticalRadius = 6;

    @MinNumber(1)
    @MaxNumber(63)
    @Desc("Required dry clearance above an inland grotto pool.")
    private int headroom = 10;

    @MinNumber(1)
    @MaxNumber(1048576)
    @Desc("Maximum accepted inland grotto volume in blocks.")
    private int maximumVolume = 8192;
}
