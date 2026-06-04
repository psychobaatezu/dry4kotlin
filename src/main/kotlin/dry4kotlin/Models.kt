package dry4kotlin

data class Candidate(
    val score: Double,
    val left: Location,
    val right: Location,
    val leftNodes: Int,
    val rightNodes: Int,
    val structureScore: Double = score,
    val sizeScore: Double = if (leftNodes == 0 || rightNodes == 0) 0.0 else minOf(leftNodes, rightNodes).toDouble() / maxOf(leftNodes, rightNodes),
    val sharedNodes: Int = 0,
    val leftKind: CandidateKind = CandidateKind.UNKNOWN,
    val rightKind: CandidateKind = CandidateKind.UNKNOWN,
    val suggestion: String? = null,
    val explanations: List<String> = emptyList(),
)

data class Location(
    val file: String,
    val startLine: Int,
    val endLine: Int,
)

data class DuplicateGroup(
    val score: Double,
    val locations: List<Location>,
    val kinds: Set<CandidateKind>,
    val suggestions: Set<String>,
    val explanations: Set<String> = emptySet(),
)

enum class CompareMode(val cliName: String) {
    DECLARATIONS("declarations"),
    BODIES("bodies"),
    ;

    companion object {
        fun parse(text: String): CompareMode {
            val normalized = text.trim().lowercase()
            return entries.firstOrNull { it.cliName == normalized || it.name.lowercase() == normalized }
                ?: throw IllegalArgumentException("Unknown compare mode: $text")
        }
    }
}

enum class CandidateKind(val cliName: String) {
    CLASS("classes"),
    FUNCTION("functions"),
    CONSTRUCTOR("constructors"),
    PROPERTY("properties"),
    INITIALIZER("initializers"),
    ENUM_ENTRY("enum-entries"),
    LAMBDA("lambdas"),
    OBJECT_LITERAL("object-literals"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun parseList(text: String): Set<CandidateKind> = text
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { parse(it) }
            .toSet()

        fun parse(text: String): CandidateKind {
            val normalized = text.trim().lowercase()
            return entries.firstOrNull { it.cliName == normalized || it.name.lowercase().replace('_', '-') == normalized }
                ?: throw IllegalArgumentException("Unknown candidate kind: $text")
        }
    }
}
