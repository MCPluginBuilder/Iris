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
}
