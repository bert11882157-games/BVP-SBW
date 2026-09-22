package com.atsuishio.superbwarfare.api.diagnostics;

import com.atsuishio.superbwarfare.api.performance.ClientRenderPerformanceDiagnostics;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.sun.source.util.JavacTask;
import com.yourname.berts_vehicle_pack.diagnostics.BvpFlightClientControl;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Separate JVM/classpath fixtures exercise the real packaged-resource switch without a world. */
public final class DebugFeaturePolicyTest {
    private static int checks;

    private static void expect(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("parse")) {
            var compiler = ToolProvider.getSystemJavaCompiler();
            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            try (var files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
                var inputs = files.getJavaFileObjectsFromStrings(Arrays.asList(args).subList(1, args.length));
                JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostics,
                        List.of("-proc:none", "-source", "17"), null, inputs);
                for (var ignored : task.parse()) { }
                for (var diagnostic : diagnostics.getDiagnostics()) {
                    if (diagnostic.getKind() == Diagnostic.Kind.ERROR) throw new AssertionError(diagnostic.toString());
                }
            }
            System.out.println("PASS Java parser: " + (args.length - 1) + " owned sources");
            return;
        }
        boolean allowed = Boolean.parseBoolean(args[0]);
        expect(DebugFeaturePolicy.allowsDebugTools() == allowed, "classpath marker policy");
        System.setProperty("bvp.diagnostics.scenarios", "true");
        System.setProperty("bvp.diagnostics.flight", "true");
        System.setProperty("bvp.debug.modelLoading", "true");
        expect(DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios") == allowed,
                "fixture property cannot override artifact policy");
        expect(DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.debug.modelLoading") == allowed,
                "automatic developer logging cannot override artifact policy");
        expect(BvpFlightClientControl.enabled() == allowed, "real BVP scenario consumer shares SBW gate");
        System.setProperty("bvp.diagnostics.flight", "false");
        expect(!BvpFlightClientControl.enabled(), "existing dev opt-in remains required");
        ClientRenderPerformanceDiagnostics.setEnabled(true);
        expect(ClientRenderPerformanceDiagnostics.isEnabled() == allowed, "direct counter enable obeys policy");
        ClientRenderPerformanceDiagnostics.recordFrameBoundary(100, true);
        ClientRenderPerformanceDiagnostics.recordFrameBoundary(200, true);
        expect(ClientRenderPerformanceDiagnostics.snapshot().frameIntervals() == (allowed ? 1 : 0),
                "disabled counters cannot collect hidden render work");
        expect((ClientRenderPerformanceDiagnostics.startTimer() != ClientRenderPerformanceDiagnostics.TIMER_DISABLED) == allowed,
                "disabled timers do not run");
        ClientRenderPerformanceDiagnostics.setEnabled(false);
        expect(!ClientRenderPerformanceDiagnostics.isEnabled(), "off remains off");
        if (!allowed) {
            EliteDiagnostics.setClientSession(new UUID(1, 2), true);
            expect(!EliteDiagnostics.isClientEnabled() && !EliteDiagnostics.isServerEnabled()
                    && EliteDiagnostics.clientSessionId() == null, "remote enable packet cannot open a client sink");
        }
        for (int i = 0; i < 1000; i++) expect(DebugFeaturePolicy.allowsDebugTools() == allowed, "cached policy stable");
        System.out.println("PASS " + checks + " artifact policy, actual BVP consumer, sink and counter checks; allowed=" + allowed);
    }
}
