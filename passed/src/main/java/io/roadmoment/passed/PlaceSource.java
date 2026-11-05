package io.roadmoment.passed;

import java.util.List;

/** Nearby place lookup. Callbacks may arrive on any thread. */
public interface PlaceSource {
    interface Callback { void onResult(List<Place> places); void onError(String message); }
    void nearby(double lat, double lon, int radiusM, Hints hints, Callback callback);
    String label();
}
