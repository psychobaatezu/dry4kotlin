package dry4kotlin

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

internal class SemanticIndex private constructor(
  private val fileInfo: Map<KtFile, FileInfo>,
  private val declarationsByName: Map<String, Set<String>>,
) {
  fun annotations(element: PsiElement): Set<String> {
    if (element !is KtAnnotated) return emptySet()
    return element.annotationEntries.mapNotNull { annotation ->
      annotation.shortName?.asString() ?: annotation.typeReference?.text
    }.toSet()
  }

  fun hasIgnoredAnnotation(
    element: PsiElement,
    ignored: Set<String>,
  ): Boolean {
    if (ignored.isEmpty()) return false
    val annotations = annotations(element)
    val file = element.containingFile as? KtFile
    val imports = file?.let { fileInfo[it]?.importsByName } ?: emptyMap()
    return annotations.any { annotation ->
      annotation in ignored || imports[annotation] in ignored ||
        ignored.any { it.substringAfterLast('.') == annotation }
    }
  }

  fun packageName(element: PsiElement): String? = (element.containingFile as? KtFile)
    ?.let { fileInfo[it]?.packageName }
    ?.takeIf { it.isNotBlank() }

  fun resolvedName(element: PsiElement): String? {
    if (element !is KtNamedDeclaration) return null
    val name = element.name ?: return null
    return fqNameFor(element) ?: resolveName(element, name)
  }

  fun resolveCall(call: KtCallExpression): String? {
    val name = call.calleeExpression?.text?.takeIf { it.isNotBlank() } ?: return null
    return resolveName(call, name)
  }

  fun declarationKind(fqName: String): String? = declarationsByName.values
    .asSequence()
    .flatten()
    .firstOrNull { it == fqName }
    ?.let { fqNameToKind[it] }

  fun explanationFor(element: PsiElement): List<String> {
    val explanations = mutableListOf<String>()
    packageName(element)?.let { explanations += "same-package-context:$it" }
    resolvedName(element)?.let { explanations += "resolved-declaration:$it" }
    val annotations = annotations(element)
    if (annotations.isNotEmpty()) explanations += "annotations:${annotations.sorted().joinToString(",")}"
    return explanations
  }

  private fun resolveName(
    element: PsiElement,
    name: String,
  ): String {
    val file = element.containingFile as? KtFile
    val imports = file?.let { fileInfo[it]?.importsByName } ?: emptyMap()
    imports[name]?.let { return it }
    val declarations = declarationsByName[name]
    if (declarations != null && declarations.size == 1) return declarations.single()
    val packageName = file?.let { fileInfo[it]?.packageName }?.takeIf { it.isNotBlank() }
    return if (packageName == null) name else "$packageName.$name"
  }

  private fun fqNameFor(declaration: KtNamedDeclaration): String? {
    val name = declaration.name ?: return null
    val containingClass = declaration.parent
      ?.parentsWithSelf()
      ?.filterIsInstance<KtClassOrObject>()
      ?.firstOrNull()
      ?.name
    val file = declaration.containingFile as? KtFile
    val packageName = file?.let { fileInfo[it]?.packageName }?.takeIf { it.isNotBlank() }
    return listOfNotNull(packageName, containingClass, name).joinToString(".").takeIf { it.isNotBlank() }
  }

  private fun PsiElement.parentsWithSelf(): Sequence<PsiElement> = sequence {
    var current: PsiElement? = this@parentsWithSelf
    while (current != null) {
      yield(current)
      current = current.parent
    }
  }

  private data class FileInfo(
    val packageName: String,
    val importsByName: Map<String, String>,
  )

  companion object {
    private val fqNameToKind = mutableMapOf<String, String>()

    fun build(files: List<KtFile>): SemanticIndex {
      fqNameToKind.clear()
      val fileInfo = files.associateWith { file ->
        FileInfo(
          packageName = file.packageFqName.asString(),
          importsByName = file.importDirectives.mapNotNull { directive ->
            val fqName = directive.importedFqName?.asString() ?: return@mapNotNull null
            val localName = directive.aliasName ?: fqName.substringAfterLast('.')
            localName to fqName
          }.toMap(),
        )
      }
      val declarationsByName = mutableMapOf<String, MutableSet<String>>()
      files.forEach { file ->
        file.collectDescendantsOfType<KtNamedDeclaration>().forEach { declaration ->
          val name = declaration.name ?: return@forEach
          val fqName = buildFqName(file, declaration, name)
          declarationsByName.getOrPut(name) { mutableSetOf() } += fqName
          fqNameToKind[fqName] = when (declaration) {
            is KtNamedFunction -> "function"
            is KtClassOrObject -> "class"
            else -> "declaration"
          }
        }
      }
      return SemanticIndex(fileInfo, declarationsByName)
    }

    private fun buildFqName(
      file: KtFile,
      declaration: KtNamedDeclaration,
      name: String,
    ): String {
      val className = declaration.parent
        ?.parentsWithSelfStatic()
        ?.filterIsInstance<KtClassOrObject>()
        ?.firstOrNull()
        ?.name
      return listOf(file.packageFqName.asString().takeIf { it.isNotBlank() }, className, name)
        .filterNotNull()
        .joinToString(".")
    }

    private fun PsiElement.parentsWithSelfStatic(): Sequence<PsiElement> = sequence {
      var current: PsiElement? = this@parentsWithSelfStatic
      while (current != null) {
        yield(current)
        current = current.parent
      }
    }
  }
}
