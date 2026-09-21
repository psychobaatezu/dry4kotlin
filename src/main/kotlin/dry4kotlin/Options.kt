package dry4kotlin

data class Options(
  val paths: List<String>,
  val threshold: Double,
  val minLines: Int,
  val minNodes: Int,
  val format: String,
  val help: Boolean,
  val candidateKinds: Set<CandidateKind> = defaultCandidateKinds,
  val maxNestingDepth: Int? = null,
  val nestedCandidates: Boolean = true,
  val includePatterns: List<String> = emptyList(),
  val excludePatterns: List<String> = defaultExcludePatterns,
  val grouped: Boolean = false,
  val relativePaths: Boolean = false,
  val showSnippets: Boolean = false,
  val contextLines: Int = 0,
  val showSuggestions: Boolean = false,
  val semanticHints: Boolean = false,
  val sizeTolerance: Double = 0.50,
  val explain: Boolean = false,
  val compareMode: CompareMode = CompareMode.DECLARATIONS,
  val ignoreAnnotations: Set<String> = defaultIgnoreAnnotations,
  val includeScripts: Boolean = false,
) {
  companion object {
    val defaultCandidateKinds: Set<CandidateKind> = setOf(
      CandidateKind.CLASS,
      CandidateKind.FUNCTION,
      CandidateKind.CONSTRUCTOR,
      CandidateKind.PROPERTY,
      CandidateKind.INITIALIZER,
      CandidateKind.ENUM_ENTRY,
      CandidateKind.LAMBDA,
      CandidateKind.OBJECT_LITERAL,
    )

    val defaultExcludePatterns: List<String> = listOf(
      "**/build/**",
      "**/.gradle/**",
      "**/target/**",
      "**/generated/**",
      "**/build/generated/**",
      "**/*.generated.kt",
      "**/*Generated.kt",
      "**/R.kt",
      "**/BuildConfig.kt",
      "**/*_Factory.kt",
      "**/*_MembersInjector.kt",
      "**/Dagger*.kt",
      "**/Hilt_*.kt",
      "**/*JsonAdapter.kt",
      "**/*Serializer.kt",
      "**/fixtures/**",
      "**/fixture/**",
      "**/snapshots/**",
      "**/snapshot/**",
      "**/*.gradle.kts",
      "**/buildSrc/**",
    )

    val defaultIgnoreAnnotations: Set<String> = setOf(
      "Generated",
      "javax.annotation.Generated",
      "jakarta.annotation.Generated",
      "kotlinx.serialization.GeneratedSerializer",
      "Preview",
      "androidx.compose.ui.tooling.preview.Preview",
    )

    fun defaults(): Options = Options(
      paths = listOf("src"),
      threshold = 0.82,
      minLines = 4,
      minNodes = 20,
      format = "text",
      help = false,
      candidateKinds = defaultCandidateKinds,
      maxNestingDepth = null,
      nestedCandidates = true,
      includePatterns = emptyList(),
      excludePatterns = defaultExcludePatterns,
      grouped = false,
      relativePaths = false,
      showSnippets = false,
      contextLines = 0,
      showSuggestions = false,
      semanticHints = false,
      sizeTolerance = 0.50,
      explain = false,
      compareMode = CompareMode.DECLARATIONS,
      ignoreAnnotations = defaultIgnoreAnnotations,
      includeScripts = false,
    )

    fun parse(vararg args: String): Options {
      val defaults = defaults()
      val paths = mutableListOf<String>()
      var threshold = defaults.threshold
      var minLines = defaults.minLines
      var minNodes = defaults.minNodes
      var format = defaults.format
      var help = defaults.help
      var candidateKinds = defaults.candidateKinds
      var maxNestingDepth = defaults.maxNestingDepth
      var nestedCandidates = defaults.nestedCandidates
      val includePatterns = defaults.includePatterns.toMutableList()
      val excludePatterns = defaults.excludePatterns.toMutableList()
      var grouped = defaults.grouped
      var relativePaths = defaults.relativePaths
      var showSnippets = defaults.showSnippets
      var contextLines = defaults.contextLines
      var showSuggestions = defaults.showSuggestions
      var semanticHints = defaults.semanticHints
      var sizeTolerance = defaults.sizeTolerance
      var explain = defaults.explain
      var compareMode = defaults.compareMode
      val ignoreAnnotations = defaults.ignoreAnnotations.toMutableSet()
      var includeScripts = defaults.includeScripts

      var i = 0
      while (i < args.size) {
        when (val arg = args[i]) {
          "--threshold" -> threshold = valueFor(args, ++i, arg).toDouble()

          "--min-lines" -> minLines = valueFor(args, ++i, arg).toInt()

          "--min-nodes" -> minNodes = valueFor(args, ++i, arg).toInt()

          "--format" -> format = valueFor(args, ++i, arg)

          "--edn" -> format = "edn"

          "--text" -> format = "text"

          "--json" -> format = "json"

          "--sarif" -> format = "sarif"

          "--kinds" -> candidateKinds = CandidateKind.parseList(valueFor(args, ++i, arg))

          "--exclude-kinds" -> candidateKinds -= CandidateKind.parseList(valueFor(args, ++i, arg))

          "--exclude-lambdas" -> candidateKinds -= CandidateKind.LAMBDA

          "--max-nesting-depth" -> maxNestingDepth = valueFor(args, ++i, arg).toInt()

          "--no-nested-candidates" -> nestedCandidates = false

          "--include" -> includePatterns += valueFor(args, ++i, arg)

          "--exclude" -> excludePatterns += valueFor(args, ++i, arg)

          "--no-default-excludes" -> {
            excludePatterns.clear()
          }

          "--group", "--grouped" -> grouped = true

          "--relative-paths" -> relativePaths = true

          "--show-snippets" -> showSnippets = true

          "--context-lines" -> contextLines = valueFor(args, ++i, arg).toInt()

          "--suggestions" -> showSuggestions = true

          "--semantic" -> semanticHints = true

          "--size-tolerance" -> sizeTolerance = valueFor(args, ++i, arg).toDouble()

          "--explain" -> explain = true

          "--compare" -> compareMode = CompareMode.parse(valueFor(args, ++i, arg))

          "--ignore-annotation" -> ignoreAnnotations += valueFor(args, ++i, arg)

          "--no-default-ignore-annotations" -> ignoreAnnotations.clear()

          "--include-scripts" -> includeScripts = true

          "--help", "-h" -> help = true

          else -> paths += arg
        }
        i++
      }

      if (paths.isEmpty()) {
        paths += "src"
      }

      require(contextLines >= 0) { "--context-lines must be >= 0" }
      require(sizeTolerance in 0.0..1.0) { "--size-tolerance must be between 0.0 and 1.0" }

      return Options(
        paths,
        threshold,
        minLines,
        minNodes,
        format,
        help,
        candidateKinds,
        maxNestingDepth,
        nestedCandidates,
        includePatterns,
        excludePatterns,
        grouped,
        relativePaths,
        showSnippets,
        contextLines,
        showSuggestions,
        semanticHints,
        sizeTolerance,
        explain,
        compareMode,
        ignoreAnnotations,
        includeScripts,
      )
    }

    private fun valueFor(
      args: Array<out String>,
      index: Int,
      option: String,
    ): String {
      require(index < args.size) { "Missing value for $option" }
      return args[index]
    }
  }
}
