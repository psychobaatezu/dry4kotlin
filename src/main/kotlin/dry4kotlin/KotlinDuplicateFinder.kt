package dry4kotlin

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.*
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

class KotlinDuplicateFinder {
    fun findDuplicates(): List<Candidate> = findDuplicates(Options.defaults())

    fun findDuplicates(options: Options): List<Candidate> {
        val entries = scan(options)
            .filter { it.lines >= options.minLines }
            .filter { it.nodes >= options.minNodes }
            .sortedBy { it.nodes }
        val candidates = mutableListOf<Candidate>()

        for (i in entries.indices) {
            for (j in i + 1 until entries.size) {
                val left = entries[i]
                val right = entries[j]
                val sizeRatio = left.nodes.toDouble() / right.nodes
                if (sizeRatio < options.sizeTolerance) break

                val score = similarity(left, right)
                if (!left.overlaps(right) && score.combined >= options.threshold) {
                    candidates += Candidate(
                        score = score.combined,
                        left = left.location(),
                        right = right.location(),
                        leftNodes = left.nodes,
                        rightNodes = right.nodes,
                        structureScore = score.structure,
                        sizeScore = score.size,
                        sharedNodes = score.sharedNodes,
                        leftKind = left.kind,
                        rightKind = right.kind,
                        suggestion = suggestionFor(left, right, options),
                        explanations = explanationsFor(left, right, score, options),
                    )
                }
            }
        }

        return candidates.sortedWith(
            compareByDescending<Candidate> { it.score }
                .thenBy { it.left.file }
                .thenBy { it.left.startLine }
                .thenBy { it.right.file }
                .thenBy { it.right.startLine },
        )
    }

    private fun scan(options: Options): List<Entry> = KotlinPsiParser().use { parser ->
        val pathFilter = PathFilter(options.includePatterns, options.excludePatterns)
        val files = defaultAwarePaths(options.paths)
            .flatMap { kotlinFiles(Path.of(it), pathFilter, options.includeScripts) }
            .distinct()
            .sorted()
        val parsedFiles = files.map { it to parseFile(parser, it) }
        val semanticIndex = SemanticIndex.build(parsedFiles.map { it.second })
        parsedFiles.flatMap { (file, ktFile) -> scanFile(file, ktFile, options, semanticIndex) }
    }

    private fun defaultAwarePaths(paths: List<String>): List<String> {
        if (paths != listOf("src")) return paths
        val sourceSetRoots = listOf(
            "src/main/kotlin",
            "src/test/kotlin",
            "src/commonMain/kotlin",
            "src/commonTest/kotlin",
            "src/jvmMain/kotlin",
            "src/jvmTest/kotlin",
            "src/androidMain/kotlin",
            "src/androidUnitTest/kotlin",
            "src/androidInstrumentedTest/kotlin",
            "src/iosMain/kotlin",
            "src/iosTest/kotlin",
        ).filter { Files.isDirectory(Path.of(it)) }
        return sourceSetRoots.ifEmpty { paths }
    }

    private fun kotlinFiles(path: Path, pathFilter: PathFilter, includeScripts: Boolean): List<Path> {
        if (path.isRegularFile() && isKotlinSource(path, includeScripts) && pathFilter.allows(path)) {
            return listOf(path)
        }
        if (!path.isDirectory()) {
            return emptyList()
        }
        try {
            Files.walk(path).use { walk ->
                return walk
                    .filter(Files::isRegularFile)
                    .filter { isKotlinSource(it, includeScripts) }
                    .filter { pathFilter.allows(it) }
                    .sorted()
                    .toList()
            }
        } catch (e: IOException) {
            throw IllegalStateException("Unable to scan $path", e)
        }
    }

    private fun isKotlinSource(path: Path, includeScripts: Boolean): Boolean =
        path.toString().endsWith(".kt") || (includeScripts && path.toString().endsWith(".kts"))

    private fun parseFile(parser: KotlinPsiParser, file: Path): KtFile = try {
        parser.parse(file)
    } catch (e: RuntimeException) {
        throw IllegalStateException("Unable to parse $file", e)
    }

    private fun scanFile(file: Path, ktFile: KtFile, options: Options, semanticIndex: SemanticIndex): List<Entry> {
        val normalizer = KotlinNormalizer(options.semanticHints, semanticIndex)
        val entries = mutableListOf<Entry>()
        collectEntries(file, ktFile.text, ktFile, options, normalizer, semanticIndex, 0, entries)
        return entries
    }

    private fun collectEntries(
        file: Path,
        text: String,
        element: PsiElement,
        options: Options,
        normalizer: KotlinNormalizer,
        semanticIndex: SemanticIndex,
        candidateDepth: Int,
        entries: MutableList<Entry>,
    ) {
        val kind = candidateKind(element)
        val isCandidate = kind != null && kind in options.candidateKinds
        val withinDepth = options.maxNestingDepth?.let { candidateDepth <= it } ?: true
        if (isCandidate && withinDepth && !semanticIndex.hasIgnoredAnnotation(element, options.ignoreAnnotations)) {
            entry(file, text, element, kind, normalizer, semanticIndex, options)?.let { entries += it }
            if (!options.nestedCandidates) return
        }
        val childDepth = if (kind != null) candidateDepth + 1 else candidateDepth
        element.children.forEach { collectEntries(file, text, it, options, normalizer, semanticIndex, childDepth, entries) }
    }

    private fun candidateKind(element: PsiElement): CandidateKind? = when (element) {
        is KtEnumEntry -> CandidateKind.ENUM_ENTRY
        is KtClassOrObject -> CandidateKind.CLASS
        is KtNamedFunction -> CandidateKind.FUNCTION
        is KtPrimaryConstructor,
        is KtSecondaryConstructor,
        -> CandidateKind.CONSTRUCTOR
        is KtProperty -> CandidateKind.PROPERTY
        is KtAnonymousInitializer -> CandidateKind.INITIALIZER
        is KtLambdaExpression -> CandidateKind.LAMBDA
        is KtObjectLiteralExpression -> CandidateKind.OBJECT_LITERAL
        else -> null
    }

    private fun entry(
        file: Path,
        text: String,
        element: PsiElement,
        kind: CandidateKind,
        normalizer: KotlinNormalizer,
        semanticIndex: SemanticIndex,
        options: Options,
    ): Entry? {
        val startOffset = element.textRange.startOffset.coerceIn(0, text.length)
        val endOffset = element.textRange.endOffset.coerceIn(startOffset, text.length)
        val startLine = lineForOffset(text, startOffset)
        val endLine = lineForOffset(text, (endOffset - 1).coerceAtLeast(startOffset))
        val comparableElement = comparableElement(element, options.compareMode) ?: return null
        val normalized = normalizer.normalize(comparableElement)
        return Entry(
            file = file.toString(),
            startLine = startLine,
            endLine = endLine,
            nodes = normalized.nodeCount(),
            weights = normalized.weightedFingerprints(),
            fingerprints = normalized.fingerprints(),
            kind = kind,
            semanticFacts = semanticIndex.explanationFor(element),
        )
    }

    private fun comparableElement(element: PsiElement, compareMode: CompareMode): PsiElement? {
        if (compareMode == CompareMode.DECLARATIONS) return element
        return when (element) {
            is KtDeclarationWithBody -> element.bodyExpression
            is KtProperty -> element.initializer ?: element.getter?.bodyExpression
            is KtAnonymousInitializer -> element.body
            is KtLambdaExpression -> element.functionLiteral.bodyExpression
            else -> element
        }
    }

    private fun lineForOffset(text: String, offset: Int): Int {
        var line = 1
        var index = 0
        val safeOffset = offset.coerceIn(0, text.length)
        while (index < safeOffset) {
            if (text[index] == '\n') line++
            index++
        }
        return line
    }

    private fun similarity(left: Entry, right: Entry): ScoreParts {
        val fingerprintsIntersection = left.fingerprints intersect right.fingerprints
        val fingerprintsUnion = left.fingerprints union right.fingerprints
        val structure = if (fingerprintsUnion.isEmpty()) 0.0 else fingerprintsIntersection.size.toDouble() / fingerprintsUnion.size
        val weightKeys = left.weights.keys + right.weights.keys
        val sharedNodes = weightKeys.sumOf { minOf(left.weights[it] ?: 0, right.weights[it] ?: 0) }
        val size = minOf(left.nodes, right.nodes).toDouble() / maxOf(left.nodes, right.nodes)
        val combined = structure * (0.75 + 0.25 * size)
        return ScoreParts(combined, structure, size, sharedNodes)
    }

    private fun suggestionFor(left: Entry, right: Entry, options: Options): String? = when {
        left.kind == CandidateKind.FUNCTION && right.kind == CandidateKind.FUNCTION && options.compareMode == CompareMode.BODIES ->
            "Consider extracting the repeated body into a private helper, extension function, or higher-order function."
        left.kind == CandidateKind.FUNCTION && right.kind == CandidateKind.FUNCTION && left.semanticFacts.any { it.contains("annotations:Composable") || it.contains("annotations:androidx.compose") } ->
            "Consider extracting a shared @Composable component or a reusable Modifier/content lambda."
        left.kind == CandidateKind.FUNCTION && right.kind == CandidateKind.FUNCTION ->
            "Consider extracting a shared private function, extension function, inline helper, or higher-order function."
        left.kind == CandidateKind.LAMBDA && right.kind == CandidateKind.LAMBDA ->
            "Consider extracting a named function, receiver lambda helper, or shared Flow/collection operator chain."
        left.kind == CandidateKind.CLASS && right.kind == CandidateKind.CLASS ->
            "Consider extracting shared behavior into an interface, sealed hierarchy, delegation helper, base class, or mapper."
        left.kind == CandidateKind.PROPERTY && right.kind == CandidateKind.PROPERTY ->
            "Consider extracting the repeated property initializer, computed property helper, or lazy delegate."
        left.kind == CandidateKind.CONSTRUCTOR && right.kind == CandidateKind.CONSTRUCTOR ->
            "Consider moving repeated initialization into an init helper, factory function, or delegated constructor."
        left.kind == right.kind -> "Consider extracting the repeated ${left.kind.cliName.removeSuffix("s")} structure into a Kotlin helper abstraction."
        else -> "Consider extracting the shared structure into an extension, delegate, helper function, or DSL builder."
    }

    private fun explanationsFor(left: Entry, right: Entry, score: ScoreParts, options: Options): List<String> {
        val explanations = mutableListOf<String>()
        explanations += "same-candidate-kind:${left.kind.cliName}/${right.kind.cliName}"
        explanations += "structure-score:${"%.2f".format(java.util.Locale.US, score.structure)}"
        explanations += "size-score:${"%.2f".format(java.util.Locale.US, score.size)}"
        explanations += "shared-normalized-nodes:${score.sharedNodes}"
        if (options.compareMode == CompareMode.BODIES) explanations += "compared:function-or-property-bodies"
        val sharedSemanticFacts = left.semanticFacts intersect right.semanticFacts.toSet()
        sharedSemanticFacts.take(5).forEach { explanations += "shared-semantic:$it" }
        return explanations
    }

    private data class ScoreParts(
        val combined: Double,
        val structure: Double,
        val size: Double,
        val sharedNodes: Int,
    )

    private data class Entry(
        val file: String,
        val startLine: Int,
        val endLine: Int,
        val nodes: Int,
        val weights: Map<String, Int>,
        val fingerprints: Set<String>,
        val kind: CandidateKind,
        val semanticFacts: List<String>,
    ) {
        val lines: Int = endLine - startLine + 1

        fun location(): Location = Location(file, startLine, endLine)

        fun overlaps(other: Entry): Boolean =
            file == other.file && startLine <= other.endLine && other.startLine <= endLine
    }

    private class PathFilter(includePatterns: List<String>, excludePatterns: List<String>) {
        private val includes = includePatterns.map { matcher(it) }
        private val excludes = excludePatterns.map { matcher(it) }

        fun allows(path: Path): Boolean {
            val normalized = Path.of(path.toString().replace('\\', '/'))
            val included = includes.isEmpty() || includes.any { it.matches(normalized) || it.matches(normalized.fileName) }
            val excluded = excludes.any { it.matches(normalized) || it.matches(normalized.fileName) }
            return included && !excluded
        }

        private fun matcher(pattern: String): PathMatcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
    }
}
