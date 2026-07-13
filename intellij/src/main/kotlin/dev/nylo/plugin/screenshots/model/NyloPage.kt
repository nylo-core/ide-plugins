package dev.nylo.plugin.screenshots.model

/**
 * A page discovered from the app's `lib/routes/router.dart`.
 *
 * [route] is the navigable path the framework's screenshot driver will `routeTo`
 * (e.g. `/login`). [routeResolved] is false when the route string couldn't be
 * found in the page's source and was derived from the class name as a fallback.
 */
data class NyloPage(
    val className: String,
    val route: String,
    val displayName: String,
    val authenticated: Boolean,
    val routeResolved: Boolean,
)
