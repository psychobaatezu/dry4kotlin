package dry4kotlin

import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path

internal class KotlinPsiParser : Closeable {
  private val disposable: Disposable = Disposer.newDisposable()
  private val environment = KotlinCoreEnvironment.createForProduction(
    disposable,
    CompilerConfiguration(),
    EnvironmentConfigFiles.JVM_CONFIG_FILES,
  )
  private val psiFactory = KtPsiFactory(environment.project, markGenerated = false)

  fun parse(file: Path): KtFile = psiFactory.createFile(file.fileName.toString(), Files.readString(file))

  override fun close() {
    Disposer.dispose(disposable)
  }
}
