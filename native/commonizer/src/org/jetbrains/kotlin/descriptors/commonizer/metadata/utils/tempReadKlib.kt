/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("UNUSED_VARIABLE", "DuplicatedCode", "SameParameterValue", "UnnecessaryVariable")

package org.jetbrains.kotlin.descriptors.commonizer.metadata.utils

import kotlinx.metadata.*
import kotlinx.metadata.klib.fqName
import org.jetbrains.kotlin.descriptors.commonizer.metadata.utils.LibraryInfo.Companion.findLibs
import java.io.File
import java.util.*

data class LibraryInfo(
    val location: File,
    val name: String,
    val fullName: String,
    val mainPackage: String?
) {
    companion object {
        fun findLibs(baseDir: File): List<LibraryInfo> =
            baseDir.listFiles().orEmpty().mapNotNull { location ->
                val manifest = location.resolve("default/manifest")
                if (!manifest.isFile) return@mapNotNull null

                val name = location.name.let { name ->
                    val items = name.split('.')
                    if (items.size > 1)
                        items.subList(0, items.size - 1).map { item -> item[0] }.joinToString(".", postfix = ".") + items.last()
                    else
                        name
                }

                val fullName = location.parentFile.let { parent ->
                    val items = parent.toPath().toAbsolutePath().map { it.fileName.toString() }.reversed().iterator()
                    var platform: String? = null
                    var commonizedPlatforms: Set<String> = emptySet()

                    if (items.hasNext()) {
                        when (val item1 = items.next()) {
                            "common" -> platform = "common"
                            else -> if (items.hasNext()) {
                                when (items.next()) {
                                    "platform" -> platform = item1
                                }
                            }
                        }

                        if (platform != null && items.hasNext()) {
                            commonizedPlatforms = items.next().split('-')
                                .takeIf { platforms -> platforms.all { '_' in it } }
                                ?.toSet()
                                .orEmpty()
                        }
                    }

                    buildString {
                        if (commonizedPlatforms.isNotEmpty()) {
                            append("[")
                            commonizedPlatforms.sorted().joinTo(this)
                            append("] -> ")
                        }

                        platform?.let {
                            append("[")
                            append(platform)
                            append("] -> ")
                        }

                        append(name)
                    }
                }

                val mainPackage = manifest.inputStream().use { Properties().apply { load(it) } }["package"]?.toString()

                LibraryInfo(location, name, fullName, mainPackage)
            }.sortedBy { it.name }
    }
}

fun main() {
    val libs = run {
        val oldLibs: (LibraryInfo) -> Boolean = { !it.name.endsWith("-NEW") }
        val newLibs: (LibraryInfo) -> Boolean = { it.name.endsWith("-NEW") }

        val filter = newLibs

        val baseDir = File("/Users/Dmitriy.Dolovov/temp/commonizer-output.006-metadatax-BETTEREXP")
        baseDir.listFiles().orEmpty().filter { it.isDirectory }.map { dir ->
            findLibs(dir.resolve("common")) +
                    dir.resolve("platform").listFiles().orEmpty().filter { it.isDirectory }.map { findLibs(it) }.flatten()
        }.flatten().filter(filter)
    }

//    val libs = run {
//        val baseDir = File("/Users/Dmitriy.Dolovov/.konan/kotlin-native-prebuilt-macos-1.4.30-rc1-14/klib")
//        findLibs(baseDir.resolve("common"))//.filter { it.name.endsWith("stdlib") }
//    }

//    val libs = run {
//        val baseDir = File("/Users/Dmitriy.Dolovov/.konan/kotlin-native-prebuilt-macos-1.4.30-rc1-14/klib")
//        listOf(
//            findLibs(baseDir.resolve("common")),
//            findLibs(baseDir.resolve("platform/ios_x64"))
//        ).flatten()
//    }

//    val libs = listOf(
//        LibraryInfo(
//            File("/Users/Dmitriy.Dolovov/IdeaProjects/nativeLib/build/classes/kotlin/native/main/nativeLib.klib"),
//            "nativeLib",
//            "sample"
//        )
//    )

    dumpHeader()
    libs.forEachIndexed { index, lib ->
        val metadata = KotlinMetadataLibraryProvider.readLibraryMetadata(lib.location)
        val fragments = metadata.fragments.filter { lib.mainPackage == null || lib.mainPackage == it.fqName }

//        val classes = fragments.flatMap { it.classes }.associateBy { it.name }
//        val classesPlain = classes.values.sortedBy { it.name }
//
//        val typeAliases = fragments.flatMap { it.pkg?.typeAliases.orEmpty() }.associateBy { it.name }
//        val typeAliasesPlain = typeAliases.values.sortedBy { it.name }
//
//        val properties = fragments.flatMap { it.pkg?.properties.orEmpty() }.associateBy { it.name }
//        val propertiesPlain = properties.values.sortedBy { it.name }
//
//        val functions = fragments.flatMap { it.pkg?.functions.orEmpty() }.associateBy { it.name }
//        val functionsPlain = functions.values.sortedBy { it.name }

        dumpDetails(index, lib.fullName.removeSuffix("-NEW"), checkExpandedTypes(fragments), false)
    }
    println("== Done.")
}

data class Counter(
    var success: Int = 0,
    var failure: Int = 0
)

private fun checkExpandedTypes(fragments: Collection<KmModuleFragment>): Map<String, Counter> {
    val result = mutableMapOf<String, Counter>()

    fun checkNestedType(type: KmType?, where: List<String>) {
        type ?: return

        val key = where.joinToString(" > ")
        val counter = result.getOrPut(key) { Counter() }

        if (type.abbreviatedType == null)
            counter.success++
        else
            counter.failure++

        type.arguments.forEach { checkNestedType(it.type, where /*+ "ArgumentType"*/) }
    }

    fun checkTopLevelType(type: KmType?, where: List<String>) {
        type ?: return
        if (type.abbreviatedType == null) return

        type.arguments.forEach { checkNestedType(it.type, where + "ArgumentType") }
    }

    fun checkProperty(topLevel: Boolean, property: KmProperty) {
        val where = listOfNotNull(if (!topLevel) "Class" else null, "Property")

        checkTopLevelType(property.returnType, where + "ReturnType")
        checkTopLevelType(property.receiverParameterType, where + "ReceiverType")
        checkTopLevelType(property.setterParameter?.type, where + "SetterType")
        checkTopLevelType(property.setterParameter?.varargElementType, where + "SetterVarargType")
        property.typeParameters.forEach { typeParameter ->
            typeParameter.upperBounds.forEach { upperBound ->
                checkTopLevelType(upperBound, where + listOf("TypeParameter", "UpperBound"))
            }
        }
    }

    fun checkFunction(topLevel: Boolean, function: KmFunction) {
        val where = listOfNotNull(if (!topLevel) "Class" else null, "Function")

        checkTopLevelType(function.returnType, where + "ReturnType")
        checkTopLevelType(function.receiverParameterType, where + "ReceiverType")
        function.valueParameters.forEach { valueParameter ->
            checkTopLevelType(valueParameter.type, where + "ValueParameterType")
            checkTopLevelType(valueParameter.varargElementType, where + "ValueParameterVarargType")
        }
        function.typeParameters.forEach { typeParameter ->
            typeParameter.upperBounds.forEach { upperBound ->
                checkTopLevelType(upperBound, where + listOf("TypeParameter", "UpperBound"))
            }
        }
    }

    fragments.forEach { fragment ->
        fragment.pkg?.let { pkg ->
            pkg.properties.forEach { property -> checkProperty(true, property) }
            pkg.functions.forEach { function -> checkFunction(true, function) }
            pkg.typeAliases.forEach { typeAlias ->
                checkTopLevelType(typeAlias.underlyingType, listOf("TypeAlias", "UnderlyingType"))
                checkTopLevelType(typeAlias.expandedType, listOf("TypeAlias", "ExpandedType"))

                fun processArgument(argument: KmTypeProjection) {
                    val type = argument.type ?: return
                    if (type.abbreviatedType != null)
                        print("")
                    type.arguments.forEach(::processArgument)
                }

                typeAlias.underlyingType.arguments.forEach(::processArgument)
                typeAlias.expandedType.arguments.forEach(::processArgument)
            }
        }
        fragment.classes.forEach { clazz ->
            clazz.properties.forEach { property -> checkProperty(false, property) }
            clazz.functions.forEach { function -> checkFunction(false, function) }
            clazz.constructors.forEach { constructor ->
                constructor.valueParameters.forEach { valueParameter ->
                    checkTopLevelType(valueParameter.type, listOf("Class", "Constructor", "ValueParameterType"))
                    checkTopLevelType(valueParameter.varargElementType, listOf("Class", "Constructor", "ValueParameterVarargType"))
                }
            }
        }
    }

    return result
}

private fun dumpHeader() {
    val header = buildString {
        repeat(109) { append(' ') }
        append("SSS   FFF")
    }
    println(header)
}

private fun dumpDetails(index: Int, libraryName: String, result: Map<String, Counter>, full: Boolean) {
    val sum = with(result.values) {
        if (isEmpty())
            Counter()
        else
            reduce { acc, counter -> Counter(acc.success + counter.success, acc.failure + counter.failure) }
    }

    val header = buildString {
        append("== ")
        val number = (index + 1).toString()
        append(number.padStart(3, ' '))
        append(". ")
        append(libraryName)
        repeat(100 - length) { append(' ') }
        append("Total:")

        if (sum.success != 0 || sum.failure != 0) {
            append(sum.success.toString().padStart(6, ' '))
            append(sum.failure.toString().padStart(6, ' '))
        }
    }
    println(header)

    if (full) {
        result.keys.sorted().forEach { key ->
            val counter = result.getValue(key)
            println("\t- $key")
            println("\t\t${counter.success}\t${counter.failure}")
        }
    }
}

