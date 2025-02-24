/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2021 Kyle Wood (DenWav)
 *                    Contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation;
 * version 2.1 only, no later versions.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */

package io.papermc.paperweight.tasks

import io.papermc.paperweight.DownloadService
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import io.papermc.paperweight.util.data.*
import java.nio.file.Path
import javax.inject.Inject
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkQueue
import org.gradle.workers.WorkerExecutor
import org.w3c.dom.Document
import org.w3c.dom.Element

// Not cached since these are Mojang's files
abstract class DownloadTask : DefaultTask() {

    @get:Input
    abstract val url: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @get:Internal
    abstract val downloader: Property<DownloadService>

    @TaskAction
    fun run() = downloader.get().download(url, outputFile)
}

@CacheableTask
abstract class DownloadMcLibraries : BaseTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mcLibrariesFile: RegularFileProperty

    @get:Input
    abstract val repositories: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Internal
    abstract val downloader: Property<DownloadService>

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @get:Input
    abstract val sources: Property<Boolean>

    override fun init() {
        super.init()
        sources.convention(false)
    }

    @TaskAction
    fun run() {
        downloadMinecraftLibraries(
            downloader,
            workerExecutor,
            outputDir.path,
            repositories.get(),
            mcLibrariesFile.path.readLines(),
            sources.get()
        )
    }
}

fun downloadMinecraftLibraries(
    download: Provider<DownloadService>,
    workerExecutor: WorkerExecutor,
    targetDir: Path,
    repositories: List<String>,
    mcLibraries: List<String>,
    sources: Boolean
): WorkQueue {
    val excludes = listOf(targetDir.fileSystem.getPathMatcher("glob:*.etag"))
    targetDir.deleteRecursively(excludes)

    val queue = workerExecutor.noIsolation()

    for (lib in mcLibraries) {
        if (sources) {
            queue.submit(DownloadSourcesToDirAction::class) {
                repos.set(repositories)
                artifact.set(lib)
                target.set(targetDir)
                downloader.set(download)
            }
        } else {
            queue.submit(DownloadWorker::class) {
                repos.set(repositories)
                artifact.set(lib)
                target.set(targetDir)
                downloadToDir.set(true)
                downloader.set(download)
            }
        }
    }

    return queue
}

@CacheableTask
abstract class DownloadSpigotDependencies : BaseTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val apiPom: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val serverPom: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mcLibrariesFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputSourcesDir: DirectoryProperty

    @get:Internal
    abstract val downloader: Property<DownloadService>

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @TaskAction
    fun run() {
        val apiSetup = parsePom(apiPom.path)
        val serverSetup = parsePom(serverPom.path)
        val mcLibraries = mcLibrariesFile.path.readLines()

        val out = outputDir.path
        val excludes = listOf(out.fileSystem.getPathMatcher("glob:*.etag"))
        out.deleteRecursively(excludes)

        val outSources = outputSourcesDir.path
        val excludesSources = listOf(outSources.fileSystem.getPathMatcher("glob:*.etag"))
        outSources.deleteRecursively(excludesSources)

        val spigotRepos = mutableSetOf<String>()
        spigotRepos += apiSetup.repos
        spigotRepos += serverSetup.repos

        val artifacts = mutableSetOf<MavenArtifact>()
        artifacts += apiSetup.artifacts
        artifacts += serverSetup.artifacts

        val queue = workerExecutor.noIsolation()
        for (art in artifacts) {
            queue.submit(DownloadWorker::class) {
                repos.set(spigotRepos)
                artifact.set(art.toString())
                target.set(out)
                downloadToDir.set(true)
                downloader.set(this@DownloadSpigotDependencies.downloader)
            }
            if (!mcLibraries.contains(art.toString())) {
                queue.submit(DownloadSourcesToDirAction::class) {
                    repos.set(spigotRepos)
                    artifact.set(art.toString())
                    target.set(outSources)
                    downloader.set(this@DownloadSpigotDependencies.downloader)
                }
            }
        }
    }

    private fun parsePom(pomFile: Path): MavenSetup {
        val depList = arrayListOf<MavenArtifact>()
        // todo dum
        depList += MavenArtifact(
            "com.google.code.findbugs",
            "jsr305",
            "3.0.2"
        )
        depList += MavenArtifact(
            "org.apache.logging.log4j",
            "log4j-api",
            "2.17.0"
        )
        depList += MavenArtifact(
            "org.jetbrains",
            "annotations",
            "23.0.0"
        )
        val repoList = arrayListOf<String>()
        // Maven Central is implicit
        repoList += MAVEN_CENTRAL_URL

        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val doc = pomFile.inputStream().buffered().use { stream ->
            stream.buffered().use { buffered ->
                builder.parse(buffered)
            }
        }

        doc.documentElement.normalize()

        depList += doc.extractDependencies()
        repoList += doc.extractRepos()

        return MavenSetup(repos = repoList, artifacts = depList)
    }

    private fun Document.extractDependencies(): List<MavenArtifact> {
        val depList = arrayListOf<MavenArtifact>()
        val list = getElementsByTagName("dependencies")
        val node = list.item(0) as Element // Only want the first dependencies element
        val depNode = node.getElementsByTagName("dependency")
        for (j in 0 until depNode.length) {
            val dependency = depNode.item(j) as? Element ?: continue
            val artifact = getDependency(dependency) ?: continue
            depList += artifact
        }
        return depList
    }

    private fun Document.extractRepos(): List<String> {
        val repoList = arrayListOf<String>()
        val repos = getElementsByTagName("repositories")
        for (i in 0 until repos.length) {
            val node = repos.item(i) as? Element ?: continue
            val depNode = node.getElementsByTagName("repository")
            for (j in 0 until depNode.length) {
                val repo = depNode.item(j) as? Element ?: continue
                val repoUrl = repo.getElementsByTagName("url").item(0).textContent
                repoList += repoUrl
            }
        }
        return repoList
    }

    private fun getDependency(node: Element): MavenArtifact? {
        val scopeNode = node.getElementsByTagName("scope")
        val scope = if (scopeNode.length == 0) {
            "compile"
        } else {
            scopeNode.item(0).textContent
        }

        if (scope != "compile") {
            return null
        }

        val group = node.getElementsByTagName("groupId").item(0).textContent
        val artifact = node.getElementsByTagName("artifactId").item(0).textContent
        val version = node.getElementsByTagName("version").item(0).textContent

        if (version.contains("\${")) {
            // Don't handle complicated things
            // We don't need to (for now anyways)
            return null
        }

        return MavenArtifact(
            group = group,
            artifact = artifact,
            version = version
        )
    }
}

data class MavenSetup(
    val repos: List<String>,
    val artifacts: List<MavenArtifact>
)

interface DownloadParams : WorkParameters {
    val repos: ListProperty<String>
    val artifact: Property<String>
    val target: RegularFileProperty
    val downloadToDir: Property<Boolean>
    val downloader: Property<DownloadService>
}

abstract class DownloadWorker : WorkAction<DownloadParams> {

    override fun execute() {
        val target = parameters.target.path
        val artifact = MavenArtifact.parse(parameters.artifact.get())

        if (parameters.downloadToDir.get()) {
            artifact.downloadToDir(parameters.downloader.get(), target, parameters.repos.get())
        } else {
            artifact.downloadToFile(parameters.downloader.get(), target, parameters.repos.get())
        }
    }
}

abstract class DownloadSourcesToDirAction : WorkAction<DownloadSourcesToDirAction.Params> {

    interface Params : WorkParameters {
        val repos: ListProperty<String>
        val artifact: Property<String>
        val target: RegularFileProperty
        val downloader: Property<DownloadService>
    }

    override fun execute() {
        val sourceArtifact = MavenArtifact.parse(parameters.artifact.get())
            .copy(classifier = "sources")

        try {
            sourceArtifact.downloadToDir(
                parameters.downloader.get(),
                parameters.target.path,
                parameters.repos.get()
            )
        } catch (ignored: Exception) {
            // Ignore failures because not every artifact we attempt to download actually has sources
        }
    }
}
