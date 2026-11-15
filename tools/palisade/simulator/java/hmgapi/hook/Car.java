package hmgapi.hook;
public final class Car {
    public static final int DISTANCE_KM=0, DISTANCE_MILES=1;
    public static int unit=DISTANCE_MILES;
    public static int distanceUnit(android.content.Context c) { return unit; }
    public static boolean androidAutoGuiding(android.content.Context c) { return false; }
}
