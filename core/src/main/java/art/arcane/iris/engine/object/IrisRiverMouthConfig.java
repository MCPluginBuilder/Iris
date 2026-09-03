package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Controls how a surface river meets the sea.")
@Data
public class IrisRiverMouthConfig {
    @MinNumber(1)
    @MaxNumber(4)
    @Desc("Width multiplier reached at the coastline over the final stretch of the river.")
    private double flareRatio = 1.6D;

    @MinNumber(0)
    @MaxNumber(32)
    @Desc("Maximum number of blocks the recorded mouth footprint may enter the ocean before terminating.")
    private int maximumOceanApron = 8;

    @MinNumber(0)
    @MaxNumber(256)
    @Desc("Blocks of river before the coast held at sea level as a drowned inlet, widened toward flareRatio over that reach. Zero ends the river at the shoreline with no inlet.")
    private int inletLength = 64;

    @MinNumber(0)
    @MaxNumber(16)
    @Desc("Extra bed depth reached at the shoreline over the inlet, so the estuary is deeper than the river above it.")
    private int inletDepth = 3;

    @MinNumber(0)
    @MaxNumber(128)
    @Desc("Deepest cut allowed in the inlet and its approach ramp, replacing channel.maximumIncision there, so the coast may be cut down to sea level through a rise of this height.")
    private int maximumIncision = 32;
}
