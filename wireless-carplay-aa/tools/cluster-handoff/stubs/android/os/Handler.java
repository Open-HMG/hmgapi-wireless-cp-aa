package android.os;
public class Handler {
    public boolean post(Runnable r) { android.content.Context.queue.add(r);return true; }
}
