package dry4kotlin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class KotlinDuplicateFinderTest {
  @Test
  fun reportsStructuralDuplicateCandidatesWithFileAndLineRanges() {
    val dir = Files.createTempDirectory("dry4kotlin")
    val left =
      writeSource(
        dir,
        "Left.kt",
        """
            package sample

            class Left {
                fun alpha(xs: List<Int>): Int {
                    val ys = xs.filter { x -> x % 2 == 1 }
                    return ys.map { x -> x + 1 }.sum()
                }
            }
        """.trimIndent(),
      )
    val right =
      writeSource(
        dir,
        "Right.kt",
        """
            package sample

            class Right {
                fun beta(items: List<Int>): Int {
                    val kept = items.filter { item -> item % 2 == 0 }
                    return kept.map { item -> item - 1 }.sum()
                }
            }
        """.trimIndent(),
      )

    val candidates = KotlinDuplicateFinder().findDuplicates(
      Options(listOf(dir.toString()), 0.50, 3, 8, "text", false),
    )

    val candidate = candidates
      .filter { it.left.file == left.toString() }
      .filter { it.right.file == right.toString() }
      .first { it.left.startLine == 4 && it.right.startLine == 4 }
    assertEquals(4, candidate.left.startLine)
    assertEquals(7, candidate.left.endLine)
    assertEquals(4, candidate.right.startLine)
    assertEquals(7, candidate.right.endLine)
  }

  @Test
  fun matchesDataClassesWithDifferentNamesAndLiteralValues() {
    val dir = Files.createTempDirectory("dry4kotlin")
    writeSource(
      dir,
      "One.kt",
      """
            data class Invoice(val id: String, val amount: Int) {
                fun payable(): Boolean {
                    return id != "" && amount > 0
                }
            }
      """.trimIndent(),
    )
    writeSource(
      dir,
      "Two.kt",
      """
            data class Receipt(val code: String, val total: Int) {
                fun closed(): Boolean {
                    return code != "closed" && total > 10
                }
            }
      """.trimIndent(),
    )

    val candidates = KotlinDuplicateFinder().findDuplicates(
      Options(listOf(dir.toString()), 0.80, 3, 8, "text", false),
    )

    assertTrue(candidates.any { it.left.file.endsWith("One.kt") && it.right.file.endsWith("Two.kt") })
  }

  @Test
  fun matchesEnumsAndConstantsStructurally() {
    val dir = Files.createTempDirectory("dry4kotlin")
    writeSource(
      dir,
      "One.kt",
      """
            enum class One(private val code: Int) {
                READY(1), DONE(2);
                fun active(): Boolean {
                    return code > 0
                }
            }
      """.trimIndent(),
    )
    writeSource(
      dir,
      "Two.kt",
      """
            enum class Two(private val value: Int) {
                OPEN(10), CLOSED(20);
                fun valid(): Boolean {
                    return value > 10
                }
            }
      """.trimIndent(),
    )

    val candidates = KotlinDuplicateFinder().findDuplicates(
      Options(listOf(dir.toString()), 0.70, 3, 8, "text", false),
    )

    assertTrue(candidates.any { it.left.file.endsWith("One.kt") && it.right.file.endsWith("Two.kt") })
  }

  @Test
  fun filtersCandidatesShorterThanTheMinimumLineCount() {
    val dir = Files.createTempDirectory("dry4kotlin")
    writeSource(dir, "One.kt", "class One { fun a(x: Int): Int = x + 1 }\n")
    writeSource(dir, "Two.kt", "class Two { fun b(y: Int): Int = y + 2 }\n")

    val candidates = KotlinDuplicateFinder().findDuplicates(
      Options(listOf(dir.toString()), 0.80, 3, 1, "text", false),
    )

    assertEquals(emptyList<Candidate>(), candidates)
  }

  @Test
  fun parsesCommandLineOptionsAndPaths() {
    val options = Options.parse(
      "--threshold",
      "0.9",
      "--min-lines",
      "5",
      "--min-nodes",
      "30",
      "--edn",
      "spec",
    )

    assertEquals(listOf("spec"), options.paths)
    assertEquals(0.9, options.threshold)
    assertEquals(5, options.minLines)
    assertEquals(30, options.minNodes)
    assertEquals("edn", options.format)
  }

  @Test
  fun defaultsToSrcWhenNoPathsAreProvided() {
    assertEquals(listOf("src"), Options.parse().paths)
  }

  @Test
  fun parsesKotlinSpecificOptions() {
    val options = Options.parse(
      "--json",
      "--kinds", "functions,classes",
      "--exclude-lambdas",
      "--max-nesting-depth", "1",
      "--no-nested-candidates",
      "--include", "**/src/**",
      "--exclude", "**/build/**",
      "--group",
      "--relative-paths",
      "--show-snippets",
      "--context-lines", "2",
      "--suggestions",
      "--semantic",
      "--explain",
      "--compare", "bodies",
      "--ignore-annotation", "MyGenerated",
      "--include-scripts",
      "--size-tolerance", "0.25",
    )

    assertEquals("json", options.format)
    assertEquals(setOf(CandidateKind.FUNCTION, CandidateKind.CLASS), options.candidateKinds)
    assertEquals(1, options.maxNestingDepth)
    assertEquals(false, options.nestedCandidates)
    assertEquals(listOf("**/src/**"), options.includePatterns)
    assertTrue("**/build/**" in options.excludePatterns)
    assertTrue(options.grouped)
    assertTrue(options.relativePaths)
    assertTrue(options.showSnippets)
    assertEquals(2, options.contextLines)
    assertTrue(options.showSuggestions)
    assertTrue(options.semanticHints)
    assertTrue(options.explain)
    assertEquals(CompareMode.BODIES, options.compareMode)
    assertTrue("MyGenerated" in options.ignoreAnnotations)
    assertTrue(options.includeScripts)
    assertEquals(0.25, options.sizeTolerance)
  }

  @Test
  fun excludesGeneratedAndBuildFilesByDefault() {
    val dir = Files.createTempDirectory("dry4kotlin")
    writeSource(
      dir.resolve("build/generated"),
      "One.kt",
      """
            fun alpha(): Int {
                return listOf(1, 2, 3).sum()
            }
      """.trimIndent(),
    )
    writeSource(
      dir,
      "Two.kt",
      """
            fun beta(): Int {
                return listOf(4, 5, 6).sum()
            }
      """.trimIndent(),
    )

    val candidates = KotlinDuplicateFinder().findDuplicates(
      Options(listOf(dir.toString()), 0.50, 2, 4, "text", false),
    )

    assertEquals(emptyList<Candidate>(), candidates)
  }

  @Test
  fun ignoresDeclarationsWithGeneratedOrPreviewAnnotations() {
    val dir = Files.createTempDirectory("dry4kotlin")
    writeSource(
      dir,
      "One.kt",
      """
            @Generated
            fun alpha(): Int {
                return listOf(1, 2, 3).sum()
            }
      """.trimIndent(),
    )
    writeSource(
      dir,
      "Two.kt",
      """
            fun beta(): Int {
                return listOf(4, 5, 6).sum()
            }
      """.trimIndent(),
    )

    val candidates = KotlinDuplicateFinder().findDuplicates(
      Options(listOf(dir.toString()), 0.50, 2, 4, "text", false),
    )

    assertEquals(emptyList<Candidate>(), candidates)
  }

  @Test
  fun canCompareFunctionBodiesOnly() {
    val dir = Files.createTempDirectory("dry4kotlin")
    writeSource(
      dir,
      "One.kt",
      """
            fun alpha(value: Int): Int {
                val doubled = value * 2
                return doubled + 1
            }
      """.trimIndent(),
    )
    writeSource(
      dir,
      "Two.kt",
      """
            @Deprecated("test")
            internal suspend fun beta(input: Long): Long {
                val total = input * 2
                return total + 10
            }
      """.trimIndent(),
    )

    val candidates = KotlinDuplicateFinder().findDuplicates(
      Options.defaults().copy(
        paths = listOf(dir.toString()),
        threshold = 0.50,
        minLines = 3,
        minNodes = 3,
        compareMode = CompareMode.BODIES,
      ),
    )

    assertTrue(candidates.any { it.leftKind == CandidateKind.FUNCTION && it.rightKind == CandidateKind.FUNCTION })
  }

  @Test
  fun canExplainMatchesInJson() {
    val candidate = Candidate(
      0.9,
      Location("a.kt", 1, 5),
      Location("b.kt", 10, 14),
      20,
      22,
      explanations = listOf("same-candidate-kind:functions/functions", "shared-normalized-nodes:20"),
    )

    val json = Dry4Kotlin.toJson(listOf(candidate), Options.defaults().copy(explain = true))

    assertTrue(json.contains("\"explanations\""))
    assertTrue(json.contains("shared-normalized-nodes"))
  }

  @Test
  fun canGroupDuplicatesAndPrintJson() {
    val candidate = Candidate(
      0.9,
      Location("a.kt", 1, 5),
      Location("b.kt", 10, 14),
      20,
      22,
      leftKind = CandidateKind.FUNCTION,
      rightKind = CandidateKind.FUNCTION,
      suggestion = "Consider extracting a shared private function or extension function.",
    )

    val groups = DuplicateGrouper.group(listOf(candidate))
    val json = Dry4Kotlin.toJson(listOf(candidate), Options.defaults().copy(grouped = true, showSuggestions = true))

    assertEquals(1, groups.size)
    assertTrue(json.contains("\"groups\""))
    assertTrue(json.contains("shared private function"))
  }

  @Test
  fun canExcludeLambdaCandidates() {
    val dir = Files.createTempDirectory("dry4kotlin")
    writeSource(
      dir,
      "One.kt",
      """
            fun one(xs: List<Int>): Int {
                return xs.map { value -> value + 1 }.sum()
            }
      """.trimIndent(),
    )
    writeSource(
      dir,
      "Two.kt",
      """
            fun two(xs: List<Int>): Int {
                return xs.map { item -> item + 2 }.sum()
            }
      """.trimIndent(),
    )

    val candidates = KotlinDuplicateFinder().findDuplicates(
      Options.defaults().copy(
        paths = listOf(dir.toString()),
        threshold = 0.40,
        minLines = 1,
        minNodes = 1,
        candidateKinds = setOf(CandidateKind.LAMBDA),
      ),
    )
    val withoutLambdas = KotlinDuplicateFinder().findDuplicates(
      Options.defaults().copy(
        paths = listOf(dir.toString()),
        threshold = 0.40,
        minLines = 1,
        minNodes = 1,
        candidateKinds = Options.defaultCandidateKinds - CandidateKind.LAMBDA,
      ),
    )

    assertTrue(candidates.isNotEmpty())
    assertTrue(withoutLambdas.none { it.leftKind == CandidateKind.LAMBDA || it.rightKind == CandidateKind.LAMBDA })
  }

  @Test
  fun formatsTextOutputWithLineRanges() {
    val candidate = Candidate(
      0.875,
      Location("a.kt", 10, 14),
      Location("b.kt", 20, 24),
      88,
      91,
    )

    assertEquals(
      "DUPLICATE score=0.88 structure=0.88 size=0.97\n  a.kt:10-14\n  b.kt:20-24",
      Dry4Kotlin.formatCandidate(candidate),
    )
  }

  @Test
  fun printsClearMessageWhenNoTextCandidatesExist() {
    val bytes = ByteArrayOutputStream()
    val original = System.out
    System.setOut(PrintStream(bytes))
    try {
      Dry4Kotlin.printText(emptyList())
    } finally {
      System.setOut(original)
    }

    assertEquals("No duplicate candidates found.\n", bytes.toString())
  }

  @Test
  fun printsEdn() {
    assertEquals("{:candidates []}", Dry4Kotlin.toEdn(emptyList()))
  }

  private fun writeSource(
    dir: Path,
    name: String,
    text: String,
  ): Path {
    val file = dir.resolve(name)
    Files.createDirectories(file.parent)
    Files.writeString(file, text)
    return file
  }
}
