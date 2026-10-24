package dev.ynagai.agui.smoketest

import com.agui.core.types.Context
import com.agui.tools.AbstractToolExecutor
import dev.ynagai.a2ui.core.protocol.CatalogDefinition
import dev.ynagai.agui.a2ui.A2uiRequest
import dev.ynagai.agui.a2ui.RenderA2UiTool
import dev.ynagai.agui.a2ui.catalogContext

/**
 * Touches the three declarations `agui-a2ui` publishes on its four native targets only -- the
 * ones that name upstream's types -- from a shared source set of those four, the way a consumer
 * whose own `commonMain` has no web target would. That they resolve here is what says the
 * module's `upstreamMain` reached the published metadata, and was not left out of a variant, as
 * `withAndroidTarget()` once left it out of the Android one. See `docs/decisions/0017`.
 *
 * `A2uiRequest` takes `a2ui-compose`'s `CatalogDefinition`, and `catalogContext` hands back
 * upstream's `Context`, reached across `agui-a2ui`'s `api` scopes the way `SmokeProtocol.kt`
 * reaches upstream's `AbstractAgent`.
 */
@Suppress("unused")
internal fun request(catalog: CatalogDefinition): A2uiRequest = A2uiRequest(catalog)

@Suppress("unused")
internal fun context(catalog: CatalogDefinition): Context = catalogContext(catalog)

@Suppress("unused")
internal fun renderTool(): AbstractToolExecutor = RenderA2UiTool()
