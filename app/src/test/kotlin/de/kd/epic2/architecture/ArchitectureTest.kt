package de.kd.epic2.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Guards the rules from CLAUDE.md: vertical slices, screaming package structure and the dependency rule.
 */
class ArchitectureTest {
    private val productionFiles = Konsist.scopeFromProduction().files

    @Test
    fun `production code lives in the base package`() {
        val violations =
            productionFiles
                .filterNot { it.packagee?.name?.startsWith(BASE_PACKAGE) == true }
                .map { it.path }

        assertEquals(emptyList(), violations)
    }

    @Test
    fun `domain kernel and use cases do not depend on frameworks or infrastructure`() {
        val violations =
            productionFiles
                .filter { it.isInPackage(DOMAIN_KERNEL) || it.name.removeSuffix(".kt").endsWith("UseCase") }
                .flatMap { file ->
                    file.imports
                        .filter { import -> FORBIDDEN_IN_DOMAIN.any { import.name.startsWith(it) } }
                        .map { import -> "${file.path} imports ${import.name}" }
                }

        assertEquals(emptyList(), violations)
    }

    @Test
    fun `slices do not import other slices`() {
        val violations =
            SLICES.flatMap { slice ->
                productionFiles
                    .filter { it.isInPackage(slice) }
                    .flatMap { file ->
                        file.imports
                            .filter { import -> (SLICES - slice).any { import.name.startsWith("$BASE_PACKAGE.$it.") } }
                            .map { import -> "${file.path} imports ${import.name}" }
                    }
            }

        assertEquals(emptyList(), violations)
    }

    private fun KoFileDeclaration.isInPackage(subPackage: String): Boolean {
        val packageName = packagee?.name ?: return false
        val fullName = "$BASE_PACKAGE.$subPackage"
        return packageName == fullName || packageName.startsWith("$fullName.")
    }

    private companion object {
        const val BASE_PACKAGE = "de.kd.epic2"
        const val DOMAIN_KERNEL = "place"

        val SLICES =
            listOf(
                "rememberplace",
                "editplace",
                "deleteplace",
                "listplaces",
                "showmap",
                "shareplace",
                "routetoplace",
                "receivegeolink",
                "backup",
            )

        val FORBIDDEN_IN_DOMAIN =
            listOf(
                "android.",
                "androidx.",
                "java.time.",
                "org.koin.",
                "$BASE_PACKAGE.infrastructure.",
            )
    }
}
