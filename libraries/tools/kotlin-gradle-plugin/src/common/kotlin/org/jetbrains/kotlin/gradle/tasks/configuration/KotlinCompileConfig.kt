/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.tasks.configuration

import org.gradle.api.Project
import org.gradle.api.artifacts.transform.TransformSpec
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinTopLevelExtension
import org.jetbrains.kotlin.gradle.internal.ClassLoadersCachingBuildService
import org.jetbrains.kotlin.gradle.internal.transforms.BuildToolsApiClasspathEntrySnapshotTransform
import org.jetbrains.kotlin.gradle.internal.transforms.ClasspathEntrySnapshotTransform
import org.jetbrains.kotlin.gradle.plugin.BUILD_TOOLS_API_CLASSPATH_CONFIGURATION_NAME
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilationInfo
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJvmAndroidCompilation
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJvmCompilation
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinWithJavaCompilation
import org.jetbrains.kotlin.gradle.utils.markResolvable
import org.jetbrains.kotlin.gradle.plugin.tcsOrNull
import org.jetbrains.kotlin.gradle.report.BuildMetricsService
import org.jetbrains.kotlin.gradle.tasks.DefaultKotlinJavaToolchain
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.utils.providerWithLazyConvention

internal typealias KotlinCompileConfig = BaseKotlinCompileConfig<KotlinCompile>

internal open class BaseKotlinCompileConfig<TASK : KotlinCompile> : AbstractKotlinCompileConfig<TASK> {

    init {
        configureTaskProvider { taskProvider ->
            val useClasspathSnapshot = propertiesProvider.useClasspathSnapshot
            val classpathConfiguration = if (useClasspathSnapshot) {
                val runKotlinCompilerViaBuildToolsApi = propertiesProvider.runKotlinCompilerViaBuildToolsApi
                val jvmToolchain = taskProvider.flatMap { it.defaultKotlinJavaToolchain }
                registerTransformsOnce(project, jvmToolchain, runKotlinCompilerViaBuildToolsApi)
                // Note: Creating configurations should be done during build configuration, not task configuration, to avoid issues with
                // composite builds (e.g., https://issuetracker.google.com/183952598).
                project.configurations.detachedConfiguration(
                    project.dependencies.create(objectFactory.fileCollection().from(project.provider { taskProvider.get().libraries }))
                ).markResolvable()
            } else null

            taskProvider.configure { task ->
                task.incremental = propertiesProvider.incrementalJvm ?: true
                task.usePreciseJavaTracking = propertiesProvider.usePreciseJavaTracking ?: true
                task.jvmTargetValidationMode.convention(propertiesProvider.jvmTargetValidationMode).finalizeValueOnRead()
                task.useKotlinAbiSnapshot.value(propertiesProvider.useKotlinAbiSnapshot).disallowChanges()

                task.classpathSnapshotProperties.useClasspathSnapshot.value(useClasspathSnapshot).disallowChanges()
                if (useClasspathSnapshot) {
                    val classpathEntrySnapshotFiles = classpathConfiguration!!.incoming.artifactView {
                        it.attributes.attribute(ARTIFACT_TYPE_ATTRIBUTE, CLASSPATH_ENTRY_SNAPSHOT_ARTIFACT_TYPE)
                    }.files
                    task.classpathSnapshotProperties.classpathSnapshot.from(classpathEntrySnapshotFiles).disallowChanges()
                    task.classpathSnapshotProperties.classpathSnapshotDir.value(getClasspathSnapshotDir(task)).disallowChanges()
                } else {
                    task.classpathSnapshotProperties.classpath.from(task.project.provider { task.libraries }).disallowChanges()
                }
                task.taskOutputsBackupExcludes.addAll(
                    task.classpathSnapshotProperties.classpathSnapshotDir.asFile.flatMap {
                        // it looks weird, but it's required to work around this issue: https://github.com/gradle/gradle/issues/17704
                        objectFactory.providerWithLazyConvention { listOf(it) }
                    }.orElse(emptyList())
                )
            }
        }
    }

    constructor(compilationInfo: KotlinCompilationInfo) : super(compilationInfo) {
        val javaTaskProvider = when (val compilation = compilationInfo.tcsOrNull?.compilation) {
            is KotlinJvmCompilation -> compilation.compileJavaTaskProviderSafe
            is KotlinJvmAndroidCompilation -> compilation.compileJavaTaskProvider
            is KotlinWithJavaCompilation<*, *> -> compilation.compileJavaTaskProvider
            else -> null
        }

        configureTaskProvider { taskProvider ->
            taskProvider.configure { task ->
                javaTaskProvider?.let { javaTask ->
                    task.associatedJavaCompileTaskTargetCompatibility.value(javaTask.map { it.targetCompatibility })
                    task.associatedJavaCompileTaskName.value(javaTask.map { it.name })
                }

                @Suppress("DEPRECATION")
                task.ownModuleName.value(
                    providers.provider {
                        task.parentKotlinOptions.orNull?.moduleName ?: compilationInfo.moduleName
                    })

                // In case of 'org.jetbrains.kotlin.jvm' and 'org.jetbrains.kotlin.android' plugins module name will be pre-configured
                if (ext !is KotlinJvmProjectExtension && ext !is KotlinAndroidProjectExtension) {
                    task.compilerOptions.moduleName.convention(providers.provider { compilationInfo.moduleName })
                } else {
                    task.nagTaskModuleNameUsage.set(true)
                }
            }
        }
    }


    constructor(project: Project, ext: KotlinTopLevelExtension) : super(
        project, ext
    )

    companion object {
        private const val TRANSFORMS_REGISTERED = "_kgp_internal_kotlin_compile_transforms_registered"

        val ARTIFACT_TYPE_ATTRIBUTE: Attribute<String> = Attribute.of("artifactType", String::class.java)
        private const val DIRECTORY_ARTIFACT_TYPE = "directory"
        private const val JAR_ARTIFACT_TYPE = "jar"
        const val CLASSPATH_ENTRY_SNAPSHOT_ARTIFACT_TYPE = "classpath-entry-snapshot"
    }

    private fun registerTransformsOnce(
        project: Project,
        jvmToolchain: Provider<DefaultKotlinJavaToolchain>,
        runKotlinCompilerViaBuildToolsApi: Boolean,
    ) {
        if (project.extensions.extraProperties.has(TRANSFORMS_REGISTERED)) {
            return
        }
        project.extensions.extraProperties[TRANSFORMS_REGISTERED] = true

        if (runKotlinCompilerViaBuildToolsApi) {
            registerBuildToolsApiTransformations(project, jvmToolchain)
        } else {
            registerOldTransformations(project)
        }
    }

    private fun registerOldTransformations(project: Project) {
        val buildMetricsService = BuildMetricsService.registerIfAbsent(project)
        project.dependencies.registerTransform(ClasspathEntrySnapshotTransform::class.java) {
            it.from.attribute(ARTIFACT_TYPE_ATTRIBUTE, JAR_ARTIFACT_TYPE)
            it.to.attribute(ARTIFACT_TYPE_ATTRIBUTE, CLASSPATH_ENTRY_SNAPSHOT_ARTIFACT_TYPE)
            it.parameters.gradleUserHomeDir.set(project.gradle.gradleUserHomeDir)
            buildMetricsService?.apply { it.parameters.buildMetricsService.set(this) }
        }
        project.dependencies.registerTransform(ClasspathEntrySnapshotTransform::class.java) {
            it.from.attribute(ARTIFACT_TYPE_ATTRIBUTE, DIRECTORY_ARTIFACT_TYPE)
            it.to.attribute(ARTIFACT_TYPE_ATTRIBUTE, CLASSPATH_ENTRY_SNAPSHOT_ARTIFACT_TYPE)
            it.parameters.gradleUserHomeDir.set(project.gradle.gradleUserHomeDir)
            buildMetricsService?.apply { it.parameters.buildMetricsService.set(this) }
        }
    }

    private fun TransformSpec<BuildToolsApiClasspathEntrySnapshotTransform.Parameters>.configureCommonParameters(
        classLoadersCachingService: Provider<ClassLoadersCachingBuildService>,
        classpath: Provider<out FileCollection>,
        jvmToolchain: Provider<DefaultKotlinJavaToolchain>,
    ) {
        parameters.gradleUserHomeDir.set(project.gradle.gradleUserHomeDir)
        parameters.classLoadersCachingService.set(classLoadersCachingService)
        parameters.classpath.from(classpath)
        // add some tools.jar in order to reuse some of the classloaders required for compilation
        parameters.classpath.from(jvmToolchain.map { toolchain ->
            if (toolchain.currentJvmJdkToolsJar.isPresent) {
                setOf(toolchain.currentJvmJdkToolsJar.get())
            } else {
                emptySet()
            }
        })
    }

    private fun registerBuildToolsApiTransformations(project: Project, jvmToolchain: Provider<DefaultKotlinJavaToolchain>) {
        val classLoadersCachingService = ClassLoadersCachingBuildService.registerIfAbsent(project)
        val classpath = project.configurations.named(BUILD_TOOLS_API_CLASSPATH_CONFIGURATION_NAME)
        project.dependencies.registerTransform(BuildToolsApiClasspathEntrySnapshotTransform::class.java) {
            it.from.attribute(ARTIFACT_TYPE_ATTRIBUTE, JAR_ARTIFACT_TYPE)
            it.to.attribute(ARTIFACT_TYPE_ATTRIBUTE, CLASSPATH_ENTRY_SNAPSHOT_ARTIFACT_TYPE)
            it.configureCommonParameters(classLoadersCachingService, classpath, jvmToolchain)
        }
        project.dependencies.registerTransform(BuildToolsApiClasspathEntrySnapshotTransform::class.java) {
            it.from.attribute(ARTIFACT_TYPE_ATTRIBUTE, DIRECTORY_ARTIFACT_TYPE)
            it.to.attribute(ARTIFACT_TYPE_ATTRIBUTE, CLASSPATH_ENTRY_SNAPSHOT_ARTIFACT_TYPE)
            it.configureCommonParameters(classLoadersCachingService, classpath, jvmToolchain)
        }
    }

}