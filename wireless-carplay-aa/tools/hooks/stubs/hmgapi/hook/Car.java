package hmgapi.hook;

public final class Car {
    public static final int REGION_US = 1, REGION_CANADA = 4, REGION_KOREA = 0;
    public static int region = REGION_US;
    public static int region() { return region; }
}
