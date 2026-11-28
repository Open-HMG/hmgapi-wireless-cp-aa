package android.content.pm;

import android.content.Intent;

import java.util.HashSet;
import java.util.Set;

public class PackageManager {
    public final Set<String> activities = new HashSet<String>();
    public ResolveInfo resolveActivity(Intent i, int flags) {
        return i.getComponent() != null && activities.contains(i.getComponent().getClassName()) ? new ResolveInfo() : null;
    }
}
