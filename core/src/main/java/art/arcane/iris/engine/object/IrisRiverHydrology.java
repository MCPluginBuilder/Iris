package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.ArrayType;
import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.volmlib.util.collection.KList;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Dimension-owned terrain-guided river configuration.")
@Data
public class IrisRiverHydrology {
    @Desc("Enable river planning for this dimension.")
    private boolean enabled = false;

    @Desc("Drainage sampling, refinement, and outlet configuration.")
    private IrisRiverRoutingConfig routing = new IrisRiverRoutingConfig();

    @Desc("Organic centerline, bed, wall, and descending-water geometry.")
    private IrisRiverGeometryConfig geometry = new IrisRiverGeometryConfig();

    @Desc("Surface river source, channel, hydraulic, ridge-tunnel, and mouth configuration.")
    private IrisSurfaceRiverConfig surface = new IrisSurfaceRiverConfig();

    @Desc("Independent underground river source and channel configuration.")
    private IrisUndergroundRiverConfig underground = new IrisUndergroundRiverConfig();

    @Desc("Contained coastal and inland grotto configuration.")
    private IrisRiverGrottoConfig grottos = new IrisRiverGrottoConfig();

    @ArrayType(min = 1, type = IrisRiverProfile.class)
    @Desc("Reusable river fluid profiles selected by river policies.")
    private KList<IrisRiverProfile> profiles = new KList<IrisRiverProfile>().qadd(new IrisRiverProfile());
}
