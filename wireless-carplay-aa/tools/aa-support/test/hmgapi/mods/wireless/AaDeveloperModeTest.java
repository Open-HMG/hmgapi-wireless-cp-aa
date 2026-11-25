package hmgapi.mods.wireless;

import hmgapi.hook.Bridge;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;
import java.io.File;

public final class AaDeveloperModeTest {
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    // Same JNI shape as the receiver, with an arbitrary method name: the mod must
    // resolve the mapped receiver's ABI without relying on an app method spelling.
    static class Receiver {
        static class CarInfo {
            private String manufacturer = "Hyundai", model = "Ioniq";
            private String headUnitMake = "Mobis", headUnitModel = "Gen5W";
            private String vehicleId = "fixture-id", modelYear = "2026", displayName = "fixture";
            private int driverPosition = 1, sessionConfiguration = 7;
            private boolean canPlayNativeMediaDuringVr = true;
            private Object extra = new Object();
            private CarInfo() {}
        }
        native void handoff(CarInfo info);
        native void other(String info);
    }
    static class Ambiguous extends Receiver {
        static class CarInfo {
            String manufacturer, model, headUnitMake, headUnitModel;
        }
        native void first(CarInfo info);
        native void second(CarInfo info);
    }
    static class WrongType {
        static class CarInfo { String manufacturer, model, headUnitMake; int headUnitModel; }
        native void handoff(CarInfo info);
    }
    static class StaticField {
        static class CarInfo { String manufacturer, model, headUnitMake; static String headUnitModel; }
        native void handoff(CarInfo info);
    }
    static class NonNative {
        void handoff(Receiver.CarInfo info) {}
    }

    private static ModContext load(String pkg, Boolean setting, Class<?> receiver) {
        Bridge.reset();
        Points.receiverClass = receiver;
        ModContext mod = new ModContext();
        mod.settings.put("tbt", false);
        mod.settings.put("usb_drives", false);
        if (setting != null) mod.settings.put("aa_developer_mode", setting);
        new Wireless(new File("missing-fixture.so")).appLoaded(mod, new LoadedApp(pkg));
        return mod;
    }

    public static void main(String[] args) throws Throwable {
        ModContext mod = load(Wireless.GPM, null, Receiver.class);
        check(Bridge.hook != null, "default enables developer mode without TBT or USB hooks");
        check(mod.natives.isEmpty() && mod.hooks.isEmpty(), "no TBT recipes or extra mapped hooks");
        check(Bridge.member.getDeclaringClass() == Receiver.class, "hooks the mapped receiver");
        Receiver.CarInfo original = new Receiver.CarInfo();
        MethodHook.Param call = Bridge.hook.before(new Receiver(), original);
        Receiver.CarInfo copy = (Receiver.CarInfo) call.args[0];
        check(copy != original, "sends a separate CarInfo instance");
        check("Google".equals(copy.manufacturer) && "Desktop Head Unit".equals(copy.model)
                && "Google".equals(copy.headUnitMake) && "Desktop Head Unit".equals(copy.headUnitModel),
                "all four fields match aa-proxy-rs");
        check("Hyundai".equals(original.manufacturer) && "Ioniq".equals(original.model)
                && "Mobis".equals(original.headUnitMake) && "Gen5W".equals(original.headUnitModel),
                "original identity stays intact");
        check(original.vehicleId.equals(copy.vehicleId) && original.modelYear.equals(copy.modelYear)
                && original.displayName.equals(copy.displayName) && original.driverPosition == copy.driverPosition
                && original.sessionConfiguration == copy.sessionConfiguration
                && original.canPlayNativeMediaDuringVr == copy.canPlayNativeMediaDuringVr && original.extra == copy.extra,
                "preserves every other field, including added fields");
        check(Bridge.hook.before(new Receiver(), original).args[0] != copy, "fresh identity for each handoff/reconnect");
        check(Bridge.hook.before(new Receiver(), new Object[] { null }).args[0] == null, "null passes through");
        check(Bridge.hook.before(new Receiver(), new Object()).args[0].getClass() == Object.class,
                "runtime mismatch passes through");
        check(Bridge.hook.before(new Receiver(), original).args[0] != original, "failure does not poison later handoffs");
        mod.settings.put("aa_developer_mode", false);
        check(Bridge.hook.before(new Receiver(), original).args[0] == original, "setting off passes through");
        mod.settings.put("aa_developer_mode", true);
        mod.enabled = false;
        check(Bridge.hook.before(new Receiver(), original).args[0] == original, "disabled mod passes through");
        load(Wireless.GPM, false, Receiver.class);
        check(Bridge.hook == null, "explicitly disabled setting installs no hook");
        load("unrelated.package", true, Receiver.class);
        check(Bridge.hook == null, "other apps unaffected");
        for (Class<?> incompatible : new Class<?>[] { null, WrongType.class, StaticField.class, NonNative.class, Ambiguous.class }) {
            ModContext rejected = load(Wireless.GPM, true, incompatible);
            check(Bridge.hook == null && rejected.logs.toString().contains("no compatible CarInfo handoff"),
                    "missing, changed or ambiguous ABI is rejected");
        }
        Bridge.reset();
        Points.receiverClass = null;
        System.out.println("ok    AA developer mode: " + checks + " checks (off-car only)");
    }
}
