/**
 * The HistoForge window's user interface, split by screen: {@code app} (the shell), {@code
 * project}, {@code modules} (installed-module library), and {@code jobs} (job activity).
 * Cross-screen UI state and styling shared by those screens — the {@link ModulesRegistry} module
 * view and {@link HistoforgeStyles} semantic styles — live at this top level. Domain logic lives in
 * sibling packages ({@code pipeline}, {@code state}, {@code data}, {@code modules}, {@code roi})
 * that these screens only consume.
 */
package qupath.ext.histoforge.ui;
