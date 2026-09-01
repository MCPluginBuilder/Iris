package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Controls low-volume cascades, falling faces, and organic receiving basins.")
@Data
public class IrisRiverDropShapeConfig {
    @MinNumber(1)
    @MaxNumber(16)
    @Desc("Preferred horizontal cascade run per block of head loss.")
    private int cascadeRunPerBlock = 2;

    @MinNumber(0.25)
    @MaxNumber(6)
    @Desc("Exponent of the graded cascade profile. Values above one accelerate toward the receiver.")
    private double cascadeExponent = 1.4D;

    @MinNumber(1)
    @MaxNumber(4)
    @Desc("Maximum head loss between adjacent underground drop faces. Exposed cascades always use one-block steps.")
    private int maximumCascadeStep = 2;

    @MinNumber(0.25)
    @MaxNumber(1)
    @Desc("Drop-flow width as a fraction of the connected channel width.")
    private double flowWidthRatio = 0.45D;

    @MinNumber(1)
    @MaxNumber(16)
    @Desc("Maximum wetted depth along a descending flow path.")
    private int maximumFlowDepth = 2;

    @MinNumber(1)
    @MaxNumber(4)
    @Desc("Receiving-basin width as a fraction of the descending flow width.")
    private double basinWidthRatio = 1.8D;

    @MinNumber(1)
    @MaxNumber(32)
    @Desc("Maximum receiving-basin depth after drop-scaled erosion.")
    private int maximumBasinDepth = 8;
}
