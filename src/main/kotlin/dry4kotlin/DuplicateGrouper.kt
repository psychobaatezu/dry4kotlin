package dry4kotlin

object DuplicateGrouper {
  fun group(candidates: List<Candidate>): List<DuplicateGroup> {
    val parent = mutableMapOf<Location, Location>()

    fun root(location: Location): Location {
      val current = parent.getOrPut(location) { location }
      if (current == location) return location
      val resolved = root(current)
      parent[location] = resolved
      return resolved
    }

    fun union(
      left: Location,
      right: Location,
    ) {
      val leftRoot = root(left)
      val rightRoot = root(right)
      if (leftRoot != rightRoot) parent[rightRoot] = leftRoot
    }

    candidates.forEach { union(it.left, it.right) }

    return candidates
      .flatMap { candidate -> listOf(candidate.left to candidate, candidate.right to candidate) }
      .groupBy { root(it.first) }
      .values
      .map { members ->
        val relatedCandidates = members.map { it.second }.distinct()
        val locations = members.map { it.first }.distinct().sortedWith(
          compareBy<Location> { it.file }.thenBy { it.startLine }.thenBy { it.endLine },
        )
        DuplicateGroup(
          score = relatedCandidates.minOf { it.score },
          locations = locations,
          kinds =
          relatedCandidates.flatMap { listOf(it.leftKind, it.rightKind) }.toSet() - CandidateKind.UNKNOWN,
          suggestions = relatedCandidates.mapNotNull { it.suggestion }.toSet(),
          explanations = relatedCandidates.flatMap { it.explanations }.toSet(),
        )
      }
      .sortedWith(
        compareByDescending<DuplicateGroup> { it.score }
          .thenByDescending { it.locations.size }
          .thenBy { it.locations.firstOrNull()?.file ?: "" }
          .thenBy { it.locations.firstOrNull()?.startLine ?: 0 },
      )
  }
}
