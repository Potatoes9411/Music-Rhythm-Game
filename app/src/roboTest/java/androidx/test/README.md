# Minimal androidx.test shim (test-only)

Robolectric depends on `androidx.test:monitor`, which is published only to Google's Maven
repository. That host is unreachable from the environment this project was built in, so these
few classes provide the tiny surface Robolectric's `RoboMonitoringInstrumentation` calls when
activities are driven with `Robolectric.buildActivity` (registries, lifecycle stages and no-op
monitors). They are written from scratch for this project, are never packaged into the APK and
are not needed at all when `androidx.test:monitor` is available (delete this directory then).
