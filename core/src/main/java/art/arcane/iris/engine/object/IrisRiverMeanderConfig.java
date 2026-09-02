package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Controls multi-scale terrain-guided river meanders.")
@Data
public class IrisRiverMeanderConfig {
    @MinNumber(8)
    @MaxNumber(512)
    @Desc("Broad meander wavelength in blocks.")
    private int primaryWavelength = 64;

    @MinNumber(4)
    @MaxNumber(128)
    @Desc("Fine worm wavelength in blocks. Smaller values change direction more frequently.")
    private int detailWavelength = 12;

    @MinNumber(0)
    @MaxNumber(2)
    @Desc("Strength of broad meanders.")
    private double primaryStrength = 0.34D;

    @MinNumber(0)
    @MaxNumber(2)
    @Desc("Strength of fine worm movement.")
    private double detailStrength = 0.42D;

    @MinNumber(0)
    @MaxNumber(1)
    @Desc("Maximum lateral route displacement as a fraction of one drainage edge.")
    private double maximumOffsetRatio = 0.48D;

    @MinNumber(0)
    @MaxNumber(4)
    @Desc("Terrain-safe centerline smoothing passes after route solving.")
    private int smoothingPasses = 1;

    @MinNumber(10)
    @MaxNumber(150)
    @Desc("Maximum retained centerline turn angle in degrees.")
    private double maximumTurnDegrees = 82D;
}
