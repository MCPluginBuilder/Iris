package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Controls how a surface river descends.")
@Data
public class IrisSurfaceRiverFlowConfig {
    @MinNumber(1)
    @MaxNumber(8)
    @Desc("Shortest level run in blocks between one-block steps before consecutive steps are labelled a cascade.")
    private int cascadeRun = 2;

    @MinNumber(2)
    @MaxNumber(32)
    @Desc("Smallest natural cliff in blocks that becomes a waterfall instead of a cascade.")
    private int waterfallMinimumDrop = 6;

    @MinNumber(0)
    @MaxNumber(0.95)
    @Desc("Share of the half-width that stays at full bed depth in the carved throat of a waterfall or cascade before the bed rises to the edge; higher is a flatter, broader throat.")
    private double waterfallThalwegFraction = 0.65D;

    @MinNumber(1)
    @MaxNumber(8)
    @Desc("Smallest drop in blocks that scours a plunge basin into the bed below it; drops shorter than this leave the bed untouched.")
    private int plungeBasinMinimumDrop = 2;

    @MinNumber(0)
    @MaxNumber(8)
    @Desc("Length of the plunge basin downstream of a drop as a multiple of the channel half-width, never shorter than two stations; 0 gives the shortest basin.")
    private double plungeBasinLengthRatio = 2D;

    @MinNumber(0)
    @MaxNumber(8)
    @Desc("Extra bed depth in blocks scoured inside a plunge basin; 0 marks the basin without deepening it.")
    private int plungeBasinDepth = 1;
}
