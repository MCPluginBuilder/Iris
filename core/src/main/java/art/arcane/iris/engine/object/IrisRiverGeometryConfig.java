package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Controls organic river routing, beds, carved walls, and descending water geometry.")
@Data
public class IrisRiverGeometryConfig {
    @Desc("Surface and underground centerline meanders.")
    private IrisRiverMeanderConfig meanders = new IrisRiverMeanderConfig();

    @Desc("Surface channel bed and bank shape.")
    private IrisRiverChannelShapeConfig surface = new IrisRiverChannelShapeConfig();

    @Desc("Underground channel bed and wall shape.")
    private IrisRiverChannelShapeConfig underground = new IrisRiverChannelShapeConfig();

    @Desc("Grotto pool bed and wall shape.")
    private IrisRiverChannelShapeConfig grottos = new IrisRiverChannelShapeConfig();

    @Desc("Cascade, cataract, waterfall, and sinkhole descent shape.")
    private IrisRiverDropShapeConfig drops = new IrisRiverDropShapeConfig();
}
