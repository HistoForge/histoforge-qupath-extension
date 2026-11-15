/**
 * Typed image-action planning, detached execution, staging, persistence reduction, and bounded
 * multi-image scheduling.
 *
 * <p>{@link ImageActionExecutor} owns one image lease and final save decision; {@link
 * RecipeExecutor} stages model and ordinary-script work; {@link BatchOrchestrator} schedules
 * independent image actions and aggregates their typed terminal results.
 */
package qupath.ext.histoforge.pipeline;
