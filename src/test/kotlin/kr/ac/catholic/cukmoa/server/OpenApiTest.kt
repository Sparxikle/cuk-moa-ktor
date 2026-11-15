package kr.ac.catholic.cukmoa.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.application.plugin
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kr.ac.catholic.cukmoa.server.db.Database

// The OpenAPI spec (Swagger) and the real routes must list the same endpoints: a route added, removed or renamed
// without updating openapi/documentation.yaml fails here.
class OpenApiTest {
    private fun app(block: suspend io.ktor.server.testing.ApplicationTestBuilder.(io.ktor.server.application.Application) -> Unit) {
        val db = Database(File.createTempFile("openapi", ".db").apply { delete(); deleteOnExit() }.path)
        testApplication {
            lateinit var application: io.ktor.server.application.Application
            application {
                application = this
                module(db, loadCatalog(null), DepartmentService(apiKey = null, registry = SiteRegistry.parse(File("sites/department-sites.json").readText())))
            }
            startApplication()
            block(application)
        }
    }

    /** "GET /api/v1/courses/{id}" for every route, leaving out Swagger's own routes and the catch-all. */
    private fun routes(application: io.ktor.server.application.Application): Set<String> =
        application.plugin(RoutingRoot).getAllRoutes().mapNotNull { route ->
            val text = route.toString() // e.g. "/api/v1/courses/{id}/(method:GET)"
            val method = Regex("""\(method:(\w+)\)""").find(text)?.groupValues?.get(1) ?: return@mapNotNull null
            val path = text.substringBefore("/(method:").ifEmpty { "/" }
            if (path.startsWith("/swagger") || "{...}" in path) null else "$method $path"
        }.toSet()

    /** "GET /health" for every operation under `paths:` in the spec (two-space path keys, four-space method keys). */
    private fun specOperations(): Set<String> {
        val lines = File("src/main/resources/$OPENAPI_SPEC").readLines()
        val start = lines.indexOfFirst { it == "paths:" }
        val end = lines.indexOfFirst { it == "components:" }.takeIf { it > start } ?: lines.size
        var path: String? = null
        val ops = mutableSetOf<String>()
        for (line in lines.subList(start + 1, end)) {
            Regex("""^  (/\S*):\s*$""").find(line)?.let { path = it.groupValues[1]; return@let }
            Regex("""^    (get|post|put|patch|delete):\s*$""").find(line)?.let { m -> path?.let { ops += "${m.groupValues[1].uppercase()} $it" } }
        }
        return ops
    }

    @Test
    fun specListsExactlyTheRoutes() = app { application ->
        val routes = routes(application)
        val spec = specOperations()
        assertTrue(routes.size >= 13, "found only $routes")
        assertEquals(routes.sorted(), spec.sorted(), "routes not in the spec: ${routes - spec}; spec entries without a route: ${spec - routes}")
    }

    @Test
    fun swaggerUiIsServed() = app {
        val page = client.get("/swagger")
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue("swagger" in page.bodyAsText().lowercase())
        val spec = client.get("/swagger/documentation.yaml")
        assertEquals(HttpStatusCode.OK, spec.status)
        assertTrue(spec.bodyAsText().startsWith("openapi: 3"))
    }
}
