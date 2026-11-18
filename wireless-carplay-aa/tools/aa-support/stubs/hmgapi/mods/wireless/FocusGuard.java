package hmgapi.mods.wireless;

import android.content.Context;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.ModContext;

public final class FocusGuard {
    public static int btAudioCalls;
    public static int gpmCalls;
    public static int dipoCalls;
    public static int aaConnectingCalls;

    public static void reset() {
        btAudioCalls = 0;
        gpmCalls = 0;
        dipoCalls = 0;
        aaConnectingCalls = 0;
    }

    public static boolean active(ModContext mod) {
        return mod != null && mod.settingBoolean("projection_focus", false);
    }

    public static void btAudio(ModContext mod, LoadedApp app) {
        btAudioCalls++;
    }

    public static void gpm(ModContext mod, LoadedApp app) {
        gpmCalls++;
    }

    public static void dipo(ModContext mod, LoadedApp app) {
        dipoCalls++;
    }

    public static void onAaConnecting(ModContext mod, Context context) {
        aaConnectingCalls++;
    }
}
