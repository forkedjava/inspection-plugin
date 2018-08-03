package org.jetbrains.intellij

import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.plugins.quality.CodeQualityExtension
import org.gradle.api.plugins.quality.internal.AbstractCodeQualityPlugin
import org.gradle.api.tasks.SourceSet
import org.jetbrains.intellij.extensions.InspectionsExtension
import org.jetbrains.intellij.tasks.*
import org.jetbrains.intellij.versions.IdeaVersion
import org.jetbrains.intellij.versions.KotlinPluginVersion
import org.jetbrains.intellij.versions.ToolVersion
import java.io.File

open class InspectionPlugin : AbstractCodeQualityPlugin<InspectionsTask>() {

    private val inspectionExtension: InspectionsExtension
        get() = extension as InspectionsExtension

    override fun getToolName(): String = "IDEA Inspections"

    override fun getTaskType(): Class<InspectionsTask> = InspectionsTask::class.java

    override fun getConfigurationName(): String = SHORT_NAME

    override fun getTaskBaseName(): String = SHORT_NAME

    override fun getReportName(): String = SHORT_NAME

    override fun createExtension(): CodeQualityExtension {
        val extension = project.extensions.create(SHORT_NAME, InspectionsExtension::class.java, project)
        LOG.info("Extension $SHORT_NAME created")
        return extension
    }

    override fun beforeApply() {
        project.repositories.maven { it.setUrl("https://www.jetbrains.com/intellij-repository/releases") }
        configureTasksDefaults()
        project.rootProject.plugins.apply("idea")
        for (subProject in project.rootProject.subprojects) {
            subProject.plugins.apply("idea")
        }
    }

    override fun configureTaskDefaults(task: InspectionsTask, baseName: String) {
        val baseType = BaseType(baseName)
        task.baseType = baseType
        configureReformatTaskDefaults(baseType)
        configureIdeaInspectionsTaskDependencies(task)
        configureDefaultDependencies(task)
        configureReportsConventionMapping(task, baseName)
    }

    private fun configureTasksDefaults() {
        project.tasks.create(CLEAN_TASK_NAME, CleanTask::class.java)
        project.tasks.create(UNZIP_IDEA_TASK_NAME, UnzipIdeaTask::class.java)
        project.tasks.create(DOWNLOAD_KOTLIN_PLUGIN_TASK_NAME, DownloadKotlinPluginTask::class.java)
        project.tasks.create(UNZIP_KOTLIN_PLUGIN_TASK_NAME, UnzipKotlinPluginTask::class.java) {
            it.dependsOn += project.tasks.getByName(DOWNLOAD_KOTLIN_PLUGIN_TASK_NAME)
        }
    }

    private fun configureReformatTaskDefaults(baseType: BaseType) {
        project.tasks.create(reformatTaskName(baseType), ReformatTask::class.java) {
            it.baseType = baseType
            it.dependsOn += project.tasks.getByName(UNZIP_IDEA_TASK_NAME)
            it.dependsOn += project.tasks.getByName(UNZIP_KOTLIN_PLUGIN_TASK_NAME)
            it.dependsOn += project.rootProject.tasks.getByName("idea")
            for (subProject in project.rootProject.subprojects) {
                it.dependsOn += subProject.tasks.getByName("idea")
            }
        }
    }

    private fun configureIdeaInspectionsTaskDependencies(task: InspectionsTask) {
        task.dependsOn += project.tasks.getByName(UNZIP_IDEA_TASK_NAME)
        task.dependsOn += project.tasks.getByName(UNZIP_KOTLIN_PLUGIN_TASK_NAME)
        task.dependsOn += project.rootProject.tasks.getByName("idea")
        for (subProject in project.rootProject.subprojects) {
            task.dependsOn += subProject.tasks.getByName("idea")
        }
    }

    private fun configureDefaultDependencies(task: InspectionsTask) {
        project.configurations.getByName(SHORT_NAME).defaultDependencies {
            it.add(project.dependencies.create(task.ideaVersion.mavenUrl))
        }
    }

    private fun configureReportsConventionMapping(task: InspectionsTask, baseName: String) {
        task.reports.all { report ->
            val reportMapping = AbstractCodeQualityPlugin.conventionMappingOf(report)
            reportMapping.map("enabled") { true }
            reportMapping.map("destination") {
                File(inspectionExtension.reportsDir, "$baseName.${report.name}")
            }
        }
    }

    override fun configureForSourceSet(sourceSet: SourceSet, task: InspectionsTask) {
        task.description = "Run IDEA inspections for " + sourceSet.name + " classes"
        task.classpath = sourceSet.output.plus(sourceSet.compileClasspath)
        task.source = sourceSet.allSource
        val baseType = task.baseType
        val reformatTask = project.tasks.getByName(reformatTaskName(baseType)) as ReformatTask
        reformatTask.source = sourceSet.allSource
    }

    companion object {

        private val DEFAULT_TOOL_VERSION = ToolVersion.IP_0_1_4

        private val LOG: Logger = Logging.getLogger(InspectionPlugin::class.java)

        internal const val SHORT_NAME = "inspections"

        private const val CLEAN_TASK_NAME = SHORT_NAME + "Clean"

        private const val UNZIP_IDEA_TASK_NAME = "unzip-idea"

        private const val DOWNLOAD_KOTLIN_PLUGIN_TASK_NAME = "download-kotlin-plugin"

        private const val UNZIP_KOTLIN_PLUGIN_TASK_NAME = "unzip-kotlin-plugin"

        private fun reformatTaskName(baseType: BaseType) = "reformat" + baseType.baseTitle

        private val TEMP_DIRECTORY: File
            get() = File(System.getProperty("java.io.tmpdir"))

        internal val BASE_CACHE_DIRECTORY: File
            get() = File(TEMP_DIRECTORY, "inspection-plugin")

        internal val DEPENDENCY_SOURCE_DIRECTORY: File
            get() = File(BASE_CACHE_DIRECTORY, "dependencies")

        private val DOWNLOAD_DIRECTORY: File
            get() = File(InspectionPlugin.BASE_CACHE_DIRECTORY, "downloads")

        internal fun kotlinPluginSource(kotlinPluginVersion: KotlinPluginVersion) =
                File(DOWNLOAD_DIRECTORY, kotlinPluginVersion.value.normalizeVersion + ".zip")

        internal fun kotlinPluginDirectory(kotlinPluginVersion: KotlinPluginVersion) =
                File(DEPENDENCY_SOURCE_DIRECTORY, kotlinPluginVersion.value.normalizeVersion)

        internal fun ideaDirectory(ideaVersion: IdeaVersion) =
                File(DEPENDENCY_SOURCE_DIRECTORY, ideaVersion.value.normalizeVersion)

        private val String.normalizeVersion: String
            get() = replace(':', '_').replace('.', '_').replace('-', '_')

        internal fun toolVersion(toolVersion: String?): ToolVersion {
            if (toolVersion == null) return DEFAULT_TOOL_VERSION
            return ToolVersion(toolVersion)
        }

        internal fun ideaVersion(ideaVersion: String?): IdeaVersion {
            if (ideaVersion == null) return IdeaVersion.IDEA_IC_2017_3
            val version = IdeaVersion(ideaVersion)
            if (version is IdeaVersion.Other)
                LOG.warn("Uses custom idea version: $version")
            return version
        }

        internal fun kotlinPluginVersion(ideaVersion: IdeaVersion, kotlinPluginVersion: String?, url: String?): KotlinPluginVersion {
            if (kotlinPluginVersion == null) return defaultKotlinPluginVersion(ideaVersion)
            val version = KotlinPluginVersion(kotlinPluginVersion, url)
            if (version is KotlinPluginVersion.Other) {
                LOG.warn("Uses custom kotlin plugin version $version")
            } else if (url != null) {
                LOG.warn("Uses custom kotlin plugin sources for defined version $version")
            }
            return version
        }

        private fun defaultKotlinPluginVersion(ideaVersion: IdeaVersion) = when (ideaVersion) {
            IdeaVersion.IDEA_IC_2017_2 -> KotlinPluginVersion.RELEASE_IJ2017_2_1__1_2_60
            IdeaVersion.IDEA_IC_2017_3 -> KotlinPluginVersion.RELEASE_IJ2017_3_1__1_2_60
            IdeaVersion.IDEA_IC_2018_1 -> KotlinPluginVersion.RELEASE_IJ2018_1_1__1_2_60
            IdeaVersion.IDEA_IC_2018_2 -> KotlinPluginVersion.RELEASE_IJ2018_2_1__1_2_60
            else -> throw IllegalArgumentException("")
        }
    }
}
