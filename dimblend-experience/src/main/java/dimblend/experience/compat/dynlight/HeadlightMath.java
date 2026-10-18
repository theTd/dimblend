package dimblend.experience.compat.dynlight;

/** Headlight selection and falloff, independent of the client renderer. */
public final class HeadlightMath {
    private HeadlightMath() {}

    public static boolean matches(String namespace, String path) {
        return (namespace.equals("dndecor") && path.endsWith("_frontlight"))
                || (namespace.equals("bellsandwhistles") && path.equals("headlight"));
    }

    public static double light(double distanceSquared, double radius, int brightness) {
        if (brightness <= 0 || distanceSquared >= radius * radius) return 0;
        return (1.0D - Math.sqrt(distanceSquared) / radius) * brightness;
    }

    // A one-block halo covers samples at the boundary of neighboring render sections.
    public static int minimumSection(double coordinate, double radius) {
        return (int) Math.floor((coordinate - radius - 1.0D) / 16.0D);
    }

    public static int maximumSection(double coordinate, double radius) {
        return (int) Math.floor((coordinate + radius + 1.0D) / 16.0D);
    }
}
