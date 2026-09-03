package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Controls coastal grottos: sea-level chambers whose only opening is their ocean face, admitted either as a river outlet or as a standalone sea cave.")
@Data
public class IrisCoastalRiverGrottoConfig {
    @Desc("Allow a coastal grotto to serve as an explicit accepted outlet.")
    private boolean enabled = true;

    @Desc("Hydraulic level assigned to the contained coastal grotto pool.")
    private IrisGrottoPoolLevel poolLevel = IrisGrottoPoolLevel.SEA_LEVEL;

    @MinNumber(1)
    @MaxNumber(128)
    @Desc("Maximum horizontal radius in blocks of an accepted coastal grotto.")
    private int horizontalRadius = 12;

    @MinNumber(1)
    @MaxNumber(64)
    @Desc("Maximum vertical radius in blocks of an accepted coastal grotto.")
    private int verticalRadius = 7;

    @MinNumber(1)
    @MaxNumber(63)
    @Desc("Required dry clearance above a coastal grotto pool.")
    private int headroom = 10;

    @MinNumber(1)
    @MaxNumber(1048576)
    @Desc("Maximum accepted coastal grotto volume in blocks.")
    private int maximumVolume = 8192;

    @Desc("Standalone sea caves: coastal grottos opening from the ocean into the coast without a river.")
    private IrisSeaCaveConfig seaCaves = new IrisSeaCaveConfig();
}
