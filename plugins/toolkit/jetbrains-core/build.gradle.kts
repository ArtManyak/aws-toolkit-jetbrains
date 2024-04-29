// Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

import com.jetbrains.plugin.structure.base.utils.inputStream
import com.jetbrains.plugin.structure.base.utils.simpleName
import com.jetbrains.plugin.structure.intellij.utils.JDOMUtil
import org.jetbrains.intellij.transformXml
import software.aws.toolkits.gradle.buildMetadata
import software.aws.toolkits.gradle.changelog.tasks.GeneratePluginChangeLog
import software.aws.toolkits.gradle.intellij.IdeFlavor
import software.aws.toolkits.gradle.intellij.IdeVersions
import software.aws.toolkits.gradle.isCi

val toolkitVersion: String by project
val ideProfile = IdeVersions.ideProfile(project)

plugins {
    id("java-library")
    id("toolkit-kotlin-conventions")
    id("toolkit-testing")
    id("toolkit-intellij-subplugin")
    id("toolkit-integration-testing")
}

intellijToolkit {
    ideFlavor.set(IdeFlavor.IC)
}

val changelog = tasks.register<GeneratePluginChangeLog>("pluginChangeLog") {
    includeUnreleased.set(true)
    changeLogFile.set(project.file("$buildDir/changelog/change-notes.xml"))
}

tasks.compileJava {
    // https://github.com/gradle/gradle/issues/26006
    // consistently saves 6+ minutes in CI. we do not need incremental compilation for 2 java files
    options.isIncremental = false
}

tasks.jar {
    dependsOn(changelog)
    from(changelog) {
        into("META-INF")
    }
}

val gatewayPluginXml = tasks.create<org.jetbrains.intellij.tasks.PatchPluginXmlTask>("patchPluginXmlForGateway") {
    pluginXmlFiles.set(tasks.patchPluginXml.map { it.pluginXmlFiles }.get())
    destinationDir.set(project.buildDir.resolve("patchedPluginXmlFilesGW"))

    val buildSuffix = if (!project.isCi()) "+${buildMetadata()}" else ""
    version.set("GW-$toolkitVersion-${ideProfile.shortName}$buildSuffix")

    // jetbrains expects gateway plugin to be dynamic
    doLast {
        pluginXmlFiles.get()
            .map(File::toPath)
            .forEach { p ->
                val path = destinationDir.get()
                    .asFile.toPath().toAbsolutePath()
                    .resolve(p.simpleName)

                val document = path.inputStream().use { inputStream ->
                    JDOMUtil.loadDocument(inputStream)
                }

                document.rootElement
                    .getAttribute("require-restart")
                    .setValue("false")

                transformXml(document, path)
            }
    }
}

val gatewayArtifacts by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    // share same dependencies as default configuration
    extendsFrom(configurations["implementation"], configurations["runtimeOnly"])
}

val jarNoPluginXmlArtifacts by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    // only consumed without transitive depen
}

val gatewayJar = tasks.create<Jar>("gatewayJar") {
    // META-INF/plugin.xml is a duplicate?
    // unclear why the exclude() statement didn't work
    duplicatesStrategy = DuplicatesStrategy.WARN

    dependsOn(tasks.instrumentedJar)

    archiveBaseName.set("aws-toolkit-jetbrains-IC-GW")
    from(tasks.instrumentedJar.get().outputs.files.map { zipTree(it) }) {
        exclude("**/plugin.xml")
        exclude("**/plugin-intellij.xml")
        exclude("**/inactive")
    }

    from(gatewayPluginXml) {
        into("META-INF")
    }

    val pluginGateway = sourceSets.main.get().resources.first { it.name == "plugin-gateway.xml" }
    from(pluginGateway) {
        into("META-INF")
    }
}

val jarNoPluginXml = tasks.create<Jar>("jarNoPluginXml") {
    duplicatesStrategy = DuplicatesStrategy.WARN

    dependsOn(tasks.instrumentedJar)

    archiveBaseName.set("aws-toolkit-jetbrains-IC-noPluginXml")
    from(tasks.instrumentedJar.get().outputs.files.map { zipTree(it) }) {
        exclude("**/plugin.xml")
        exclude("**/plugin-intellij.xml")
        exclude("**/inactive")
    }
}

artifacts {
    add("gatewayArtifacts", gatewayJar)
    add("jarNoPluginXmlArtifacts", jarNoPluginXml)
}

tasks.prepareSandbox {
    // you probably do not want to modify this.
    // this affects the IDE sandbox / build for `:jetbrains-core`, but will not propogate to the build generated by `:intellij`
    // (which is what is ultimately published to the marketplace)
    // without additional effort
}

tasks.testJar {
    // classpath.index is a duplicate
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

tasks.processTestResources {
    // TODO how can we remove this. Fails due to:
    // "customerUploadedEventSchemaMultipleTypes.json.txt is a duplicate but no duplicate handling strategy has been set"
    duplicatesStrategy = DuplicatesStrategy.INCLUDE

    // delete when fully split
    // pull in shim to make tests pass
    from(project(":plugin-toolkit:intellij").file("src/main/resources"))
}

dependencies {
    listOf(
        libs.aws.apprunner,
        libs.aws.cloudcontrol,
        libs.aws.cloudformation,
        libs.aws.cloudwatchlogs,
        libs.aws.codecatalyst,
        libs.aws.dynamodb,
        libs.aws.ec2,
//        libs.aws.ecr,
//        libs.aws.ecs,
//        libs.aws.iam,
//        libs.aws.lambda,
        libs.aws.rds,
        libs.aws.redshift,
//        libs.aws.s3,
        libs.aws.schemas,
        libs.aws.secretsmanager,
        libs.aws.sns,
        libs.aws.sqs,
        libs.aws.services,
    ).forEach { api(it) { isTransitive = false } }

    listOf(
        libs.aws.apacheClient,
        libs.aws.nettyClient,
    ).forEach { compileOnlyApi(it) { isTransitive = false } }

    compileOnlyApi(project(":plugin-toolkit:core"))
    compileOnlyApi(project(":plugin-core:jetbrains-community"))

    // TODO: remove Q dependency when split is fully done
    implementation(project(":plugin-amazonq:mynah-ui"))
    implementation(libs.bundles.jackson)
    implementation(libs.zjsonpatch)
    // CodeWhispererTelemetryService uses a CircularFifoQueue, transitive from zjsonpatch
    implementation(libs.commons.collections)

    testImplementation(testFixtures(project(":plugin-core:jetbrains-community")))
    // slf4j is v1.7.36 for <233
    // in <233, the classpass binding functionality picks up the wrong impl of StaticLoggerBinder (from the maven plugin instead of IDE platform) and causes a NoClassDefFoundError
    // instead of trying to fix the classpath, since it's built by gradle-intellij-plugin, shove slf4j >= 2.0.9 onto the test classpath, which uses a ServiceLoader and call it done
    testImplementation(libs.slf4j.api)
    testRuntimeOnly(libs.slf4j.jdk14)

    // delete when fully split
    testRuntimeOnly(project(":plugin-core:jetbrains-community"))
    testRuntimeOnly(project(":plugin-amazonq", "moduleOnlyJars"))
}
