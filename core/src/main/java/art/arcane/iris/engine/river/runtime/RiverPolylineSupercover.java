package art.arcane.iris.engine.river.runtime;

import art.arcane.iris.engine.river.RiverPolyline;

import java.util.Objects;

final class RiverPolylineSupercover {
    private static final double CORNER_EPSILON = 0.000000000001D;

    private RiverPolylineSupercover() {
    }

    static boolean all(RiverPolyline polyline, CellPredicate predicate) {
        Objects.requireNonNull(polyline);
        Objects.requireNonNull(predicate);
        double totalLength = polyline.length();
        for (int point = 0; point < polyline.size() - 1; point++) {
            if (!allSegment(polyline, point, totalLength, predicate)) {
                return false;
            }
        }
        return true;
    }

    private static boolean allSegment(
            RiverPolyline polyline,
            int point,
            double totalLength,
            CellPredicate predicate
    ) {
        double startX = polyline.x(point);
        double startZ = polyline.z(point);
        double endX = polyline.x(point + 1);
        double endZ = polyline.z(point + 1);
        double deltaX = endX - startX;
        double deltaZ = endZ - startZ;
        double segmentLength = StrictMath.hypot(deltaX, deltaZ);
        int cellX = clampFloor(startX + 0.5D);
        int cellZ = clampFloor(startZ + 0.5D);
        int endCellX = clampFloor(endX + 0.5D);
        int endCellZ = clampFloor(endZ + 0.5D);
        if (!visit(polyline, point, totalLength, predicate, cellX, cellZ)) {
            return false;
        }
        if (cellX == endCellX && cellZ == endCellZ) {
            return true;
        }

        int stepX = Double.compare(deltaX, 0D);
        int stepZ = Double.compare(deltaZ, 0D);
        double shiftedStartX = startX + 0.5D;
        double shiftedStartZ = startZ + 0.5D;
        double inverseDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 1D / StrictMath.abs(deltaX);
        double inverseDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 1D / StrictMath.abs(deltaZ);
        double nextBoundaryX = stepX > 0 ? cellX + 1D : cellX;
        double nextBoundaryZ = stepZ > 0 ? cellZ + 1D : cellZ;
        double maximumX = stepX == 0
                ? Double.POSITIVE_INFINITY
                : StrictMath.abs(nextBoundaryX - shiftedStartX) * inverseDeltaX;
        double maximumZ = stepZ == 0
                ? Double.POSITIVE_INFINITY
                : StrictMath.abs(nextBoundaryZ - shiftedStartZ) * inverseDeltaZ;

        while (cellX != endCellX || cellZ != endCellZ) {
            double difference = maximumX - maximumZ;
            if (difference < -CORNER_EPSILON) {
                cellX += stepX;
                maximumX += inverseDeltaX;
                if (!visit(polyline, point, totalLength, predicate, cellX, cellZ)) {
                    return false;
                }
                continue;
            }
            if (difference > CORNER_EPSILON) {
                cellZ += stepZ;
                maximumZ += inverseDeltaZ;
                if (!visit(polyline, point, totalLength, predicate, cellX, cellZ)) {
                    return false;
                }
                continue;
            }

            int nextCellX = cellX + stepX;
            int nextCellZ = cellZ + stepZ;
            if (stepX != 0 && !visit(polyline, point, totalLength, predicate, nextCellX, cellZ)) {
                return false;
            }
            if (stepZ != 0 && !visit(polyline, point, totalLength, predicate, cellX, nextCellZ)) {
                return false;
            }
            cellX = nextCellX;
            cellZ = nextCellZ;
            maximumX += inverseDeltaX;
            maximumZ += inverseDeltaZ;
            if (!visit(polyline, point, totalLength, predicate, cellX, cellZ)) {
                return false;
            }
        }
        return true;
    }

    private static boolean visit(
            RiverPolyline polyline,
            int point,
            double totalLength,
            CellPredicate predicate,
            int cellX,
            int cellZ
    ) {
        double startX = polyline.x(point);
        double startZ = polyline.z(point);
        double deltaX = polyline.x(point + 1) - startX;
        double deltaZ = polyline.z(point + 1) - startZ;
        double segmentLengthSquared = deltaX * deltaX + deltaZ * deltaZ;
        double projection = segmentLengthSquared == 0D
                ? 0D
                : ((cellX - startX) * deltaX + (cellZ - startZ) * deltaZ) / segmentLengthSquared;
        double factor = Math.max(0D, Math.min(1D, projection));
        double alongReach = totalLength == 0D
                ? 0D
                : (polyline.cumulativeLength(point) + StrictMath.sqrt(segmentLengthSquared) * factor) / totalLength;
        return predicate.test(cellX, cellZ, Math.max(0D, Math.min(1D, alongReach)));
    }

    private static int clampFloor(double value) {
        return (int) StrictMath.max(Integer.MIN_VALUE, StrictMath.min(Integer.MAX_VALUE, StrictMath.floor(value)));
    }

    @FunctionalInterface
    interface CellPredicate {
        boolean test(int blockX, int blockZ, double alongReach);
    }
}
