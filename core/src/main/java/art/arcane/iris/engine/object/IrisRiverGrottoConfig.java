package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Controls explicitly accepted coastal and inland river grottos.")
@Data
public class IrisRiverGrottoConfig {
    @Desc("Coastal grotto configuration.")
    private IrisCoastalRiverGrottoConfig coastal = new IrisCoastalRiverGrottoConfig();

    @Desc("Contained inland grotto configuration.")
    private IrisInlandRiverGrottoConfig inland = new IrisInlandRiverGrottoConfig();
}
