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

    @Desc("Wet channel width, depth, sink and roughness.")
    private IrisSurfaceRiverChannelConfig channel = new IrisSurfaceRiverChannelConfig();

    @Desc("Shore band and eroded valley around the channel.")
    private IrisSurfaceRiverBankConfig banks = new IrisSurfaceRiverBankConfig();
    @Desc("Material under and beside the water: falling-block replacement and padding.")
    private IrisSurfaceRiverBedConfig bed = new IrisSurfaceRiverBedConfig();

    @Desc("Cascade and waterfall thresholds.")
    private IrisSurfaceRiverFlowConfig flow = new IrisSurfaceRiverFlowConfig();

    @Desc("Coastal mouth flare and ocean-apron limits.")
    private IrisRiverMouthConfig mouths = new IrisRiverMouthConfig();
    @Desc("How the ground around the channel is eroded into a valley.")
    private IrisSurfaceRiverErosionConfig erosion = new IrisSurfaceRiverErosionConfig();
    @Desc("Ponds at the source and at the inland end of every surface river.")
    private IrisSurfaceRiverPondsConfig ponds = new IrisSurfaceRiverPondsConfig();
}
