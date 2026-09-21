package dry4kotlin

internal data class NormalizedNode(
  private val tag: String,
  private val children: List<NormalizedNode> = emptyList(),
) {
  fun nodeCount(): Int = 1 + children.sumOf { it.nodeCount() }

  fun fingerprints(): Set<String> = weightedFingerprints().keys.toSortedSet()

  fun weightedFingerprints(): Map<String, Int> {
    val result = linkedMapOf<String, Int>()
    collectWeightedFingerprints(result)
    return result.toSortedMap()
  }

  private fun collectWeightedFingerprints(result: MutableMap<String, Int>): Int {
    val childSizes = children.map { it.collectWeightedFingerprints(result) }
    val size = 1 + childSizes.sum()
    val fingerprint = toFingerprint()
    result[fingerprint] = maxOf(result[fingerprint] ?: 0, size)
    return size
  }

  private fun toFingerprint(): String {
    if (children.isEmpty()) return tag
    return children.joinToString(prefix = "($tag ", separator = " ", postfix = ")") { it.toFingerprint() }
  }
}
