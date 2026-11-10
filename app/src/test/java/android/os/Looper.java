package android.os;

/**
 * JVM test implementation of Android's Looper.
 */
public class Looper {
    private static final Looper MAIN_LOOPER = new Looper(Thread.currentThread());
    private static final ThreadLocal<Looper> CURRENT_LOOPER = new ThreadLocal<>();

    private final Thread thread;

    private Looper(Thread thread) {
        this.thread = thread;
    }

    public static Looper getMainLooper() {
        return MAIN_LOOPER;
    }

    public static Looper myLooper() {
        Looper l = CURRENT_LOOPER.get();
        if (l == null && Thread.currentThread() == MAIN_LOOPER.thread) {
            return MAIN_LOOPER;
        }
        return l;
    }

    public static void setCurrentLooper(Looper looper) {
        CURRENT_LOOPER.set(looper);
    }

    public static void prepare() {
        if (CURRENT_LOOPER.get() != null) {
            throw new RuntimeException("Only one Looper may be created per thread");
        }
        CURRENT_LOOPER.set(new Looper(Thread.currentThread()));
    }

    public Thread getThread() {
        return thread;
    }
}
