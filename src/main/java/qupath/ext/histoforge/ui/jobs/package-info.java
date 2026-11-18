/**
 * The Jobs tab — a live table of server-side jobs (publish / install / train). {@link JobsPoller}
 * keeps the shared job list fresh and reports finished jobs to the shell; {@link JobsTabController}
 * binds a {@link javafx.scene.control.TableView} to that list; {@link JobsTableColumns} builds the
 * columns + cell rendering (a running spinner, terminal done/error, a per-row Clear). Clearing a
 * row removes the server entry but never cancels the runner — it finishes on the server.
 */
package qupath.ext.histoforge.ui.jobs;
