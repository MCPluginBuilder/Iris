package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Controls terrain-guided surface rivers.")
@Data
public class IrisSurfaceRiverConfig {
    @Desc("Enable the independent surface-river source budget.")
    private boolean enabled = true;

    @Desc("Surface-river source allocation.")
    private IrisSurfaceRiverSourceConfig sources = new IrisSurfaceRiverSourceConfig();

    @Desc("Surface channel and surrounding terrain footprint.")
    private IrisSurfaceRiverChannelConfig channel = new IrisSurfaceRiverChannelConfig();

    @Desc("Pool, riffle, cascade, and waterfall classification thresholds.")
    private IrisRiverHydraulicsConfig hydraulics = new IrisRiverHydraulicsConfig();

    @Desc("Short ridge-bore configuration for otherwise viable surface routes.")
    private IrisRiverRidgeTunnelConfig ridgeTunnels = new IrisRiverRidgeTunnelConfig();

    @Desc("Coastal mouth leveling and ocean-apron limits.")
    private IrisRiverMouthConfig mouths = new IrisRiverMouthConfig();
}
