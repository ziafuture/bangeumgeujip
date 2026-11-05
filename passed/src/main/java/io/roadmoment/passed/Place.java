package io.roadmoment.passed;

/** A point of interest from whichever place-search provider is used. */
public final class Place {
    public final String id, name, category, url, address, phone; public final double lat, lon;
    public Place(String id, String name, String category, double lat, double lon, String url, String address, String phone) {
        this.id = id; this.name = name; this.category = category == null ? "" : category; this.lat = lat; this.lon = lon;
        this.url = url == null ? "" : url; this.address = address == null ? "" : address; this.phone = phone == null ? "" : phone;
    }
    public Place(String id, String name, String category, double lat, double lon, String url) { this(id, name, category, lat, lon, url, "", ""); }
    public Place(String id, String name, String category, double lat, double lon) { this(id, name, category, lat, lon, ""); }

    /** Last segment of a "음식점 > 카페 > 커피전문점" style category, for speech. */
    public String shortCategory() {
        int i = category.lastIndexOf('>');
        return (i < 0 ? category : category.substring(i + 1)).trim();
    }
}
