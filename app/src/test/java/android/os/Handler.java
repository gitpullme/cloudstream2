package android.os;

/**
 * JVM test implementation of Android's Handler.
 */
public class Handler {
    private final Looper looper;

    public Handler() {
        this(Looper.myLooper() != null ? Looper.myLooper() : Looper.getMainLooper());
    }

    public Handler(Looper looper) {
        this.looper = looper;
    }

    public Looper getLooper() {
        return looper;
    }

    public boolean post(Runnable r) {
        if (r == null) return false;
        Looper prev = Looper.myLooper();
        try {
            Looper.setCurrentLooper(this.looper);
            r.run();
        } finally {
            Looper.setCurrentLooper(prev);
        }
        return true;
    }

    public void removeCallbacks(Runnable r) {}
}
