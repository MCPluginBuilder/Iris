package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class HydrologyEnumDescriptionsTest {
    private static final List<Class<? extends Enum<?>>> HYDROLOGY_ENUMS = List.of(
            IrisRiverPlacementMode.class,
            IrisRiverRoutingMode.class,
            IrisGrottoPoolLevel.class
    );

    @Test
    public void hydrologyEnumsDescribeThemselvesForTheSchema() {
        for (Class<? extends Enum<?>> type : HYDROLOGY_ENUMS) {
            Desc description = type.getAnnotation(Desc.class);
            assertNotNull(type.getSimpleName() + " needs a @Desc", description);
            assertFalse(type.getSimpleName() + " has a blank @Desc", description.value().isBlank());
        }
    }

    @Test
    public void everyHydrologyEnumConstantDescribesItselfForTheSchema() throws NoSuchFieldException {
        for (Class<? extends Enum<?>> type : HYDROLOGY_ENUMS) {
            for (Enum<?> constant : type.getEnumConstants()) {
                Field field = type.getField(constant.name());
                Desc description = field.getAnnotation(Desc.class);
                String label = type.getSimpleName() + "." + constant.name();
                assertNotNull(label + " needs a @Desc", description);
                assertFalse(label + " has a blank @Desc", description.value().isBlank());
            }
        }
    }
}
