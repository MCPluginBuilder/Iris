package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Controls river leveling before a coastal reservoir and the final ocean footprint.")
@Data
public class IrisRiverMouthConfig {
    @MinNumber(0)
    @MaxNumber(2048)
    @Desc("Distance in blocks over which an ocean-bound river reaches sea level before the coastline.")
    private int levelingDistance = 64;

    @MinNumber(0)
    @MaxNumber(64)
    @Desc("Maximum number of blocks the river footprint may enter an ocean reservoir before terminating.")
    private int maximumOceanApron = 8;
}
