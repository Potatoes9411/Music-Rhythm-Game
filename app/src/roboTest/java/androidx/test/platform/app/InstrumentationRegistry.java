package androidx.test.platform.app;

import android.app.Instrumentation;
import android.os.Bundle;

public final class InstrumentationRegistry {
    private static volatile Instrumentation instrumentation;
    private static volatile Bundle arguments;

    private InstrumentationRegistry() {}

    public static void registerInstance(Instrumentation i, Bundle args) { instrumentation = i; arguments = args; }

    public static Instrumentation getInstrumentation() {
        if (instrumentation == null) throw new IllegalStateException("No instrumentation registered");
        return instrumentation;
    }

    public static Bundle getArguments() { return arguments == null ? new Bundle() : new Bundle(arguments); }
}
