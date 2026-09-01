package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.ArrayType;
import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import art.arcane.volmlib.util.collection.KList;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Controls bounded drainage planning and legal river outlets.")
@Data
public class IrisRiverRoutingConfig {
    @MinNumber(256)
    @MaxNumber(8192)
    @Desc("Hydrology planning tile size in blocks.")
    private int tileSize = 2048;

    @MinNumber(8)
    @MaxNumber(512)
    @Desc("Spacing in blocks between terrain samples used to build the drainage graph.")
    private int sampleSpacing = 64;

    @MinNumber(1)
    @MaxNumber(64)
    @Desc("Spacing in blocks used to refine accepted terrain-guided centerlines.")
    private int refinementSpacing = 8;

    @Desc("Minimum route and tributary lengths used to suppress chopped, clustered branches.")
    private IrisRiverBranchingConfig branching = new IrisRiverBranchingConfig();

    @MinNumber(256)
    @MaxNumber(32768)
    @Desc("Maximum bounded length in blocks of one accepted river route.")
    private int maximumRouteLength = 16384;

    @MinNumber(1)
    @MaxNumber(256)
    @Desc("Maximum drainage roots selected per planning tile. Fewer roots produce longer, more strongly branching river trees.")
    private int maximumOutletsPerTile = 4;

    @Desc("Allow rivers to terminate at an ocean reservoir.")
    private boolean oceanOutlets = true;

    @ArrayType(type = IrisRiverInlandOutlet.class)
    @Desc("Contained inland outlet kinds available when no legal ocean outlet is selected.")
    private KList<IrisRiverInlandOutlet> inlandOutlets = new KList<>();
}
