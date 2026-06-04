# dry4kotlin

Find structurally similar Kotlin declarations.

`dry4kotlin` scans `.kt` files, normalizes Kotlin PSI trees, ignores identifiers and literal values, and compares declarations by normalized subtree fingerprints. It is Kotlin-first: candidate roots, operators, nullability, safe calls, lambdas, extension receivers, scope functions, `when`, destructuring, annotations, and Kotlin modifiers are represented in the normalized shape. Optional `--semantic` mode builds a source-level semantic index for packages, imports, declarations, annotations, and call symbols so matches can use resolved project/import names where available.

## Usage

```bash
mvn -q -DskipTests package
java -jar target/dry4kotlin-0.1.0-SNAPSHOT.jar [options] [file-or-directory ...]
```

When no path is provided, Kotlin source-set roots under `src` are detected (`main`, `test`, `commonMain`, `jvmMain`, `androidMain`, etc.); otherwise `src` is scanned as a fallback.

## Options

```text
--threshold N          Minimum structural similarity score, default 0.82
--min-lines N          Minimum source lines in a candidate declaration, default 4
--min-nodes N          Minimum normalized syntax nodes, default 20
--format F             text, edn, json, or sarif, default text
--edn/--text/--json/--sarif
--kinds LIST           Candidate kinds: classes,functions,constructors,properties,initializers,enum-entries,lambdas,object-literals
--exclude-kinds LIST   Remove candidate kinds from the default set
--exclude-lambdas      Shortcut for --exclude-kinds lambdas
--max-nesting-depth N  Limit nested candidate roots by candidate-depth
--no-nested-candidates Report only outer candidate roots
--include GLOB         Include only matching .kt paths
--exclude GLOB         Exclude matching .kt paths; default excludes build/generated files
--no-default-excludes  Disable default build/generated excludes
--group                Group pairwise duplicates into clusters
--relative-paths       Print paths relative to the current directory
--show-snippets        Include source snippets in text/json output
--context-lines N      Source context lines for snippets, default 0
--suggestions          Include Kotlin-specific extraction suggestions
--semantic             Add source-level semantic resolution hints
--explain              Explain why candidates matched
--compare F            declarations or bodies, default declarations
--ignore-annotation A  Ignore declarations annotated with A; defaults include Generated and Compose Preview
--no-default-ignore-annotations
--include-scripts      Also scan .kts files; Gradle scripts remain excluded unless default excludes are disabled
--size-tolerance N     Skip pairs with node size ratio below N, default 0.50
```

Default excludes skip `build/`, `.gradle/`, `target/`, `generated/`, `*.generated.kt`, `*Generated.kt`, `R.kt`, `BuildConfig.kt`, common Dagger/Hilt/serialization generated files, fixtures, snapshots, Gradle scripts, and `buildSrc`.

Declarations annotated with generated/preview annotations are also ignored by default, including `@Generated` and Compose `@Preview`.

## What is compared

Candidate roots include Kotlin classes/objects, functions, constructors, properties, anonymous initializers, enum entries, lambdas, and object literals. Names and literal values are intentionally ignored so equivalent code with different variable names or constants can still match.

The score combines:

- structural similarity over normalized subtree fingerprints
- size similarity, to penalize matches with very different node counts
- shared normalized node weight, reported as `shared-nodes`

Use `--compare bodies` to compare only meaningful bodies/initializers for functions, properties, lambdas, and initializers while still reporting the declaration ranges.

## Output

Text output:

```text
DUPLICATE score=0.91 structure=0.93 size=0.86 shared-nodes=42
  src/main/kotlin/Left.kt:10-18
  src/main/kotlin/Right.kt:22-30
```

Grouped output:

```text
GROUP score=0.89 size=3 kinds=functions
  src/main/kotlin/A.kt:10-18
  src/main/kotlin/B.kt:22-30
  src/main/kotlin/C.kt:40-48
```

Machine-readable formats are available with `--json`, `--edn`, and `--sarif`.

## Examples

Scan only functions and classes, excluding lambdas:

```bash
java -jar target/dry4kotlin-0.1.0-SNAPSHOT.jar --kinds functions,classes src/main/kotlin
```

Generate grouped JSON with explanations, suggestions, and snippets:

```bash
java -jar target/dry4kotlin-0.1.0-SNAPSHOT.jar \
  --json --group --explain --suggestions --show-snippets --context-lines 2 --relative-paths
```

Use additional Kotlin semantic hints, body-only comparison, and path filters:

```bash
java -jar target/dry4kotlin-0.1.0-SNAPSHOT.jar \
  --semantic --compare bodies --include '**/src/**' --exclude '**/fixtures/**'
```

## Development

```bash
mvn test
```
