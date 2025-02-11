// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codemodernizer.ideMaven

import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.project.Project
import org.jetbrains.idea.maven.execution.MavenRunner
import org.jetbrains.idea.maven.execution.MavenRunnerParameters
import org.jetbrains.idea.maven.execution.MavenRunnerSettings
import org.slf4j.Logger
import software.aws.toolkits.core.utils.error
import software.aws.toolkits.core.utils.info
import software.aws.toolkits.jetbrains.services.codemodernizer.model.MavenCopyCommandsResult
import software.aws.toolkits.jetbrains.services.codemodernizer.state.CodeTransformTelemetryState
import software.aws.toolkits.telemetry.CodeTransformMavenBuildCommand
import software.aws.toolkits.telemetry.CodetransformTelemetry
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

private fun emitMavenFailure(error: String, logger: Logger, throwable: Throwable? = null) {
    if (throwable != null) logger.error(throwable) { error } else logger.error { error }
    CodetransformTelemetry.mvnBuildFailed(
        codeTransformSessionId = CodeTransformTelemetryState.instance.getSessionId(),
        codeTransformMavenBuildCommand = CodeTransformMavenBuildCommand.IDEBundledMaven,
        reason = error
    )
}

fun runMavenCopyCommands(sourceFolder: File, buildlogBuilder: StringBuilder, logger: Logger, project: Project): MavenCopyCommandsResult {
    val currentTimestamp = System.currentTimeMillis()
    val destinationDir = Files.createTempDirectory("transformation_dependencies_temp_$currentTimestamp")

    logger.info { "Executing IntelliJ bundled Maven" }
    try {
        // Create shared parameters
        val transformMvnRunner = TransformMavenRunner(project)
        val mvnSettings = MavenRunner.getInstance(project).settings.clone() // clone required to avoid editing user settings

        // Run clean
        val cleanRunnable = runMavenClean(sourceFolder, buildlogBuilder, mvnSettings, transformMvnRunner, logger)
        cleanRunnable.await()
        buildlogBuilder.appendLine(cleanRunnable.getOutput())
        if (cleanRunnable.isComplete() == 0) {
            val successMsg = "IntelliJ bundled Maven clean executed successfully"
            logger.info { successMsg }
            buildlogBuilder.appendLine(successMsg)
        } else if (cleanRunnable.isComplete() != Integer.MIN_VALUE) {
            emitMavenFailure("Maven Clean: bundled Maven failed: exitCode ${cleanRunnable.isComplete()}", logger)
            return MavenCopyCommandsResult.Failure
        }

        // Run install
        val installRunnable = runMavenInstall(sourceFolder, buildlogBuilder, mvnSettings, transformMvnRunner, logger)
        installRunnable.await()
        buildlogBuilder.appendLine(installRunnable.getOutput())
        if (installRunnable.isComplete() == 0) {
            val successMsg = "IntelliJ bundled Maven install executed successfully"
            logger.info { successMsg }
            buildlogBuilder.appendLine(successMsg)
        } else if (installRunnable.isComplete() != Integer.MIN_VALUE) {
            emitMavenFailure("Maven Install: bundled Maven failed: exitCode ${installRunnable.isComplete()}", logger)
            return MavenCopyCommandsResult.Failure
        }

        // run copy dependencies
        val copyDependenciesRunnable = runMavenCopyDependencies(sourceFolder, buildlogBuilder, mvnSettings, transformMvnRunner, destinationDir, logger)
        copyDependenciesRunnable.await()
        buildlogBuilder.appendLine(copyDependenciesRunnable.getOutput())
        if (copyDependenciesRunnable.isComplete() == 0) {
            val successMsg = "IntelliJ bundled Maven copy-dependencies executed successfully"
            logger.info { successMsg }
            buildlogBuilder.appendLine(successMsg)
        } else {
            emitMavenFailure("Maven Copy: bundled Maven failed: exitCode ${copyDependenciesRunnable.isComplete()}", logger)
            return MavenCopyCommandsResult.Failure
        }
    } catch (t: Throwable) {
        emitMavenFailure("IntelliJ bundled Maven executed failed: ${t.message}", logger, t)
        return MavenCopyCommandsResult.Failure
    }
    // When all commands executed successfully, show the transformation hub
    return MavenCopyCommandsResult.Success(destinationDir.toFile())
}

private fun runMavenCopyDependencies(
    sourceFolder: File,
    buildlogBuilder: StringBuilder,
    mvnSettings: MavenRunnerSettings,
    transformMavenRunner: TransformMavenRunner,
    destinationDir: Path,
    logger: Logger,
): TransformRunnable {
    buildlogBuilder.appendLine("Command Run: IntelliJ bundled Maven dependency:copy-dependencies")
    val copyCommandList = listOf(
        "dependency:copy-dependencies",
        "-DoutputDirectory=$destinationDir",
        "-Dmdep.useRepositoryLayout=true",
        "-Dmdep.copyPom=true",
        "-Dmdep.addParentPoms=true",
        "-q",
    )
    val copyParams = MavenRunnerParameters(
        false,
        sourceFolder.absolutePath,
        null,
        copyCommandList,
        emptyList<String>(),
        null
    )
    val copyTransformRunnable = TransformRunnable()
    runInEdt {
        try {
            transformMavenRunner.run(copyParams, mvnSettings, copyTransformRunnable)
        } catch (t: Throwable) {
            val error = "Maven Copy: Unexpected error when executing bundled Maven copy dependencies"
            copyTransformRunnable.exitCode(Integer.MIN_VALUE) // to stop looking for the exitCode
            logger.error(t) { error }
            buildlogBuilder.appendLine("IntelliJ bundled Maven copy dependencies failed: ${t.message}")
            CodetransformTelemetry.mvnBuildFailed(
                codeTransformSessionId = CodeTransformTelemetryState.instance.getSessionId(),
                codeTransformMavenBuildCommand = CodeTransformMavenBuildCommand.IDEBundledMaven,
                reason = error
            )
        }
    }
    return copyTransformRunnable
}

private fun runMavenClean(
    sourceFolder: File,
    buildlogBuilder: StringBuilder,
    mvnSettings: MavenRunnerSettings,
    transformMavenRunner: TransformMavenRunner,
    logger: Logger
): TransformRunnable {
    buildlogBuilder.appendLine("Command Run: IntelliJ bundled Maven clean")
    val cleanParams = MavenRunnerParameters(
        false,
        sourceFolder.absolutePath,
        null,
        listOf("clean", "-q"),
        emptyList<String>(),
        null
    )
    val cleanTransformRunnable = TransformRunnable()
    runInEdt {
        try {
            transformMavenRunner.run(cleanParams, mvnSettings, cleanTransformRunnable)
        } catch (t: Throwable) {
            val error = "Maven Clean: Unexpected error when executing bundled Maven clean"
            cleanTransformRunnable.exitCode(Integer.MIN_VALUE) // to stop looking for the exitCode
            buildlogBuilder.appendLine("IntelliJ bundled Maven clean failed: ${t.message}")
            emitMavenFailure(error, logger, t)
        }
    }
    return cleanTransformRunnable
}

private fun runMavenInstall(
    sourceFolder: File,
    buildlogBuilder: StringBuilder,
    mvnSettings: MavenRunnerSettings,
    transformMavenRunner: TransformMavenRunner,
    logger: Logger
): TransformRunnable {
    buildlogBuilder.appendLine("Command Run: IntelliJ bundled Maven install")
    val installParams = MavenRunnerParameters(
        false,
        sourceFolder.absolutePath,
        null,
        listOf("install", "-q"),
        emptyList<String>(),
        null
    )
    val installTransformRunnable = TransformRunnable()
    runInEdt {
        try {
            transformMavenRunner.run(installParams, mvnSettings, installTransformRunnable)
        } catch (t: Throwable) {
            val error = "Maven Install: Unexpected error when executing bundled Maven install"
            installTransformRunnable.exitCode(Integer.MIN_VALUE) // to stop looking for the exitCode
            buildlogBuilder.appendLine("IntelliJ bundled Maven install failed: ${t.message}")
            emitMavenFailure(error, logger, t)
        }
    }
    return installTransformRunnable
}
