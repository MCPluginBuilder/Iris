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
    @MaxNumber(64)
    @Desc("Maximum number of blocks the recorded mouth footprint may enter the ocean before terminating.")
    private int maximumOceanApron = 8;
}
