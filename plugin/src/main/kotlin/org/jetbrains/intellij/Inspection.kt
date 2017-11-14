package org.jetbrains.intellij

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileTree
import org.gradle.api.internal.ClosureBackedAction
import org.gradle.api.plugins.quality.CheckstyleReports
import org.gradle.api.reporting.Reporting
import org.gradle.api.resources.TextResource
import org.gradle.api.tasks.*
import org.gradle.api.tasks.Optional
import org.jdom2.input.SAXBuilder
import java.io.File
import org.gradle.api.Project as GradleProject

@CacheableTask
open class Inspection : SourceTask(), VerificationTask, Reporting<CheckstyleReports> {

    object ClassloaderContainer {
        @JvmField
        var customClassLoader: ClassLoader? = null

        fun getOrInit(init: () -> ClassLoader): ClassLoader {
            return customClassLoader ?: init().apply {
                customClassLoader = this
            }
        }
    }

    /**
     * The class path containing the compiled classes for the source files to be analyzed.
     */
    @get:Classpath
    lateinit var classpath: FileCollection

    /**
     * The configuration to use. Replaces the `configFile` property.
     */
    var config: TextResource
        get() = extension.config
        set(value) {
            extension.config = value
        }

    /**
     * The properties available for use in the configuration file. These are substituted into the configuration file.
     */
    @get:Input
    @get:Optional
    var configProperties: Map<String, Any> = LinkedHashMap()

    private val reports = IdeaCheckstyleReports(this)
    private var ignoreFailures: Boolean = false

    private val extension get() = project.extensions.findByType(InspectionPluginExtension::class.java)!!

    /**
     * The maximum number of errors that are tolerated before breaking the build
     * or setting the failure property.
     *
     * @return the maximum number of errors allowed
     */
    var maxErrors: Int
        @Input get() = extension.maxErrors
        set(value) {
            extension.maxErrors = value
        }

    /**
     * The maximum number of warnings that are tolerated before breaking the build
     * or setting the failure property.
     *
     * @return the maximum number of warnings allowed
     */
    var maxWarnings: Int
        @Input get() = extension.maxWarnings
        set(value) {
            extension.maxWarnings = value
        }

    /**
     * Whether rule violations are to be displayed on the console.
     *
     * @return true if violations should be displayed on console
     */

    @get:Console
    var showViolations: Boolean
        @Input get() = extension.isShowViolations
        set(value) {
            extension.isShowViolations = value
        }


    /**
     * The configuration file to use.
     */
    var configFile: File?
        @InputFile
        get() = config.asFile()
        set(configFile) {
            config = project.resources.text.fromFile(configFile)
        }

    /**
     * Configures the reports to be generated by this task.
     *
     * The contained reports can be configured by name and closures. Example:
     *
     * <pre>
     * inspection {
     *     reports {
     *         html {
     *             destination "build/codenarc.html"
     *         }
     *         xml {
     *             destination "build/report.xml"
     *         }
     *     }
     * }
     * </pre>
     *
     *
     * @param closure The configuration
     * @return The reports container
     */
    override fun reports(
            @DelegatesTo(value = CheckstyleReports::class, strategy = Closure.DELEGATE_FIRST) closure: Closure<*>
    ): CheckstyleReports = reports(ClosureBackedAction(closure))

    /**
     * Configures the reports to be generated by this task.
     *
     * The contained reports can be configured by name and closures. Example:
     *
     * <pre>
     * checkstyleTask {
     *     reports {
     *         html {
     *             destination "build/codenarc.html"
     *         }
     *     }
     * }
     * </pre>
     *
     * @param configureAction The configuration
     * @return The reports container
     */
    override fun reports(configureAction: Action<in CheckstyleReports>): CheckstyleReports {
        configureAction.execute(reports)
        return reports
    }

    private fun readInspectionClassesFromConfigFile(): InspectionClassesSuite {
        val builder = SAXBuilder()
        val document = builder.build(configFile)
        val root = document.rootElement

        val inheritFromIdea = root.getChild("inheritFromIdea") != null
        if (inheritFromIdea) {
            return InspectionClassesSuite()
        }
        val errorClasses = root.getChild("errors").children.map { it.getAttributeValue("class") }
        val warningClasses = root.getChild("warnings").children.map { it.getAttributeValue("class") }
        val infoClasses = root.getChild("infos").children.map { it.getAttributeValue("class") }

        return InspectionClassesSuite(errorClasses, warningClasses, infoClasses)
    }

    private fun tryResolveRunnerJar(project: org.gradle.api.Project): File = try {
        val dependency = project.buildscript.dependencies.create(
                "org.jetbrains.intellij.plugins:inspection-runner:0.1-SNAPSHOT"
        )
        val configuration = project.buildscript.configurations.detachedConfiguration(dependency)
        configuration.description = "Runner main jar"
        configuration.resolve().first()
    } catch (e: Exception) {
        project.parent?.let { tryResolveRunnerJar(it) } ?: throw e
    }

    @TaskAction
    fun run() {
        try {
            val ideaDirectory = UnzipTask.cacheDirectory
            val ideaClasspath = listOf(
                File(ideaDirectory, "lib")
            ).map {
                it.listFiles { _, name -> name.endsWith("jar") }.toList()
            }.flatten()
            val fullClasspath = (listOf(tryResolveRunnerJar(project)) + ideaClasspath).map { it.toURI().toURL() }
            logger.info("Inspection runner classpath: $fullClasspath")
            val loader = ClassloaderContainer.getOrInit {
                ChildFirstClassLoader(
                        classpath = fullClasspath.toTypedArray(),
                        parent = this.javaClass.classLoader
                )
            }

            val inspectionClasses = readInspectionClassesFromConfigFile()
            @Suppress("UNCHECKED_CAST")
            val analyzerClass = loader.loadClass(
                    "org.jetbrains.idea.inspections.InspectionRunner"
            ) as Class<Analyzer>
            val analyzer = analyzerClass.constructors.first().newInstance(
                    project, maxErrors, maxWarnings,
                    showViolations, inspectionClasses, reports,
                    logger
            ).let { analyzerClass.cast(it) }
            if (!analyzer.analyzeTreeAndLogResults(getSource())) {
                throw TaskExecutionException(this, null)
            }
        }
        catch (e: Throwable) {
            logger.error("EXCEPTION caught in inspections plugin: " + e.message)
            if (e is GradleException) throw e
            throw GradleException("Exception occurred in analyze task: ${e.message}", e)
        }
    }

    /**
     * {@inheritDoc}
     *
     *
     * The sources for this task are relatively relocatable even though it produces output that
     * includes absolute paths. This is a compromise made to ensure that results can be reused
     * between different builds. The downside is that up-to-date results, or results loaded
     * from cache can show different absolute paths than would be produced if the task was
     * executed.
     */
    @PathSensitive(PathSensitivity.RELATIVE)
    override fun getSource(): FileTree = super.getSource()

     /**
     * The reports to be generated by this task.
     */
    @Nested
    override fun getReports(): CheckstyleReports = reports

    /**
     * Whether or not this task will ignore failures and continue running the build.
     *
     * @return true if failures should be ignored
     */
    @Input
    override fun getIgnoreFailures(): Boolean = ignoreFailures

    /**
     * Whether this task will ignore failures and continue running the build.
     *
     * @return true if failures should be ignored
     */
    @Suppress("unused")
    open fun isIgnoreFailures(): Boolean = ignoreFailures

    /**
     * Whether this task will ignore failures and continue running the build.
     */
    override fun setIgnoreFailures(ignoreFailures: Boolean) {
        this.ignoreFailures = ignoreFailures
    }

    fun setSourceSet(source: FileTree) {
        setSource(source as Any)
    }
}