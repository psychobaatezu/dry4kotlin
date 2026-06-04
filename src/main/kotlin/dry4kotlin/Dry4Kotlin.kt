package dry4kotlin

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.exists

object Dry4Kotlin {
    val USAGE: String = listOf(
        "Usage: dry4kotlin [options] [file-or-directory ...]",
        "",
        "Options:",
        "  --threshold N          Minimum structural similarity score, default 0.82",
        "  --min-lines N          Minimum source lines in a candidate declaration, default 4",
        "  --min-nodes N          Minimum normalized syntax nodes, default 20",
        "  --format F             text, edn, json, or sarif, default text",
        "  --edn/--text/--json/--sarif",
        "  --kinds LIST           Candidate kinds: classes,functions,constructors,properties,initializers,enum-entries,lambdas,object-literals",
        "  --exclude-kinds LIST   Remove candidate kinds from the default set",
        "  --exclude-lambdas      Shortcut for --exclude-kinds lambdas",
        "  --max-nesting-depth N  Limit nested candidate roots by candidate-depth",
        "  --no-nested-candidates Report only outer candidate roots",
        "  --include GLOB         Include only matching .kt paths",
        "  --exclude GLOB         Exclude matching .kt paths; default excludes build/generated files",
        "  --no-default-excludes  Disable default build/generated excludes",
        "  --group                Group pairwise duplicates into clusters",
        "  --relative-paths       Print paths relative to the current directory",
        "  --show-snippets        Include source snippets in text/json output",
        "  --context-lines N      Source context lines for snippets, default 0",
        "  --suggestions          Include Kotlin-specific extraction suggestions",
        "  --semantic             Add source-level semantic resolution hints",
        "  --explain              Explain why candidates matched",
        "  --compare F            declarations or bodies, default declarations",
        "  --ignore-annotation A  Ignore declarations annotated with A; defaults include Generated and Compose Preview",
        "  --no-default-ignore-annotations",
        "  --include-scripts      Also scan .kts files; Gradle scripts remain excluded unless default excludes are disabled",
        "  --size-tolerance N     Skip pairs with node size ratio below N, default 0.50",
    ).joinToString(System.lineSeparator())

    @JvmStatic
    fun main(args: Array<String>) {
        val options = Options.parse(*args)
        if (options.help) {
            println(USAGE)
            return
        }

        val candidates = KotlinDuplicateFinder().findDuplicates(options)
        when (options.format) {
            "edn" -> println(toEdn(candidates, options))
            "json" -> println(toJson(candidates, options))
            "sarif" -> println(toSarif(candidates, options))
            "text" -> printText(candidates, options)
            else -> {
                System.err.println("Unknown format: ${options.format}")
                kotlin.system.exitProcess(2)
            }
        }
    }

    fun printText(candidates: List<Candidate>, options: Options = Options.defaults()) {
        if (candidates.isEmpty()) {
            println("No duplicate candidates found.")
            return
        }

        if (options.grouped) {
            DuplicateGrouper.group(candidates).forEachIndexed { index, group ->
                if (index > 0) println()
                println(formatGroup(group, options))
            }
            return
        }

        candidates.forEachIndexed { index, candidate ->
            if (index > 0) println()
            println(formatCandidate(candidate, options))
        }
    }

    fun formatCandidate(candidate: Candidate): String = formatCandidate(candidate, Options.defaults())

    fun formatCandidate(candidate: Candidate, options: Options): String = buildString {
        append("DUPLICATE score=${formatScore(candidate.score)}")
        append(" structure=${formatScore(candidate.structureScore)}")
        append(" size=${formatScore(candidate.sizeScore)}")
        if (candidate.sharedNodes > 0) append(" shared-nodes=${candidate.sharedNodes}")
        append('\n')
        append("  ${lineRange(candidate.left, options)}\n")
        append("  ${lineRange(candidate.right, options)}")
        if (options.showSuggestions && candidate.suggestion != null) {
            append("\n  suggestion: ${candidate.suggestion}")
        }
        if (options.explain && candidate.explanations.isNotEmpty()) {
            append("\n  matched because:")
            candidate.explanations.forEach { append("\n    - $it") }
        }
        if (options.showSnippets) {
            append("\n")
            append(snippet(candidate.left, options).prependIndent("  "))
            append("\n")
            append(snippet(candidate.right, options).prependIndent("  "))
        }
    }

    fun formatGroup(group: DuplicateGroup, options: Options): String = buildString {
        append("GROUP score=${formatScore(group.score)} size=${group.locations.size}")
        if (group.kinds.isNotEmpty()) append(" kinds=${group.kinds.joinToString(",") { it.cliName }}")
        group.locations.forEach { append("\n  ${lineRange(it, options)}") }
        if (options.showSuggestions && group.suggestions.isNotEmpty()) {
            append("\n  suggestions:")
            group.suggestions.forEach { append("\n    - $it") }
        }
        if (options.explain && group.explanations.isNotEmpty()) {
            append("\n  matched because:")
            group.explanations.forEach { append("\n    - $it") }
        }
        if (options.showSnippets) {
            group.locations.forEach {
                append("\n")
                append(snippet(it, options).prependIndent("  "))
            }
        }
    }

    fun toEdn(candidates: List<Candidate>): String = toEdn(candidates, Options.defaults())

    fun toEdn(candidates: List<Candidate>, options: Options): String {
        if (candidates.isEmpty()) return "{:candidates []}"
        return candidates.joinToString(
            prefix = "{:candidates\n [",
            separator = "\n  ",
            postfix = "]}",
        ) { candidate ->
            "{:score ${candidate.score}\n" +
                "   :structure-score ${candidate.structureScore}\n" +
                "   :size-score ${candidate.sizeScore}\n" +
                "   :shared-nodes ${candidate.sharedNodes}\n" +
                "   :left ${locationEdn(candidate.left, options)}\n" +
                "   :right ${locationEdn(candidate.right, options)}\n" +
                "   :left-kind :${candidate.leftKind.cliName}\n" +
                "   :right-kind :${candidate.rightKind.cliName}\n" +
                "   :explanations [${candidate.explanations.joinToString(" ") { "\"${escape(it)}\"" }}]\n" +
                "   :left-nodes ${candidate.leftNodes}\n" +
                "   :right-nodes ${candidate.rightNodes}}"
        }
    }

    fun toJson(candidates: List<Candidate>, options: Options = Options.defaults()): String {
        val groups = if (options.grouped) DuplicateGrouper.group(candidates) else emptyList()
        return buildString {
            append("{\n")
            append("  \"candidates\": [")
            candidates.forEachIndexed { index, candidate ->
                if (index > 0) append(',')
                append("\n    ")
                append(candidateJson(candidate, options).prependIndent("    ").trimStart())
            }
            append("\n  ]")
            if (options.grouped) {
                append(",\n  \"groups\": [")
                groups.forEachIndexed { index, group ->
                    if (index > 0) append(',')
                    append("\n    ")
                    append(groupJson(group, options).prependIndent("    ").trimStart())
                }
                append("\n  ]")
            }
            append("\n}")
        }
    }

    fun toSarif(candidates: List<Candidate>, options: Options = Options.defaults()): String = buildString {
        append("{\n")
        append("  \"version\": \"2.1.0\",\n")
        append("  \"runs\": [{\n")
        append("    \"tool\": {\"driver\": {\"name\": \"dry4kotlin\", \"rules\": [{\"id\": \"structural-duplicate\", \"name\": \"Structural duplicate\"}]}},\n")
        append("    \"results\": [")
        candidates.forEachIndexed { index, candidate ->
            if (index > 0) append(',')
            append("\n      {")
            append("\"ruleId\": \"structural-duplicate\", ")
            append("\"level\": \"warning\", ")
            append("\"message\": {\"text\": \"Structural duplicate score=${formatScore(candidate.score)}\"}, ")
            append("\"locations\": [")
            append(sarifLocation(candidate.left, options))
            append(',')
            append(sarifLocation(candidate.right, options))
            append("]}")
        }
        append("\n    ]\n")
        append("  }]\n")
        append("}")
    }

    private fun candidateJson(candidate: Candidate, options: Options): String = buildString {
        append("{\n")
        append("  \"score\": ${candidate.score},\n")
        append("  \"structureScore\": ${candidate.structureScore},\n")
        append("  \"sizeScore\": ${candidate.sizeScore},\n")
        append("  \"sharedNodes\": ${candidate.sharedNodes},\n")
        append("  \"left\": ${locationJson(candidate.left, options)},\n")
        append("  \"right\": ${locationJson(candidate.right, options)},\n")
        append("  \"leftKind\": \"${candidate.leftKind.cliName}\",\n")
        append("  \"rightKind\": \"${candidate.rightKind.cliName}\",\n")
        append("  \"leftNodes\": ${candidate.leftNodes},\n")
        append("  \"rightNodes\": ${candidate.rightNodes}")
        if (options.showSuggestions && candidate.suggestion != null) append(",\n  \"suggestion\": ${jsonString(candidate.suggestion)}")
        if (options.explain) append(",\n  \"explanations\": [${candidate.explanations.joinToString(",") { jsonString(it) }}]")
        if (options.showSnippets) {
            append(",\n  \"leftSnippet\": ${jsonString(snippet(candidate.left, options))},")
            append("\n  \"rightSnippet\": ${jsonString(snippet(candidate.right, options))}")
        }
        append("\n}")
    }

    private fun groupJson(group: DuplicateGroup, options: Options): String = buildString {
        append("{\n")
        append("  \"score\": ${group.score},\n")
        append("  \"locations\": [${group.locations.joinToString(",") { locationJson(it, options) }}],\n")
        append("  \"kinds\": [${group.kinds.joinToString(",") { jsonString(it.cliName) }}],\n")
        append("  \"suggestions\": [${group.suggestions.joinToString(",") { jsonString(it) }}]")
        if (options.explain) append(",\n  \"explanations\": [${group.explanations.joinToString(",") { jsonString(it) }}]")
        append("\n")
        append("}")
    }

    private fun locationJson(location: Location, options: Options): String =
        "{\"file\": ${jsonString(displayFile(location.file, options))}, \"startLine\": ${location.startLine}, \"endLine\": ${location.endLine}}"

    private fun sarifLocation(location: Location, options: Options): String =
        "{\"physicalLocation\": {\"artifactLocation\": {\"uri\": ${jsonString(displayFile(location.file, options))}}, " +
            "\"region\": {\"startLine\": ${location.startLine}, \"endLine\": ${location.endLine}}}}"

    private fun locationEdn(location: Location, options: Options): String =
        "{:file \"${escape(displayFile(location.file, options))}\", :start-line ${location.startLine}, :end-line ${location.endLine}}"

    private fun lineRange(location: Location, options: Options): String =
        "${displayFile(location.file, options)}:${location.startLine}-${location.endLine}"

    private fun displayFile(file: String, options: Options): String {
        if (!options.relativePaths) return file
        val path = Path.of(file)
        return runCatching { Path.of("").toAbsolutePath().relativize(path.toAbsolutePath()).toString() }.getOrElse { file }
    }

    private fun snippet(location: Location, options: Options): String {
        val path = Path.of(location.file)
        if (!path.exists()) return "${lineRange(location, options)}\n<file not found>"
        val lines = Files.readAllLines(path)
        val start = (location.startLine - options.contextLines).coerceAtLeast(1)
        val end = (location.endLine + options.contextLines).coerceAtMost(lines.size)
        return buildString {
            append(lineRange(location, options))
            for (line in start..end) {
                append('\n')
                append(line.toString().padStart(5))
                append(" | ")
                append(lines[line - 1])
            }
        }
    }

    private fun formatScore(score: Double): String = String.format(Locale.US, "%.2f", score)

    private fun escape(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun jsonString(text: String): String = buildString {
        append('"')
        text.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }
}
