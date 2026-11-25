package hmgapi.mods.wireless;

import hmgapi.hook.Bridge;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** The receiver's CarInfo -> JNI handoff, before service discovery is serialized. */
final class AaDeveloperMode {
    private AaDeveloperMode() {}

    // aa-proxy-rs/src/mitm.rs: DHU_MAKE, DHU_MODEL and the developer_mode SDR rewrite.
    static final String MAKE = "Google", MODEL = "Desktop Head Unit";
    private static final String[] IDENTITY = { "manufacturer", "model", "headUnitMake", "headUnitModel" };

    static void install(final ModContext mod, LoadedApp app) {
        try {
            final Handoff handoff = Handoff.find(Points.cls(app.classLoader, "gpm.receiver"));
            // The pinned SDK maps the receiver but not its CarInfo handoff. Resolve
            // this JNI ABI by shape, not an obfuscated app method name. JNI itself
            // requires these four field names and String types. Reject ambiguity.
            // A direct Bridge hook needs the enable/settings checks normally supplied
            // by ModContext's mapped hook guard; keep them on every invocation.
            Bridge.hookMethod(handoff.method, new MethodHook() {
                private boolean reportedFailure;
                @Override protected void beforeHookedMethod(Param p) {
                    if (!mod.enabled() || !mod.settingBoolean("aa_developer_mode", true) || p.args[0] == null) return;
                    try {
                        // Publish only a complete copy. Never mutate the receiver's
                        // saved identity, even if reflection fails midway through.
                        p.args[0] = handoff.copy(p.args[0]);
                    } catch (Exception e) {
                        if (!reportedFailure) {
                            reportedFailure = true;
                            mod.log("Android Auto: developer identity unavailable; using stock identity");
                        }
                    }
                }
            });
            mod.log("Android Auto: developer mode enabled (Google Desktop Head Unit identity)");
        } catch (Exception e) {
            // Optional: an unfamiliar receiver must not break projection, TBT or USB.
            // Do not log CarInfo or exception messages that could contain car data.
            mod.log("Android Auto: developer mode off; receiver has no compatible CarInfo handoff");
        }
    }

    private static final class Handoff {
        final Method method;
        final Constructor<?> constructor;
        final List<Field> fields = new ArrayList<Field>();
        final Field[] identity = new Field[IDENTITY.length];

        Handoff(Method method) throws Exception {
            this.method = method;
            Class<?> info = method.getParameterTypes()[0];
            if (info.getSuperclass() != Object.class) throw new NoSuchFieldException();
            constructor = info.getDeclaredConstructor();
            constructor.setAccessible(true);
            for (int i = 0; i < IDENTITY.length; i++) {
                Field f = info.getDeclaredField(IDENTITY[i]);
                if (f.getType() != String.class || Modifier.isStatic(f.getModifiers())
                        || Modifier.isFinal(f.getModifiers())) throw new NoSuchFieldException();
                f.setAccessible(true);
                identity[i] = f;
            }
            for (Field f : info.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (Modifier.isFinal(f.getModifiers())) throw new NoSuchFieldException();
                f.setAccessible(true);
                fields.add(f);
            }
        }

        static Handoff find(Class<?> receiver) throws Exception {
            Handoff found = null;
            for (Method m : receiver.getDeclaredMethods()) {
                if (!Modifier.isNative(m.getModifiers()) || Modifier.isStatic(m.getModifiers())
                        || m.getReturnType() != void.class || m.getParameterTypes().length != 1) continue;
                Class<?> info = m.getParameterTypes()[0];
                if (info.getDeclaringClass() != receiver) continue;
                Handoff candidate;
                try { candidate = new Handoff(m); }
                catch (ReflectiveOperationException incompatible) { continue; }
                if (found != null) throw new NoSuchMethodException("ambiguous CarInfo handoff");
                found = candidate;
            }
            if (found == null) throw new NoSuchMethodException("no CarInfo handoff");
            return found;
        }

        Object copy(Object original) throws Exception {
            Object result = constructor.newInstance();
            for (Field f : fields) f.set(result, f.get(original));
            identity[0].set(result, MAKE);
            identity[1].set(result, MODEL);
            identity[2].set(result, MAKE);
            identity[3].set(result, MODEL);
            return result;
        }
    }
}
