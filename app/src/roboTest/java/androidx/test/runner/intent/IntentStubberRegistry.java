package androidx.test.runner.intent;

public final class IntentStubberRegistry {
    private static volatile IntentStubber instance;
    private IntentStubberRegistry() {}
    public static void load(IntentStubber s) { instance = s; }
    public static boolean isLoaded() { return instance != null; }
    public static IntentStubber getInstance() {
        if (instance == null) throw new IllegalStateException("No intent stubber loaded");
        return instance;
    }
}
