package qupath.ext.histoforge.pipeline;

import groovy.lang.GroovyShell;

/** Internal owner of the class-loader policy for trusted module Groovy scripts. */
public final class ScriptRuntime {

    private ScriptRuntime() {}

    /**
     * Create a shell that can resolve both host APIs and extension-owned classes.
     *
     * <p>QuPath loads extensions below the host loader, so Groovy's default host-owned loader
     * cannot see HistoForge classes. The thread context loader is not a stable host contract;
     * anchor compilation to this extension class's defining loader instead.
     */
    public static GroovyShell newShell() {
        return new GroovyShell(ScriptRuntime.class.getClassLoader());
    }
}
