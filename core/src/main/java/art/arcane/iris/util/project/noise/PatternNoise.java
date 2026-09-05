package art.arcane.iris.util.project.noise;

abstract class PatternNoise implements NoiseGenerator, OctaveNoise {
    private int octaves = 1;
    private double bounding = 1D;

    @Override
    public final double noise(double x) {
        return noise(x, 0D);
    }

    @Override
    public final double noise(double x, double z) {
        return noise(x, 0D, z);
    }

    @Override
    public final double noise(double x, double y, double z) {
        if (octaves == 1) {
            return sample(x, y, z);
        }
        double sum = 0D;
        double frequency = 1D;
        double amplitude = 1D;
        for (int octave = 0; octave < octaves; octave++) {
            sum += sample(x * frequency, y * frequency, z * frequency) * amplitude;
            frequency *= 2D;
            amplitude *= 0.5D;
        }
        return sum * bounding;
    }

    @Override
    public final void setOctaves(int octaves) {
        this.octaves = Math.clamp(octaves, 1, 16);
        bounding = 1D / (2D - Math.scalb(1D, 1 - this.octaves));
    }

    protected abstract double sample(double x, double y, double z);
}
