package org.jetbrains.idea.inspections.runners

import com.intellij.codeInsight.FileModificationService
import com.intellij.codeInspection.*
import com.intellij.codeInspection.ex.ApplicationInspectionProfileManager
import com.intellij.codeInspection.ex.InspectionToolRegistrar
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import org.jetbrains.idea.inspections.PinnedProblemDescriptor
import org.jetbrains.idea.inspections.ProblemLevel
import org.jetbrains.idea.inspections.acceptRecursively
import org.jetbrains.idea.inspections.generators.HTMLGenerator
import org.jetbrains.idea.inspections.generators.XMLGenerator
import org.jetbrains.intellij.ProxyLogger
import org.jetbrains.intellij.parameters.InspectionsRunnerParameters
import java.io.File
import java.util.*


class InspectionsRunner(logger: ProxyLogger) : FileInfoRunner<InspectionsRunnerParameters>(logger) {

    class PluginInspectionWrapper<out T : InspectionProfileEntry>(
            val tool: T,
            val extension: InspectionEP?,
            val classFqName: String,
            val name: String,
            val language: String?,
            val level: ProblemLevel?
    ) {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as PluginInspectionWrapper<*>

            if (classFqName != other.classFqName) return false

            return true
        }

        override fun hashCode(): Int {
            return classFqName.hashCode()
        }
    }

    private fun getInspectionWrappers(parameters: InspectionsRunnerParameters, project: Project): List<PluginInspectionWrapper<*>> {
        logger.info("InheritFromIdea = ${parameters.inheritFromIdea}")
        val registeredInspectionWrappers = getRegisteredInspectionWrappers(parameters)
        val inspectionWrappersFromIdea = when (parameters.inheritFromIdea) {
            true -> getInspectionWrappersInheritFromIdea(parameters, project)
            false -> emptyList()
        }
        logger.info("Registered inspections: " + registeredInspectionWrappers.map { it.classFqName to it.level }.toMap())
        logger.info("Inspections from idea: " + inspectionWrappersFromIdea.map { it.classFqName to it.level }.toMap())
        val mapOfRegisteredInspectionWrappers = registeredInspectionWrappers.map { it.classFqName to it }.toMap()
        val mapOfInspectionWrappersFromIdea = inspectionWrappersFromIdea.map { it.classFqName to it }.toMap()
        val inspections = mapOfInspectionWrappersFromIdea + mapOfRegisteredInspectionWrappers
        return inspections.values.toList()
    }

    private fun getInspectionWrappersInheritFromIdea(parameters: InspectionsRunnerParameters, project: Project): List<PluginInspectionWrapper<*>> {
        val inspectionProfileManager = ApplicationInspectionProfileManager.getInstanceImpl()
        val profile = parameters.profileName
                ?.let { File(project.basePath, "$INSPECTION_PROFILES_PATH/$it") }
                ?.let { inspectionProfileManager.loadProfile(it.absolutePath) }
                ?: inspectionProfileManager.currentProfile
        logger.info("Profile file = ${profile.name}")
        return profile.getAllEnabledInspectionTools(project).map {
            val tool = it.tool
            val classFqName = tool.tool.javaClass.name
            val level = ProblemLevel.fromInspectionEPLevel(it.defaultState.level.name)
            PluginInspectionWrapper(tool.tool, tool.extension, classFqName, tool.displayName, tool.language, level)
        }
    }

    private fun InspectionsRunnerParameters.inspectionsWithLevel(): Map<String, ProblemLevel> {
        return errors.inspections.keys.map { it to ProblemLevel.ERROR }.toMap() +
                warnings.inspections.keys.map { it to ProblemLevel.WARNING }.toMap() +
                info.inspections.keys.map { it to ProblemLevel.INFO }.toMap()
    }

    private fun getRegisteredInspectionWrappers(parameters: InspectionsRunnerParameters): List<PluginInspectionWrapper<*>> {
        logger.info("Error inspections: " + parameters.errors.inspections.map { it.key })
        logger.info("Warning inspections: " + parameters.warnings.inspections.map { it.key })
        logger.info("Info inspections: " + parameters.info.inspections.map { it.key })
        val tools = InspectionToolRegistrar.getInstance().createTools()
        return parameters.inspectionsWithLevel().map { entry ->
            val name = entry.key
            val longInspectionClassName = name + "Inspection"
            val level = entry.value
            tools.find {
                it.tool.javaClass.name == name
                        || it.tool.javaClass.name.split(".").last() == longInspectionClassName
                        || it.tool.displayName == name
            }?.let { PluginInspectionWrapper(it.tool, it.extension, it.tool.javaClass.name, name, it.language, level) }
                    ?: throw IllegalArgumentException("'$name' is not found in registrar")
        }
    }

    override fun analyze(
            files: Collection<FileInfo>,
            project: Project,
            parameters: InspectionsRunnerParameters
    ): Boolean {
        val checker = InspectionChecker()
        val results = analyze(files, project, parameters, checker)
        reportProblems(parameters, results)
        quickFixProblems(project, parameters, results)
        return checker.isSuccess
    }

    private fun analyze(
            files: Collection<FileInfo>,
            project: Project,
            parameters: InspectionsRunnerParameters,
            checker: InspectionChecker
    ): Map<String, InspectionResult<*>> {
        val results = HashMap<String, InspectionResult<*>>()
        logger.info("Before inspections launched: total of ${files.size} files to analyze")
        val inspectionWrappers = getInspectionWrappers(parameters, project)
        logger.info("Inspections: " + inspectionWrappers.map { it.classFqName to it.level }.toMap())
        for (inspectionWrapper in inspectionWrappers) {
            val inspectionClass = inspectionWrapper.classFqName
            val inspectionTool = inspectionWrapper.tool
            when (inspectionTool) {
                is LocalInspectionTool -> {
                    @Suppress("UNCHECKED_CAST")
                    inspectionWrapper as PluginInspectionWrapper<LocalInspectionTool>
                    results[inspectionClass] = inspectionWrapper.apply(files, parameters, checker)
                }
                is GlobalInspectionTool -> {
                    logger.warn("Global inspection tools like $inspectionClass are not yet supported")
                }
                else -> {
                    logger.error("Unexpected $inspectionClass which is neither local nor global")
                }
            }
            if (checker.isFail) break
        }
        return results
    }

    class InspectionChecker {
        private var errors: Int = 0
        private var warnings: Int = 0
        private var info: Int = 0
        private var success: Boolean = true

        val isSuccess: Boolean
            get() = success

        val isFail: Boolean
            get() = !success

        fun apply(level: ProblemLevel, parameters: InspectionsRunnerParameters, errorListener: (String, Int) -> Unit) {
            when (level) {
                ProblemLevel.ERROR -> errors++
                ProblemLevel.WARNING, ProblemLevel.WEAK_WARNING -> warnings++
                ProblemLevel.INFO -> info++
            }
            when {
                parameters.errors.isTooMany(errors) -> errorListener("errors", errors)
                parameters.warnings.isTooMany(warnings) -> errorListener("warnings", warnings)
                parameters.info.isTooMany(info) -> errorListener("info", info)
                else -> return
            }
            success = false
        }

        private fun InspectionsRunnerParameters.Inspections.isTooMany(value: Int) = max?.let { value > it } ?: false
    }

    data class InspectionResult<out T : InspectionProfileEntry>(
            val wrapper: PluginInspectionWrapper<T>,
            val problems: List<PinnedProblemDescriptor>
    )

    private fun PluginInspectionWrapper<LocalInspectionTool>.apply(
            files: Collection<FileInfo>,
            parameters: InspectionsRunnerParameters,
            checker: InspectionChecker
    ): InspectionResult<LocalInspectionTool> {
        val displayName = extension?.displayName ?: "<Unknown diagnostic>"
        val inspectionResults = mutableListOf<PinnedProblemDescriptor>()
        runReadAction {
            val task = Runnable {
                try {
                    for ((psiFile, document) in files) {
                        if (!inspectionEnabledForFile(psiFile)) continue
                        val fileName = psiFile.name
                        logger.info("($level) Inspection '$displayName' analyzing started for $fileName")
                        val problems = tool.analyze(psiFile, document, displayName, level)
                        inspectionResults += problems
                        for (problem in problems) {
                            checker.apply(problem.level, parameters) { name, number ->
                                logger.error("Too many $name found: $number. Analysis stopped")
                            }
                            if (checker.isFail) return@Runnable
                        }
                    }
                } catch (exception: InspectionException) {
                    logger.error("Exception during inspection running ${exception.message}")
                    logger.error(exception.stackTrace.joinToString(separator = "\n") { "    $it" })
                    logger.error("Caused by: " + (exception.cause.message ?: exception.cause))
                    logger.error(exception.cause.stackTrace.joinToString(separator = "\n") { "    $it" })
                }
            }
            ProgressManager.getInstance().runProcess(task, EmptyProgressIndicator())
        }
        return InspectionResult(this, inspectionResults)
    }

    private fun reportProblems(parameters: InspectionsRunnerParameters, results: Map<String, InspectionResult<*>>) {
        val numProblems = results.values.map { it.problems }.flatten().count()
        logger.info("Total of $numProblems problem(s) found")
        val generators = listOfNotNull(
                parameters.reportParameters.xml?.let { XMLGenerator(it) },
                parameters.reportParameters.html?.let { HTMLGenerator(it) }
        )
        val sortedResults = results.entries
                .map { entry -> entry.value.problems.map { entry.key to it } }
                .flatten()
                .asSequence()
                .sortedBy { (it.second.line shl 16) + it.second.row }
                .groupBy { it.second.fileName }
        runReadAction {
            for (fileInspectionAndProblems in sortedResults.values) {
                for ((inspectionClass, problem) in fileInspectionAndProblems) {
                    if (!parameters.reportParameters.isQuiet) log(problem)
                    generators.forEach { it.report(problem, inspectionClass) }
                }
            }
            generators.forEach { it.generate() }
        }
    }

    private fun quickFixProblems(
            project: Project,
            parameters: InspectionsRunnerParameters,
            results: Map<String, InspectionResult<*>>
    ) {
        if (!parameters.isAvailableCodeChanging) return
        val writeFixes = ArrayList<Pair<PinnedProblemDescriptor, QuickFix<CommonProblemDescriptor>>>()
        val otherFixes = ArrayList<Pair<PinnedProblemDescriptor, QuickFix<CommonProblemDescriptor>>>()
        @Suppress("UNUSED_VARIABLE")
        for ((inspectionClassName, result) in results) {
            val quickFix = parameters.inspections[result.wrapper.name]?.quickFix
            if (quickFix != true) continue
            for (problem in result.problems) {
                val fixes = problem.fixes ?: continue
                if (fixes.size != 1) {
                    logger.error("Can not apply problem fixes for '${problem.renderWithLocation()}'")
                    continue
                }
                val fix = fixes[0]
                when (fix.startInWriteAction()) {
                    true -> writeFixes.add(problem to fix)
                    false -> otherFixes.add(problem to fix)
                }
            }
        }
        val fileModificationService = FileModificationService.getInstance()
        val files = ArrayList<PsiFile>()
        runWriteCommandAction(project) {
            for ((problem, fix) in writeFixes) {
                fileModificationService.applyFixWithChecks(fix, project, problem)?.let {
                    files.add(it)
                }
            }
        }
        invokeAndWait {
            for ((problem, fix) in otherFixes) {
                fileModificationService.applyFixWithChecks(fix, project, problem)?.let {
                    files.add(it)
                }
            }
        }
        invokeAndWait {
            project.flushChanges(files)
        }
    }

    private fun runWriteCommandAction(project: com.intellij.openapi.project.Project, action: () -> Unit) =
            WriteCommandAction.runWriteCommandAction(project, action)

    private fun FileModificationService.applyFixWithChecks(
            fix: QuickFix<CommonProblemDescriptor>,
            project: Project,
            problem: PinnedProblemDescriptor
    ): PsiFile? {
        val renderedFix = fix.name
        val renderedProblem = problem.renderLocation()
        val identificator = "fix '$renderedFix' for '$renderedProblem'"
        val file = problem.psiElement?.containingFile
        runReadAction {
            val beforeText = file?.text
            applyFix(fix, project, problem)
            val afterText = file?.text
            when (afterText) {
                beforeText -> logger.info("File hasn't changes after $identificator")
                else -> logger.info("File has changes after $identificator")
            }
        }
        return file
    }

    private fun Project.flushChanges(files: List<PsiFile>) {
        logger.info("Flush IDEA project")
        val psiDocumentManager = PsiDocumentManager.getInstance(this)
        val fileDocumentManager = FileDocumentManager.getInstance()
        psiDocumentManager.commitAllDocuments()
        for (file in files) {
            val document = psiDocumentManager.getDocument(file)
            if (document == null) {
                logger.warn("Document for file '${file.name}' not found.")
                continue
            }
            psiDocumentManager.doPostponedOperationsAndUnblockDocument(document)
            logger.info("File '${file.name}' is flushed")
        }
        fileDocumentManager.saveAllDocuments()
    }

    private fun FileModificationService.applyFix(
            fix: QuickFix<CommonProblemDescriptor>,
            project: Project,
            problem: PinnedProblemDescriptor
    ) {
        val renderedFix = fix.name
        val renderedProblem = problem.renderLocation()
        val identificator = "fix '$renderedFix' for '$renderedProblem'"
        if (problem.psiElement == null) {
            logger.info("Already applied $identificator")
            return
        }
        try {
            if (!preparePsiElementForWrite(problem.psiElement)) {
                logger.warn("Problem psiElement cannot be prepared $identificator")
                return
            }
            fix.applyFix(project, problem)
            logger.info("Applied $identificator")
            return
        } catch (exception: Exception) {
            logger.error("Exception during applying quick $identificator")
            logger.error("$exception")
            return
        }
    }

    private fun log(problem: PinnedProblemDescriptor) {
        val level = problem.level
        val problemWithLocation = "$level: " + problem.renderWithLocation()
        when (level) {
            ProblemLevel.INFO -> logger.info(problemWithLocation)
            ProblemLevel.WARNING, ProblemLevel.WEAK_WARNING -> logger.warn(problemWithLocation)
            ProblemLevel.ERROR -> logger.error(problemWithLocation)
        }
    }

    private fun PluginInspectionWrapper<*>.inspectionEnabledForFile(psiFile: PsiFile): Boolean =
            when (psiFile.language.displayName) {
                "Kotlin" -> language in KOTLIN_FILE_APPLICABLE_LANGUAGES
                "Java" -> language in JAVA_FILE_APPLICABLE_LANGUAGES
                else -> true
            }

    private class InspectionException(
            tool: LocalInspectionTool,
            file: PsiFile,
            override val cause: Throwable
    ) : Exception("Exception during ${tool.shortName} analysis of ${file.name}", cause)

    private fun LocalInspectionTool.analyze(
            file: PsiFile,
            document: Document,
            displayName: String?,
            problemLevel: ProblemLevel?
    ): List<PinnedProblemDescriptor> {
        val holder = ProblemsHolder(InspectionManager.getInstance(file.project), file, false)
        val session = LocalInspectionToolSession(file, file.textRange.startOffset, file.textRange.endOffset)
        try {
            val visitor = buildVisitor(holder, false, session)
            file.acceptRecursively(visitor)
        } catch (t: Throwable) {
            throw InspectionException(this, file, t)
        }
        return holder.results.mapNotNull { PinnedProblemDescriptor(it, document, displayName, problemLevel) }
    }

    companion object {
        private const val INSPECTION_PROFILES_PATH = ".idea/inspectionProfiles"
        private val KOTLIN_FILE_APPLICABLE_LANGUAGES = setOf(null, "kotlin", "UAST")
        private val JAVA_FILE_APPLICABLE_LANGUAGES = setOf(null, "java", "UAST", JavaLanguage.INSTANCE.id)
    }
}