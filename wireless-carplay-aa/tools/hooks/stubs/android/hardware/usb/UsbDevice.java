package android.hardware.usb;

public class UsbDevice {
    private final int[] classes;
    public UsbDevice(int... classes) { this.classes = classes; }
    public String getDeviceName() { return "/dev/bus/usb/001/002"; }
    public int getInterfaceCount() { return classes.length; }
    public UsbInterface getInterface(int k) { return new UsbInterface(classes[k]); }
}
