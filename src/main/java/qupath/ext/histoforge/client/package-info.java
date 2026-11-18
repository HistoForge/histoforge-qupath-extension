/**
 * Authenticated clients for the HistoForge gateway.
 *
 * <p>{@link qupath.ext.histoforge.client.GatewayTransport} owns credentials, redirect policy,
 * bounded response reads, and retry-after-token-reload. {@link
 * qupath.ext.histoforge.client.ModuleGatewayClient} owns capabilities, module catalog, and
 * administrative endpoints. {@link qupath.ext.histoforge.client.ModelGatewayExecutor} owns source
 * publication and bounded finite-call execution. {@link
 * qupath.ext.histoforge.client.DurableModelRunExecutor} owns durable exact-model control and
 * independently validated immutable result downloads.
 */
package qupath.ext.histoforge.client;
