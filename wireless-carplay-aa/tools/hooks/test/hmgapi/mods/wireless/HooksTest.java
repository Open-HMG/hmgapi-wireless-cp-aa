package hmgapi.mods.wireless;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;

import hmgapi.hook.Car;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;

import java.util.Arrays;

/**
 * The Wireless mod's hooks off the car (turn-by-turn's are the projection-tbt mod's now): the
 * GPM USB-drive hook, the iAP2 bridge hooks, the BtSettings menu hook and the popup's.
 */
public final class HooksTest {
    static int fail, oks;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
        if (ok) oks++;
        else fail = 1;
    }

    static void eq(String what, Object want, Object got) {
        boolean ok = want == null ? got == null : want.equals(got);
        check(what + (ok ? "" : " (wanted " + want + ", got " + got + ")"), ok);
    }

    static ModContext load(String pkg, String... off) {
        ModContext m = new ModContext();
        for (String k : off) m.settings.put(k, false);
        new Wireless().appLoaded(m, new LoadedApp(pkg));
        return m;
    }

    static Object keys(ModContext m) {
        return m.hooks.keySet().toString();
    }

    public static void main(String[] a) throws Throwable {
        usbDrives();
        iap2();
        menu();
        popup();
        dipo();
        eq("navigation and other apps: no hooks", "[]", keys(load("com.hkmc.navi")));
        System.out.println(oks + " ok, " + (fail == 0 ? "0 failed" : "FAILED"));
        System.exit(fail);
    }

    static Intent galStart(int mode, UsbDevice d) {
        return new Intent("x").setComponent(new ComponentName(Wireless.GPM, Wireless.GAL_SERVICE)).putExtra("startMode", mode).putExtra("device", d);
    }

    static void usbDrives() throws Throwable {
        ModContext m = load(Wireless.GPM);
        eq("GPM: the USB-drive hook only", "[framework.context_impl.start_service]", keys(m));
        MethodHook h = m.hooks.get("framework.context_impl.start_service");
        check("a USB drive's wired start is dropped", h.before(null, galStart(1, new UsbDevice(8))));
        check("...and logged", m.logs.toString().contains("USB drive or hub (/dev/bus/usb/001/002), not starting wired Android Auto"));
        check("a hub with a drive behind it: dropped", h.before(null, galStart(1, new UsbDevice(9, 8))));
        check("a phone (vendor-specific interface): starts", !h.before(null, galStart(1, new UsbDevice(8, 255))));
        check("a device with no interfaces: starts", !h.before(null, galStart(1, new UsbDevice())));
        check("no device: starts", !h.before(null, galStart(1, null)));
        check("not a wired start (startMode 2): untouched", !h.before(null, galStart(2, new UsbDevice(8))));
        check("another service: untouched", !h.before(null, new Intent("x").setClassName(Wireless.GPM, "Other").putExtra("startMode", 1).putExtra("device", new UsbDevice(8))));
        check("no component: untouched", !h.before(null, new Intent("x")));
        eq("usb_drives off: no hook", "[]", keys(load(Wireless.GPM, "usb_drives")));
    }

    static void iap2() throws Throwable {
        IdentityInstaller.installs = 0;
        ModContext m = load(Wireless.IAP2);
        eq("iAP2: the bridge's hooks", "[iap2.service.on_create, iap2.service_receiver.on_receive]", keys(m));
        eq("iAP2: the shared identity installed", 1, IdentityInstaller.installs);
        // on_create: the bridge's receiver, for apps with BLUETOOTH_PRIVILEGED only
        Context svc = new Context();
        m.hooks.get("iap2.service.on_create").after(svc);
        check("on_create: a receiver for BT_ATTACHED and BT_DETACHED", svc.receiver != null
                && svc.filter.actions.equals(Arrays.asList(Wireless.BT_ATTACHED, Wireless.BT_DETACHED)));
        eq("...behind BLUETOOTH_PRIVILEGED", "android.permission.BLUETOOTH_PRIVILEGED", svc.permission);
        Points.calls.clear();
        Wireless.btComponent = 0;
        Points.answers.put("iap2.service.is_connected", Boolean.FALSE);
        svc.receiver.onReceive(svc, new Intent(Wireless.BT_ATTACHED).putExtra("component", 4).putExtra("serial", "AA:BB"));
        eq("attach: serial set, connect asked for component 4",
                "[iap2.service.is_connected, iap2.service.set_device_serial AA:BB, iap2.service.receive_connect 4]", Points.calls.toString());
        eq("attach: the session's component kept", 4, Wireless.btComponent);
        // a USB CarPlay attach ends the Bluetooth session first; other broadcasts don't
        MethodHook rx = m.hooks.get("iap2.service_receiver.on_receive");
        Points.calls.clear();
        check("receiver: another broadcast passes", !rx.before(null, null, new Intent("android.intent.action.BOOT_COMPLETED")));
        eq("...touching nothing", "[]", Points.calls.toString());
        check("receiver: a USB CarPlay attach still goes to the stock receiver", !rx.before(null, null, new Intent(Wireless.USB_CARPLAY)));
        eq("...after the Bluetooth session is stopped", "[iap2.service.set_device_serial null, iap2.service.receive_disconnect 4]", Points.calls.toString());
        eq("...and forgotten", 0, Wireless.btComponent);
        // an attach while another iAP2 session (USB) is up is ignored
        Points.calls.clear();
        Points.answers.put("iap2.service.is_connected", Boolean.TRUE);
        svc.receiver.onReceive(svc, new Intent(Wireless.BT_ATTACHED).putExtra("component", 9));
        check("attach while another session is up: ignored", Wireless.btComponent == 0 && !Points.calls.toString().contains("receive_connect"));
        Points.answers.clear();
        // settings
        eq("bridge off: no bridge hooks", "[]", keys(load(Wireless.IAP2, "bridge")));
        IdentityInstaller.installs = 0;
        load(Wireless.IAP2, "shared_identity");
        eq("shared_identity off: not installed", 0, IdentityInstaller.installs);
    }

    static void menu() throws Throwable {
        ModContext m = load(Wireless.BTSETTINGS);
        eq("BtSettings: the menu hook", "[btsettings.projection_helper.open_wireless_page]", keys(m));
        MethodHook h = m.hooks.get("btsettings.projection_helper.open_wireless_page");
        Context c = new Context();
        Car.region = Car.REGION_US;
        Points.calls.clear();
        Points.answers.put("btsettings.projection_helper.device_connections_available", Boolean.TRUE);
        check("US: the wireless page isn't opened", h.before(null, c));
        eq("...Device Connections is", "[btsettings.projection_helper.device_connections_available, btsettings.projection_helper.open_device_connections context 1]", Points.calls.toString());
        Car.region = Car.REGION_CANADA;
        Points.calls.clear();
        Points.answers.clear();
        check("Canada, no Device Connections: the wireless page isn't opened", h.before(null, c));
        eq("...the other page is", "[btsettings.projection_helper.device_connections_available, btsettings.projection_helper.open_other_page context]", Points.calls.toString());
        Car.region = Car.REGION_KOREA;
        Points.calls.clear();
        check("other regions: stock", !h.before(null, c) && Points.calls.isEmpty());
        Car.region = Car.REGION_US;
        eq("menu off: no hook", "[]", keys(load(Wireless.BTSETTINGS, "menu")));
    }

    static void popup() throws Throwable {
        ModContext m = load(Wireless.SYSTEM_POPUP);
        eq("the popup: its hook", "[framework.activity.start_activity_for_result]", keys(m));
        MethodHook h = m.hooks.get("framework.activity.start_activity_for_result");
        Context c = new Context();
        Intent i = new Intent("x").setClassName(Wireless.INTEGRATED_PKG, Wireless.INTEGRATED_CLASS);
        h.before(c, i);
        eq("no Device Connections app: Phone Projection's page instead", Wireless.PROJECTION_CLASS, i.getComponent().getClassName());
        c.pm.activities.add(Wireless.INTEGRATED_CLASS);
        i = new Intent("x").setClassName(Wireless.INTEGRATED_PKG, Wireless.INTEGRATED_CLASS);
        h.before(c, i);
        eq("a build with that app: as stock", Wireless.INTEGRATED_CLASS, i.getComponent().getClassName());
        eq("menu off: no popup hook", "[]", keys(load(Wireless.SYSTEM_POPUP, "menu")));
    }

    static void dipo() {
        ModContext m = load(Wireless.DIPO);
        eq("DiPO: its app part starts, no hooks from here", "1 []", m.dipoStarts + " " + keys(m));
        m = load(Wireless.DIPO, "bridge", "audio");
        eq("bridge and audio off: no DiPO part", 0, m.dipoStarts);
        check("no turn-by-turn hooks in GPM (projection-tbt's)", !keys(load(Wireless.GPM)).toString().contains("navigation"));
    }
}
