/**
 * The Modules tab browses the gateway's installed catalog from the shared {@link
 * qupath.ext.histoforge.ui.ModulesRegistry}. {@link ModulesTabController} owns selection, filtering
 * and administrative actions. {@link ModuleDetailsView} shows the selected module's saved
 * documentation and report action; {@link ModuleWorkflowView} projects its ordered recipe and
 * read-only settings. {@link ModuleReadmeView} renders Markdown natively without remote resources.
 * Install and Publish submit jobs through the existing dialogs; opening details performs no IO.
 */
package qupath.ext.histoforge.ui.modules;
