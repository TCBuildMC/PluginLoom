/*
 * PluginLoom
 * Copyright (c) 2024 Tube Craft Server
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.tcbuildmc.pluginloom.spigot.nms

import org.gradle.api.Plugin
import org.gradle.api.Project
import xyz.tcbuildmc.pluginloom.common.util.ConditionUtils
import xyz.tcbuildmc.pluginloom.common.util.Constants
import xyz.tcbuildmc.pluginloom.common.util.NIOUtils
import xyz.tcbuildmc.pluginloom.spigot.PluginLoomSpigot
import xyz.tcbuildmc.pluginloom.spigot.PluginLoomSpigotExtension
import xyz.tcbuildmc.pluginloom.spigot.nms.task.buildtools.RunBuildToolsTask
import xyz.tcbuildmc.pluginloom.spigot.nms.task.nms.CopyArtifactsTask
import xyz.tcbuildmc.pluginloom.spigot.nms.task.remap.RemapJarTask
import xyz.tcbuildmc.pluginloom.spigot.nms.task.buildtools.DownloadBuildToolsTask
import xyz.tcbuildmc.pluginloom.spigot.nms.task.buildtools.ReRunBuildToolsTask

class PluginLoomSpigotNMS implements Plugin<Project> {
    @Override
    void apply(Project project) {
        if (!project.plugins.hasPlugin(PluginLoomSpigot)) {
            throw new IllegalArgumentException()
        }

        def loomCache = "${project.projectDir.canonicalPath}/.gradle/pluginloom"

        def baseExt = project.extensions.getByType(PluginLoomSpigotExtension)
        def ext = project.extensions.create("pluginloomNMS", PluginLoomSpigotNMSExtension, project)

        def remapMojmapToObfTask = project.tasks.register("remapMojmapToObf", RemapJarTask) { tsk ->
            tsk.dependsOn(ext.inputJarTask)
            tsk.group = Constants.TASK_GROUP
            tsk.description = "Remaps the built project Mojmap jar to obf mappings."

            tsk.inputJar = ext.inputJarTask.archiveFile.get().asFile

            // ...
            tsk.outputJar = ext.inputJarTask.destinationDirectory.file(getRemappedJarName(ext, ext.obfJarClassifier)).get().asFile

            tsk.mappingsFile = new File("${loomCache}/repo/org/spigotmc/minecraft-server/${ConditionUtils.requiresNonNullOrEmpty(baseExt.base.spigotApiVersion)}/minecraft-server-${ConditionUtils.requiresNonNullOrEmpty(baseExt.base.spigotApiVersion)}-maps-mojang.txt")
            tsk.reverse = true
        }

        def remapObfToSpigotTask = project.tasks.register("remapObfToSpigot", RemapJarTask) { tsk ->
            tsk.group = Constants.TASK_GROUP
            tsk.description = "Remaps the built project obf jar to Spigot mappings."
            tsk.dependsOn(ext.inputJarTask)
            tsk.dependsOn(remapMojmapToObfTask.get())

            tsk.inputJar = remapMojmapToObfTask.get().outputJar
            tsk.outputJar = ext.inputJarTask.destinationDirectory.file(getRemappedJarName(ext, ext.spigotMappingsJarClassifier)).get().asFile

            tsk.mappingsFile = new File("${loomCache}/repo/org/spigotmc/minecraft-server/${ConditionUtils.requiresNonNullOrEmpty(baseExt.base.spigotApiVersion)}/minecraft-server-${ConditionUtils.requiresNonNullOrEmpty(baseExt.base.spigotApiVersion)}-maps-spigot.csrg")
        }

        def assemble = project.tasks.named("assemble").get()
        assemble.dependsOn(remapObfToSpigotTask)

        def buildToolsDir = "${loomCache}/buildTools"

        def reRunBuildToolsTask = project.tasks.register("reRunBuildTools", ReRunBuildToolsTask) { tsk ->
            tsk.group = Constants.TASK_GROUP
            tsk.description = "Re-runs `BuildTools.jar`."

            tsk.buildToolsFile = new File(buildToolsDir, "BuildTools.jar")
            tsk.mcVersion = ConditionUtils.requiresNonNullOrEmpty(baseExt.base.mcVersion)
            tsk.workDir = new File(buildToolsDir)
        }

        project.afterEvaluate {
            prepareNMS(project, baseExt, loomCache)
        }
    }

    void prepareNMS(final Project project, final PluginLoomSpigotExtension ext, final String loomCache) {
        project.logger.lifecycle("> :executing 3 steps to prepare Spigot NMS")

        def buildToolsDir = "${loomCache}/buildTools"

        project.logger.lifecycle("> :step 1 Download BuildTools")
        def task1 = new DownloadBuildToolsTask(project)
        task1.buildToolsDir = buildToolsDir
        task1.timeout = ext.base.timeout
        task1.run()

        def mavenLocalDir = "${NIOUtils.getMavenLocalDir()}/repository/org/spigotmc"

        project.logger.lifecycle("> :step 2 Run BuildTools")
        def task2 = new RunBuildToolsTask(project)
        task2.workDir = new File(buildToolsDir)
        task2.mcVersion = ConditionUtils.requiresNonNullOrEmpty(ext.base.mcVersion)
        task2.buildToolsFile = new File(buildToolsDir, "BuildTools.jar")
        task2.mavenDir = new File(mavenLocalDir)
        task2.run()

        def mavenCache = "${loomCache}/repo/org"

        project.logger.lifecycle("> :step 3 Copy Artifacts")
        def task3 = new CopyArtifactsTask()
        task3.mavenDir = new File(mavenLocalDir)
        task3.outputDir = new File(mavenCache)
        task3.run()

        project.logger.lifecycle("> :prepare Spigot NMS done")
    }

    private String getRemappedJarName(final PluginLoomSpigotNMSExtension ext, final String archivesClassifier) {
        return new StringBuilder()
                .append(ext.inputJarTask.archiveBaseName.getOrElse(""))
                .append("-")
//                .append(ext.inputJarTask.archiveAppendix.getOrElse(""))
//                .append("-")
                .append(ext.inputJarTask.archiveVersion.getOrElse(""))
                .append("-")
                .append(ConditionUtils.requiresNonNullOrEmpty(archivesClassifier))
                .append(".")
                .append(ext.inputJarTask.archiveExtension.getOrElse("jar"))
                .toString()
    }
}
