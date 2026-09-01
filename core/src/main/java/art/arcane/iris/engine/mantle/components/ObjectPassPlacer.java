package art.arcane.iris.engine.mantle.components;

import art.arcane.iris.engine.object.IObjectPlacer;
import org.jetbrains.annotations.Nullable;

public interface ObjectPassPlacer extends IObjectPlacer {
    <T> @Nullable T getDataIfPresent(int x, int y, int z, Class<T> type);

    byte[] getCarvedColumn(int x, int z, int height);

    @Override
    default <T> @Nullable T getData(int x, int y, int z, Class<T> type) {
        return getDataIfPresent(x, y, z, type);
    }
}
