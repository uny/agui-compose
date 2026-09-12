package dev.ynagai.agui.a2ui

/**
 * The names the A2UI-over-AG-UI convention is made of.
 *
 * None of these is in the AG-UI specification. They are what upstream's `@ag-ui/a2ui-middleware`
 * and `ag-ui-a2ui-toolkit` agreed on, read off their sources rather than a document, and a client
 * that wants to be drawn to by those agents has to agree to the same strings. Kept in one place so
 * that the day upstream renames one, the rename here is one line.
 */
public object AguiA2ui {
    /** The `activityType` an `ACTIVITY_SNAPSHOT` carrying an A2UI surface is stamped with. */
    public const val ACTIVITY_TYPE: String = "a2ui-surface"

    /**
     * The key under which a tool result, or an activity's content, carries an array of A2UI
     * envelopes: `{"a2ui_operations": [{"version": "v0.9", "createSurface": {...}}, ...]}`.
     */
    public const val OPERATIONS_KEY: String = "a2ui_operations"

    /**
     * The name of the tool an agent calls to draw a surface. Its arguments are `surfaceId`,
     * `components` and an optional `data` -- no `version` and, by design, no `catalogId`: the
     * catalog is the host's to choose, so a model cannot name one the client never registered.
     */
    public const val RENDER_TOOL_NAME: String = "render_a2ui"

    /**
     * The `description` of the `Context` entry a client sends to say which catalog it draws.
     *
     * Upstream's middleware reads the `catalogId` out of the entry's value and stamps it on the
     * `createSurface` it emits for a streamed render call, so a client that sends this never sees
     * a surface bound to a catalog it does not hold. The string is matched exactly by the
     * middleware and by the LangGraph adapter, em dash included.
     */
    public const val SCHEMA_CONTEXT_DESCRIPTION: String =
        "A2UI Component Schema — available components for generating UI surfaces. " +
            "Use these component names and properties when creating A2UI operations."

    /** The `version` upstream's toolkit and middleware put on every envelope they emit. */
    public const val UPSTREAM_VERSION: String = "v0.9"

    /**
     * The catalog upstream names when nothing else chose one. Not the id the v0.9 specification
     * gives its basic catalog (`.../v0_9/catalogs/basic/catalog.json`); the toolkit's constant
     * is this string, and it is the one on the wire.
     */
    public const val UPSTREAM_BASIC_CATALOG_ID: String =
        "https://a2ui.org/specification/v0_9/basic_catalog.json"

    /** The v0.9 specification's own id for its basic catalog. */
    public const val V09_BASIC_CATALOG_ID: String =
        "https://a2ui.org/specification/v0_9/catalogs/basic/catalog.json"

    /**
     * The v1.0 basic catalog's id -- the one `a2ui-compose`'s `BasicCatalog` registers under.
     *
     * A string here rather than a reference, because `BasicCatalog` lives in `a2ui-compose` and
     * this module carries no Compose. The two being equal is asserted by a test in the module
     * that holds both.
     */
    public const val V10_BASIC_CATALOG_ID: String =
        "https://a2ui.org/specification/v1_0/catalogs/basic/catalog.json"
}
