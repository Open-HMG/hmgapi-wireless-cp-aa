package android.hardware.usb;

public class UsbInterface {
    private final int cls;
    public UsbInterface(int cls) { this.cls = cls; }
    public int getInterfaceClass() { return cls; }
}
