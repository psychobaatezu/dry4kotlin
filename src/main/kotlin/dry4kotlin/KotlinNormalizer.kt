package dry4kotlin

import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.*

internal class KotlinNormalizer(
    private val semanticHints: Boolean = false,
    private val semanticIndex: SemanticIndex? = null,
) {
    fun normalize(element: PsiElement): NormalizedNode {
        val children = mutableListOf<NormalizedNode>()
        markers(element).forEach { children += NormalizedNode(it) }
        element.children
            .filter { keepsStructuralChild(it) }
            .forEach { children += normalize(it) }
        return NormalizedNode(tag(element), children)
    }

    private fun tag(element: PsiElement): String = when (element) {
        is KtEnumEntry -> "KtEnumEntry"
        else -> element::class.java.simpleName
    }

    private fun keepsStructuralChild(child: PsiElement): Boolean = when (child) {
        is PsiComment -> false
        is KtNameReferenceExpression -> false
        is KtConstantExpression -> false
        is KtStringTemplateExpression -> false
        else -> child::class.java.simpleName !in ignoredSimpleNames
    }

    private fun markers(element: PsiElement): List<String> {
        val markers = mutableListOf<String>()

        if (element is KtAnnotated) {
            val annotations = semanticIndex?.annotations(element).orEmpty()
            repeat(element.annotationEntries.size) { markers += "annotation" }
            if (semanticHints) annotations.sorted().forEach { markers += "annotation:$it" }
        }

        if (element is KtModifierListOwner) {
            element.modifierList?.children
                ?.map { it.text }
                ?.filter { it.isNotBlank() }
                ?.forEach { markers += "modifier:$it" }
        }

        when (element) {
            is KtCallableDeclaration -> {
                if (element.receiverTypeReference != null) markers += "callable:extension"
                if (semanticHints) {
                    semanticIndex?.resolvedName(element)?.let { markers += "symbol:${symbolCategory(it)}" }
                    element.receiverTypeReference?.let { markers += "receiver:${typeCategory(it.text)}" }
                    element.typeReference?.let { markers += "returns:${typeCategory(it.text)}" }
                }
            }
            is KtOperationExpression -> {
                val operator = element.operationReference.text
                if (operator.isNotBlank()) markers += "operator:$operator"
            }
            is KtSafeQualifiedExpression -> markers += "call:safe"
            is KtDotQualifiedExpression -> markers += "call:dot"
            is KtCallExpression -> {
                if (element.lambdaArguments.isNotEmpty()) markers += "call:trailing-lambda"
                if (semanticHints) {
                    scopeFunctionName(element)?.let { markers += "scope-function:$it" }
                    semanticIndex?.resolveCall(element)?.let { markers += "call-symbol:${symbolCategory(it)}" }
                }
            }
            is KtUnaryExpression -> {
                if (element.text.contains("!!")) markers += "operator:not-null-assertion"
            }
            is KtBinaryExpression -> {
                if (element.operationReference.text == "?:") markers += "operator:elvis"
                if (element.operationReference.text == "in") markers += "operator:in"
            }
            is KtWhenExpression -> {
                if (element.subjectExpression != null) markers += "when:subject"
                markers += "when:entries:${element.entries.size}"
            }
            is KtWhenEntry -> {
                if (element.isElse) markers += "when:else"
                if (element.conditions.size > 1) markers += "when:multi-condition"
            }
            is KtForExpression -> {
                markers += "loop:for-in"
                if (element.loopParameter?.destructuringDeclaration != null) markers += "loop:destructuring"
            }
            is KtDestructuringDeclaration -> markers += "declaration:destructuring"
            is KtClass -> when {
                element.isInterface() -> markers += "class:interface"
                element.isEnum() -> markers += "class:enum"
                element.isAnnotation() -> markers += "class:annotation"
                element.isSealed() -> markers += "class:sealed"
                element.isData() -> markers += "class:data"
            }
            is KtObjectDeclaration -> if (element.isCompanion()) markers += "object:companion"
            is KtLambdaExpression -> {
                if (element.functionLiteral.valueParameterList != null) markers += "lambda:parameters"
                if (element.functionLiteral.receiverTypeReference != null) markers += "lambda:receiver"
            }
            is KtTypeReference -> {
                if (element.text.contains('?')) markers += "type:nullable"
                if (semanticHints) markers += "type:${typeCategory(element.text)}"
            }
            is KtValueArgument -> if (element.isNamed()) markers += "argument:named"
            is KtTypeParameter -> if (element.text.contains("reified")) markers += "type-parameter:reified"
        }

        return markers.sorted()
    }

    private fun scopeFunctionName(element: KtCallExpression): String? {
        val name = element.calleeExpression?.text ?: return null
        return name.takeIf { it in scopeFunctions }
    }

    private fun symbolCategory(fqName: String): String {
        val name = fqName.substringAfterLast('.')
        return when {
            name in scopeFunctions -> "scope:$name"
            fqName.startsWith("kotlinx.coroutines.flow.") || name in flowOperators -> "flow:$name"
            fqName.startsWith("kotlinx.coroutines.") || name in coroutineCalls -> "coroutine:$name"
            fqName.startsWith("androidx.compose.") -> "compose:$name"
            else -> name
        }
    }

    private fun typeCategory(text: String): String {
        val clean = text.removeSuffix("?").substringAfterLast('.').substringBefore('<').trim()
        return when (clean) {
            "String", "Char", "CharSequence" -> "text"
            "Int", "Long", "Short", "Byte", "Float", "Double", "UInt", "ULong", "UShort", "UByte" -> "number"
            "Boolean" -> "boolean"
            "List", "MutableList", "Set", "MutableSet", "Map", "MutableMap", "Collection", "Iterable", "Sequence" -> "collection"
            "Array", "IntArray", "LongArray", "ByteArray", "BooleanArray", "CharArray", "DoubleArray", "FloatArray" -> "array"
            "Unit" -> "unit"
            else -> "object"
        }
    }

    private companion object {
        val scopeFunctions = setOf("let", "run", "also", "apply", "with")
        val flowOperators = setOf("flow", "map", "flatMapLatest", "combine", "catch", "collect", "filter", "onEach", "stateIn", "shareIn")
        val coroutineCalls = setOf("launch", "async", "withContext", "coroutineScope", "supervisorScope", "delay")

        val ignoredSimpleNames = setOf(
            "KtNameIdentifier",
            "KtPackageDirective",
            "KtImportList",
            "KtImportDirective",
            "KtValueArgumentName",
            "KtLabelReferenceExpression",
        )
    }
}
