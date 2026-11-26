package hmgapi.mods.wireless;

import android.content.ComponentName;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;

import hmgapi.hook.Car;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.ModEntry;
import hmgapi.hook.Points;

/**
 * The Wireless mod's hooks (the rest of the mod is file changes in patches.json). Turn-by-turn
 * on the cluster and HUD moved to its own mod, projection-tbt (AwsomeFox/hmg-api-turn-by-turn),
 * which works for wired phones too; the two work together in DiPO (dipo/Dipo's header).
 *
 * Phone Projection menu (setting "menu"): with the wireless CarPlay/AA features
 * on, HKMC_BtSettings opens the wireless projection page for Settings > Phone
 * Projection (no split screen, and only phones the stock wireless service paired).
 * Stock US/Canada opens Device Connections. On US/Canada units the hook on the
 * "open the wireless page" call does what the non-wireless branch does instead.
 * Through HMG-API's hook points (btsettings.projection_helper.*): HMG-API's map for
 * this car says what they are in this build.
 *
 * The car's "connect a phone" popup (same setting "menu"): Mobis_SystemPopup
 * (com.mobis.system) reads the wireless CarPlay/AA features when it starts, and with
 * either on its Settings button opens com.daudio.app.deviceconnections, an app only the
 * builds that ship wireless projection have. This mod turns those features on, so on the
 * US/Canadian builds that button crashed com.mobis.system (ActivityNotFoundException).
 * The hook on Activity.startActivityForResult in that app sends it to Phone Projection's
 * own Device Connections page (what the button opens with the features off), only when
 * the unit has no such activity (hook point framework.activity.start_activity_for_result).
 *
 * USB drives (setting "usb_drives"): the Android Auto app (HKMC_GPM) starts a wired
 * session for every USB_DEVICE_ATTACHED, so a USB drive (install stick, music) leaves
 * GalConnectionManager "Already AA Starting" with a wired start that never connects, and
 * wireless Android Auto is refused until the head unit restarts. The hook on
 * Context.startService in that app drops a wired start (startMode 1) for a device whose
 * interfaces are all mass storage (8) or hub (9). Phones and devices with no interfaces
 * start as before (hook point framework.context_impl.start_service).
 */
public final class Wireless extends ModEntry {
    private final IdentityRegistry identity = new IdentityRegistry();
    static final String BTSETTINGS = "com.android.btsettings";
    static final String SYSTEM_POPUP = "com.mobis.system";
    /** what the popup's Settings button opens with the wireless features on: not on US/Canadian builds */
    static final String INTEGRATED_PKG = "com.daudio.app.deviceconnections",
            INTEGRATED_CLASS = "com.daudio.app.deviceconnections.ui.connectivitySettings.PhoneProjectionSettingsActivity";
    /** what it opens with them off: Phone Projection's Device Connections page, on every build.
     *  Names, not ComponentNames: a static ComponentName would run Android code when the class loads */
    static final String PROJECTION_PKG = "com.daudio.app.phoneprojection",
            PROJECTION_CLASS = "com.daudio.app.phoneprojection.settings.ui.DeviceConnectionSettings";
    static final String GPM = "com.google.android.projection.sink";
    static final String GAL_SERVICE = "com.google.android.projection.service.GalReceiverService";
    static final int USB_CLASS_MASS_STORAGE = 8, USB_CLASS_HUB = 9;
    static final String HELPER = "btsettings.projection_helper";

    static final String DIPO = "com.daudio.app.dipo";
    static final String IAP2 = "com.tcc.iap2server";
    /** from DiPO's CarPlay Bluetooth bridge: start / stop an iAP2 session over Bluetooth */
    static final String BT_ATTACHED = "com.ev6.cpwl.BT_ATTACHED", BT_DETACHED = "com.ev6.cpwl.BT_DETACHED";
    static final String USB_CARPLAY = "android.hardware.usb.action.CARPLAY_DEVICE_ATTACHED";
    static final String USB_DIGITAL_AUDIO = "android.hardware.usb.action.DIGITAL_AUDIO_DEVICE_ATTACHED";
    /** the iAP2 component of the Bluetooth session we asked for (0: none) */
    static volatile int btComponent;

    @Override
    public void zygoteInit(final ModContext mod) {
        identity.logger(new IdentityRegistry.Logger() {
            @Override public void log(String message) { mod.log("CarPlay identity " + message); }
        });
        mod.publish(hmgapi.mods.wireless.api.CarPlayIdentification.SERVICE, identity);
    }

    @Override
    public void appLoaded(final ModContext mod, LoadedApp app) {
        if (IAP2.equals(app.packageName)) iap2(mod, app);
        if (GPM.equals(app.packageName) && mod.settingBoolean("usb_drives", true)) usbDrives(mod, app);
        if (BTSETTINGS.equals(app.packageName) && mod.settingBoolean("menu", true)) phoneProjectionMenu(mod, app);
        if (SYSTEM_POPUP.equals(app.packageName) && mod.settingBoolean("menu", true)) popupSettings(mod, app);
        if (DIPO.equals(app.packageName) && (mod.settingBoolean("bridge", true) || mod.settingBoolean("audio", true))) dipo(mod, app);
    }

    /**
     * DiPO (CarPlay): the Bluetooth bridge and Wi-Fi audio, in the mod's DiPO part
     * (hmgapi.mods.wireless.dipo.Dipo), which links against DiPO's iAP2 and Bluetooth
     * libraries.
     */
    private void dipo(ModContext mod, LoadedApp app) {
        try {
            mod.appPart(app, "hmgapi.mods.wireless.dipo.Dipo").getMethod("start", ModContext.class, LoadedApp.class).invoke(null, mod, app);
        } catch (Throwable t) {
            mod.error("DiPO part", t instanceof java.lang.reflect.InvocationTargetException ? t.getCause() : t);
        }
    }

    /**
     * The iAP2 server. Shared CarPlay identity ("shared_identity"): other mods' vehicle
     * identity goes into its identification. Bluetooth bridge ("bridge"): DiPO's bridge asks for an iAP2
     * session over Bluetooth (BT_ATTACHED / BT_DETACHED, from apps holding
     * BLUETOOTH_PRIVILEGED); a USB CarPlay attach ends it first, before the server's own
     * receiver handles that attach.
     */
    private void iap2(final ModContext mod, LoadedApp app) {
        final ClassLoader cl = app.classLoader;
        if (mod.settingBoolean("shared_identity", true)) IdentityInstaller.install(mod, app, identity);
        else mod.log("CarPlay identity sharing is off; contributions are not applied");
        if (!mod.settingBoolean("bridge", true)) return;
        mod.hook(cl, "iap2.service.on_create", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                final Object svc = p.thisObject;
                IntentFilter f = new IntentFilter();
                f.addAction(BT_ATTACHED);
                f.addAction(BT_DETACHED);
                ((Context) svc).registerReceiver(new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent i) {
                        try {
                            if (BT_ATTACHED.equals(i.getAction())) btAttach(mod, cl, svc, i);
                            else stopBt(mod, cl, svc, "detach");
                        } catch (Throwable t) {
                            mod.error("bridge " + i.getAction(), t);
                        }
                    }
                }, f, "android.permission.BLUETOOTH_PRIVILEGED", null);
                mod.log("bridge: iAP2 listens for DiPO's Bluetooth sessions");
            }
        });
        mod.hook(cl, "iap2.service_receiver.on_receive", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                Intent i = (Intent) p.args[1];
                String a = i == null ? null : i.getAction();
                if (!USB_CARPLAY.equals(a) && !USB_DIGITAL_AUDIO.equals(a)) return;
                try {
                    stopBt(mod, cl, Points.get(cl, "iap2.service_receiver.service", p.thisObject), "usb attach");
                } catch (Throwable t) {
                    mod.error("bridge: usb attach", t);
                }
            }
        });
    }

    static void btAttach(ModContext mod, ClassLoader cl, Object svc, Intent i) {
        int comp = i.getIntExtra("component", 4);
        if (comp < 3 || comp > 5) comp = 4;
        String serial = i.getStringExtra("serial");
        if (btComponent == 0 && Boolean.TRUE.equals(Points.call(cl, "iap2.service.is_connected", null))) {
            mod.log("bridge: attach ignored, another iAP2 session is up (component "
                    + Points.call(cl, "iap2.service.get_component", null) + ")");
            return;
        }
        stopBt(mod, cl, svc, "re-attach");
        btComponent = comp;
        Points.call(cl, "iap2.service.set_device_serial", svc, serial);
        Points.call(cl, "iap2.service.receive_connect", svc, comp);
        mod.log("bridge: Bluetooth iAP2 session requested, component " + comp + " serial " + serial);
    }

    static void stopBt(ModContext mod, ClassLoader cl, Object svc, String why) {
        int c = btComponent;
        if (c == 0) return;
        btComponent = 0;
        Points.call(cl, "iap2.service.set_device_serial", svc, (Object) null);
        Points.call(cl, "iap2.service.receive_disconnect", svc, c);
        mod.log("bridge: Bluetooth iAP2 session stopped, component " + c + " (" + why + ")");
    }

    private void usbDrives(final ModContext mod, LoadedApp app) {
        mod.hook(null, "framework.context_impl.start_service", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                Intent i = (Intent) p.args[0];
                ComponentName c = i == null ? null : i.getComponent();
                if (c == null || !GAL_SERVICE.equals(c.getClassName()) || i.getIntExtra("startMode", -1) != 1) return;
                UsbDevice d = i.getParcelableExtra("device");
                if (!notPhone(d)) return;
                p.setResult(null);
                mod.log("USB drive or hub (" + d.getDeviceName() + "), not starting wired Android Auto");
            }
        });
        mod.log("USB drive hook in " + app.processName);
    }

    /** true when the device has interfaces and every one is mass storage or a hub */
    static boolean notPhone(UsbDevice d) {
        if (d == null || d.getInterfaceCount() == 0) return false;
        for (int k = 0; k < d.getInterfaceCount(); k++) {
            int cls = d.getInterface(k).getInterfaceClass();
            if (cls != USB_CLASS_MASS_STORAGE && cls != USB_CLASS_HUB) return false;
        }
        return true;
    }

    /** the "connect a phone" popup's Settings button: Phone Projection's page when this build lacks the other */
    private void popupSettings(final ModContext mod, LoadedApp app) {
        mod.hook(null, "framework.activity.start_activity_for_result", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                Intent i = (Intent) p.args[0];
                ComponentName to = i == null ? null : i.getComponent();
                if (to == null || !INTEGRATED_PKG.equals(to.getPackageName()) || !INTEGRATED_CLASS.equals(to.getClassName())) return;
                Context c = (Context) p.thisObject;
                if (c.getPackageManager().resolveActivity(i, 0) != null) return;   // a build with that app: as stock
                i.setClassName(PROJECTION_PKG, PROJECTION_CLASS);
                mod.log("Phone Projection: the car's popup opens Phone Projection's settings (no Device Connections app on this build)");
            }
        });
        mod.log("Phone Projection popup hook in " + app.processName);
    }

    private void phoneProjectionMenu(final ModContext mod, LoadedApp app) {
        final ClassLoader cl = app.classLoader;
        mod.hook(cl, HELPER + ".open_wireless_page", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                int region = Car.region();
                if (region != Car.REGION_US && region != Car.REGION_CANADA) return;   // other regions: stock choice
                Context c = (Context) p.args[0];
                if (Boolean.TRUE.equals(Points.call(cl, HELPER + ".device_connections_available", null))) {
                    Points.call(cl, HELPER + ".open_device_connections", null, c, 1);
                } else {
                    Points.call(cl, HELPER + ".open_other_page", null, c);
                }
                p.setResult(null);
                mod.log("Phone Projection: Device Connections (US/Canada), not the wireless page");
            }
        });
        mod.log("Phone Projection menu hook in " + app.processName);
    }
}
