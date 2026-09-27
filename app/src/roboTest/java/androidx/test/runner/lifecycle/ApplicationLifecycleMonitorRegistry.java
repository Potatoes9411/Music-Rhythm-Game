package androidx.test.runner.lifecycle;

public final class ApplicationLifecycleMonitorRegistry {
    private static volatile ApplicationLifecycleMonitor instance;
    private ApplicationLifecycleMonitorRegistry() {}
    public static void registerInstance(ApplicationLifecycleMonitor m) { instance = m; }
    public static ApplicationLifecycleMonitor getInstance() { return instance; }
}
