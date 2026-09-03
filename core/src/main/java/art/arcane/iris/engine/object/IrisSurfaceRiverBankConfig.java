package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Controls the shore band and the eroded valley around a surface river.")
@Data
public class IrisSurfaceRiverBankConfig {
    @MinNumber(0.5)
    @MaxNumber(6)
    @Desc("Width in blocks of the shore band that receives shore biome content.")
    private double shoreWidth = 1.5D;

    @MinNumber(0.5)
    @MaxNumber(12)
    @Desc("Horizontal run in blocks of eroded bank per block of cut depth.")
    private double blendSlope = 3D;

    @MinNumber(1)
    @MaxNumber(64)
    @Desc("Narrowest eroded band outside the shore, in blocks.")
    private int minimumBlendWidth = 4;

    @MinNumber(1)
    @MaxNumber(64)
    @Desc("Widest eroded band outside the shore, in blocks.")
    private int maximumBlendWidth = 32;

    @Desc("Show the biome's deeper layers on eroded banks instead of the surface layer.")
    private boolean exposeCutStrata = true;
}
