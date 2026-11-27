package hmgapi.hook;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** calls recorded as "point arg..."; answers set per point */
public final class Points {
    public static final List<String> calls = new ArrayList<String>();
    public static final Map<String, Object> answers = new HashMap<String, Object>();
    public static final Object SERVICE = new Object();
    public static Object get(ClassLoader cl, String point, Object target) {
        if ("iap2.service_receiver.service".equals(point)) return SERVICE;
        throw new AssertionError("unexpected field: " + point);
    }
    public static Object call(ClassLoader cl, String point, Object target, Object... args) {
        StringBuilder b = new StringBuilder(point);
        for (Object a : args) b.append(' ').append(a instanceof android.content.Context ? "context" : a);
        calls.add(b.toString());
        return answers.get(point);
    }
}
