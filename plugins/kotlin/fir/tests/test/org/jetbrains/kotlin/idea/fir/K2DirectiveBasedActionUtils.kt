// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.fir

import com.intellij.platform.testFramework.core.FileComparisonFailedError
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.idea.base.test.InTextDirectivesUtils
import org.jetbrains.kotlin.psi.KtFile
import java.io.File

object K2DirectiveBasedActionUtils {
    const val DISABLE_K2_ERRORS_DIRECTIVE: String = "// DISABLE-K2-ERRORS"
    const val DISABLE_K2_WARNINGS_DIRECTIVE: String = "// DISABLE-WARNINGS"

    const val K2_ERROR_DIRECTIVE: String = "// K2-ERROR:"
    const val K2_AFTER_ERROR_DIRECTIVE: String = "// K2-AFTER-ERROR:"

    fun checkForErrorsBefore(mainFile: File, ktFile: KtFile, fileText: String) {
        checkForUnexpected(mainFile, ktFile, fileText, "errors", KaSeverity.ERROR, K2_ERROR_DIRECTIVE, "// ERROR:")
    }

    fun checkForErrorsAfter(mainFile: File, ktFile: KtFile, fileText: String) {
        checkForUnexpected(mainFile, ktFile, fileText, "errors", KaSeverity.ERROR, K2_AFTER_ERROR_DIRECTIVE, "// AFTER-ERROR:")
    }

    @OptIn(KaExperimentalApi::class, KaAllowAnalysisOnEdt::class)
    fun checkForUnexpected(
        mainFile: File,
        file: KtFile,
        fileText: String,
        name: String,
        severity: KaSeverity,
        vararg directives: String,
    ) {
        val firstDirective = directives.first()
        val (directive, lines) = directives.firstNotNullOfOrNull { directive ->
            val lines = InTextDirectivesUtils.findLinesWithPrefixesRemoved(fileText, directive)
            lines.takeIf { it.isNotEmpty() }?.let { directive to it }
        } ?: (firstDirective to emptyList())

        val expected = lines
            .filter { it.isNotBlank() }
            .sorted()
            .map { "$directive $it" }

        val actual =
            allowAnalysisOnEdt {
                analyze(file) {
                    val diagnostics = file.collectDiagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)
                    diagnostics
                        .filter { it.severity == severity }
                        .map { "$directive ${it.defaultMessage.replace("\n", "<br>")}" }
                        .sorted()
                }
            }

        if (actual.isEmpty() && expected.isEmpty()) return

        if (actual != expected) {
            val lastDirective = directives.last()
            val trimmedActual = fileText.replace(Regex("${firstDirective}.*\n"), "")

            var firstNotCommentedLineIndex = 0
            for (line in trimmedActual.lines()) {
                if (!line.startsWith("//")) break
                firstNotCommentedLineIndex += line.length + 1 // as `\n`
            }
            val actualString = trimmedActual.substring(0, firstNotCommentedLineIndex) +
                    (actual.takeIf { it.isNotEmpty() } ?: listOf(firstDirective)).joinToString(separator = "\n", postfix = "\n") { it.replace(lastDirective, firstDirective) } +
                    trimmedActual.substring(firstNotCommentedLineIndex)

            throw FileComparisonFailedError(
                "All actual $name should be mentioned in test data with '$directive' directive. " +
                        "But no unnecessary $name should be mentioned, file:\n$fileText",
                expected = fileText,
                actual = actualString,
                expectedFilePath = mainFile.absolutePath,
            )
        }
    }
}