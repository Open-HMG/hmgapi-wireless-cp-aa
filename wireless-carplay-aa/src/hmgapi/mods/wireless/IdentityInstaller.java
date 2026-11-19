package hmgapi.mods.wireless;

import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;
import hmgapi.mods.wireless.api.CarPlayIdentification;

/** Arms the native serializer from one validated snapshot of all contributions. */
final class IdentityInstaller {
    private IdentityInstaller() {}
    private static boolean loaded, armed;

    private static final class NativeFailure extends IllegalStateException {
        final int code;
        NativeFailure(int code) { super("native CarPlay identification=" + code); this.code = code; }
    }

    static void install(final ModContext mod, final LoadedApp app, IdentityRegistry registry) {
        final IdentityRegistry contributions = registry;
        registry.onChange(new Runnable() {
            @Override public void run() {
                try {
                    apply(contributions);
                    if (contributions.vehicle() != null) enableGroups(app.classLoader);
                }
                catch (Throwable t) { failure(mod, "CarPlay identification contribution change", t); }
            }
        });
        // The verified gate is safe while unarmed and permits consumers loaded live later.
        mod.nativePatch(app, "libiap2session.so");
        // Do not let the JNI library be the first code to dlopen the target: native dlopen
        // bypasses HMG-API's Runtime.load hooks, leaving the queued recipe unapplied.
        try {
            NativeIdentity.loadTarget();
            apply(registry);
        }
        catch (Throwable t) {
            // Identification is an optional extension of the iAP2 server. A native ABI
            // mismatch must not prevent Wireless from installing its bridge and TBT hooks.
            failure(mod, "CarPlay identification unavailable; Wireless continues without it", t);
        }
        MethodHook groups = new MethodHook() {
            @Override protected void afterHookedMethod(Param p) {
                try {
                    // appLoaded may precede the iAP2 server's native initialization. This
                    // point runs after it and before the identification message is built.
                    if (!armed) apply(contributions);
                    if (armed && contributions.vehicle() != null) enableGroups(app.classLoader);
                } catch (Throwable t) {
                    failure(mod, "CarPlay identification late initialization", t);
                }
            }
        };
        mod.hook(app.classLoader, "iap2.init_info.check_supports_features", groups);
        try { if (armed && registry.vehicle() != null) enableGroups(app.classLoader); }
        catch (Exception e) { throw new IllegalStateException("CarPlay identification groups", e); }
        mod.log(armed ? "CarPlay identification: contribution serializer installed"
                : "CarPlay identification: waiting for iAP2 native initialization");
    }

    private static synchronized void apply(IdentityRegistry registry) {
        CarPlayIdentification.VehicleIdentity vehicle = registry.vehicle();
        CarPlayIdentification.ThemeIdentity theme = registry.theme();
        if (vehicle == null && theme == null) { if (loaded) NativeIdentity.shutdown(); armed = false; return; }
        int[] connectorTypes = new int[vehicle == null ? 0 : vehicle.connectors.length];
        int[] connectorPowers = new int[connectorTypes.length];
        int dcCount = 0, acCount = 0;
        if (vehicle != null) {
            for (int i = 0; i < vehicle.connectors.length; i++) {
                CarPlayIdentification.Connector connector = vehicle.connectors[i];
                connectorTypes[i] = connector.type;
                connectorPowers[i] = connector.maximumPowerW;
                if (isAc(connector.type)) acCount++; else dcCount++;
            }
            if (dcCount == 0 || acCount == 0)
                throw new IllegalStateException("CarPlay EV identity requires at least one DC and one AC connector");
        }
        if (!loaded) { NativeIdentity.load(); loaded = true; }
        try {
            byte[] name = vehicle == null ? new byte[0] : vehicle.displayName.getBytes("UTF-8");
            byte[] themeId = theme == null ? new byte[0] : theme.encoded().getBytes("US-ASCII");
            int rc = NativeIdentity.configure(name, vehicle == null ? -1 : vehicle.engineType,
                    connectorTypes, connectorPowers, themeId);
            if (rc != 0) throw new NativeFailure(rc);
            armed = true;
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static boolean isAc(int type) {
        return type == CarPlayIdentification.VehicleIdentity.CONNECTOR_J1772
                || type == CarPlayIdentification.VehicleIdentity.CONNECTOR_MENNEKES
                || type == CarPlayIdentification.VehicleIdentity.CONNECTOR_GBT_AC
                || type == CarPlayIdentification.VehicleIdentity.CONNECTOR_NACS_AC;
    }

    private static void failure(ModContext mod, String message, Throwable t) {
        if (t instanceof NativeFailure && ((NativeFailure) t).code == -21)
            mod.log(message + ": native gate not ready; will retry before identification");
        else mod.error(message, t);
    }

    private static void enableGroups(ClassLoader cl) throws Exception {
        Points.field(cl, "iap2.init_info.use_vehicle_information").setInt(null, 1);
        Points.field(cl, "iap2.init_info.use_vehicle_status").setInt(null, 1);
    }
}
