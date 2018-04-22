package org.javacs.kt

import com.intellij.openapi.util.TextRange
import org.javacs.kt.RecompileStrategy.*
import org.javacs.kt.RecompileStrategy.Function
import org.javacs.kt.position.changedRegion
import org.javacs.kt.position.position
import org.javacs.kt.util.toPath
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.resolve.BindingContext

enum class RecompileStrategy {
    Function,
    File,
    NoChanges,
    Impossible
}

class CompiledFile(
        private val content: String,
        private val compiledFile: KtFile,
        private val compiledContext: BindingContext,
        private val sourcePath: Collection<KtFile>,
        private val cp: CompilerClassPath) {
    fun recompile(cursor: Int): RecompileStrategy {
        // If there are no changes, we can use the existing analyze
        val (oldChanged, _) = changedRegion(compiledFile.text, content) ?: return run {
            LOG.info("${fileName(compiledFile)} has not changed")
            NoChanges
        }
        // Look for a recoverable expression around the cursor
        val oldCursor = oldCursor(cursor)
        val leaf = compiledFile.findElementAt(oldCursor) ?: run {
            return if (oldChanged.contains(oldCursor)) {
                LOG.info("No element at ${describePosition(cursor)}, inside changed region")
                File
            } else {
                LOG.info("No element at ${describePosition(cursor)}")
                Impossible
            }
        }
        val surroundingFunction = leaf.parentsWithSelf.filterIsInstance<KtNamedFunction>().firstOrNull() ?: run {
            LOG.info("No surrounding function at ${describePosition(cursor)}")
            return File
        }
        // If the expression that we're going to re-compile doesn't include all the changes, give up
        val willRepair = surroundingFunction.bodyExpression?.textRange ?: return File
        if (!willRepair.contains(oldChanged)) {
            LOG.info("Changed region ${describeRange(oldChanged)} is outside ${surroundingFunction.name} $willRepair")
            return File
        }
        // If the function body doesn't have scope, give up
        val scope = compiledContext.get(BindingContext.LEXICAL_SCOPE, surroundingFunction.bodyExpression) ?: run {
            LOG.info("${surroundingFunction.name} has no scope")
            return File
        }

        LOG.info("Successfully recovered at ${describePosition(cursor)} using ${surroundingFunction.name}")
        return Function
    }

    private fun oldCursor(cursor: Int): Int {
        val (oldChanged, newChanged) = changedRegion(compiledFile.text, content) ?: return cursor

        return when {
            cursor <= newChanged.startOffset -> cursor
            cursor < newChanged.endOffset -> {
                val newRelative = cursor - newChanged.startOffset
                val oldRelative = newRelative * oldChanged.length / newChanged.length
                oldChanged.startOffset + oldRelative
            }
            else -> compiledFile.text.length - (content.length - cursor)
        }
    }

    fun compiledCode(cursor: Int): CompiledCode {
        return CompiledCode(compiledFile.text, compiledFile, compiledContext, cursor, 0, cp.compiler, sourcePath)
    }

    /**
     * Re-analyze a single function declaration
     */
    fun recompileFunction(cursor: Int): CompiledCode {
        val oldCursor = oldCursor(cursor)
        val surroundingFunction = compiledFile.findElementAt(oldCursor)!!.parentsWithSelf.filterIsInstance<KtNamedFunction>().firstOrNull()!!
        val body = surroundingFunction.bodyExpression!!
        val scope = compiledContext.get(BindingContext.LEXICAL_SCOPE, body)!!
        val start = body.textRange.startOffset
        val end = body.textRange.endOffset + content.length - compiledFile.text.length
        val newBodyText = content.substring(start, end)
        val newBody = cp.compiler.createExpression(newBodyText, compiledFile.toPath())
        val newContext = cp.compiler.compileExpression(newBody, scope, sourcePath)
        val offset = body.textRange.startOffset
        val result = CompiledCode(
                content,
                newBody,
                newContext,
                cursor,
                offset,
                cp.compiler,
                sourcePath)

        // Check that we compiled what we intended
        assert(newBody.text == newBodyText)

        val oldAfterCursor = content.substring(cursor)
        val newAfterCursor = newBody.text.substring(result.offset(0))
        assert(oldAfterCursor.startsWith(newAfterCursor))

        return result
    }

    fun describePosition(offset: Int): String {
        val pos = position(content, offset)
        val file = compiledFile.toPath().fileName

        return "$file ${pos.line}:${pos.character}"
    }

    private fun describeRange(range: TextRange): String {
        val start = position(content, range.startOffset)
        val end = position(content, range.endOffset)
        val file = compiledFile.name 

        return "$file ${start.line}:${start.character}-${end.line}:${end.character}"
    }
}

private fun fileName(file: KtFile): String {
    val parts = file.name.split('/')

    return parts.last()
}