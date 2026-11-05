package io.roadmoment.passed;

/** Local flat-earth math. Good to well under 1% over the few hundred metres we search. */
final class Geo {
    private static final double R = 6371008.8;
    private Geo() {}

    /** East/north offset of (lat, lon) from the origin, in metres. */
    static double[] enu(double lat0, double lon0, double lat, double lon) {
        double x = Math.toRadians(lon - lon0) * R * Math.cos(Math.toRadians(lat0));
        double y = Math.toRadians(lat - lat0) * R;
        return new double[] { x, y };
    }

    static double distance(Trail.Fix a, Trail.Fix b) {
        double[] v = enu(a.lat, a.lon, b.lat, b.lon);
        return Math.hypot(v[0], v[1]);
    }
}
