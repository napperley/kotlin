/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("UNUSED_VARIABLE")

package org.jetbrains.kotlin.descriptors.commonizer.metadata.utils

import kotlinx.metadata.*
import kotlinx.metadata.klib.KlibModuleMetadata
import kotlinx.metadata.klib.fqName
import org.jetbrains.kotlin.descriptors.commonizer.metadata.utils.MetadataDeclarationsComparator.Result
import org.jetbrains.kotlin.descriptors.commonizer.metadata.utils.MetadataDeclarationsComparator.Mismatch
import org.jetbrains.kotlin.library.ToolingSingleFileKlibResolveStrategy
import org.jetbrains.kotlin.library.resolveSingleFileKlib
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.jetbrains.kotlin.konan.file.File as KFile

@Suppress("SpellCheckingInspection")
fun main() {
    val baseDir = File("/Users/Dmitriy.Dolovov/temp/commonizer-output.008-metadatax-BETTEREXP")
    val klibPaths = baseDir.listKlibs

    for (klibPath in klibPaths) {
        doCompare(klibPath, baseDir,false)
    }
}

private val File.listKlibs: Set<File>
    get() = walkTopDown()
        .filter { it.isDirectory && (it.name == "common" || it.parentFile.name == "platform") }
        .map { it.relativeTo(this) }
        .toSet()

private val KmType.signature: String
    get() = buildString { buildSignature("type", this, 0) }

private fun KmType.buildSignature(header: String, builder: StringBuilder, indent: Int) {
    repeat(indent) { builder.append("  ") }
    builder.append("- ").append(header).append(": ")
    when (val classifier = classifier) {
        is KmClassifier.Class -> builder.append("[class] ").append(classifier.name)
        is KmClassifier.TypeAlias -> builder.append("[type-alias] ").append(classifier.name)
        is KmClassifier.TypeParameter -> builder.append("[type-param] ").append(classifier.id)
    }
    if (Flag.Type.IS_NULLABLE(flags)) builder.append('?')
    builder.append('\n')

    if (arguments.isNotEmpty()) {
        repeat(indent) { builder.append("  ") }
        builder.append("  ").append("- arguments: ").append(arguments.size).append('\n')
        arguments.forEach { argument ->
            argument.type!!.buildSignature("type", builder, indent + 2)
        }
    }

    abbreviatedType?.buildSignature("abbreviated", builder, indent + 1)
}

private fun doCompare(klibPath: File, baseDir: File, printMatches: Boolean) {
    println("BEGIN $klibPath")

    val newLibs: Map<String, KFile> = baseDir.resolve(klibPath).listFiles().orEmpty()
        .filter { it.name.endsWith("-NEW") }
        .groupBy { it.name.substringBefore("-NEW") }
        .mapValues { KFile(it.value.single().absolutePath) }

    val oldLibs: Map<String, KFile> = baseDir.resolve(klibPath).listFiles().orEmpty().toSet()
        .filter { it.name in newLibs }
        .groupBy { it.name }
        .mapValues { KFile(it.value.single().absolutePath) }

    val allLibs = newLibs.keys intersect oldLibs.keys
    check(newLibs.keys == allLibs)
    check(oldLibs.keys == allLibs)

    for (lib in allLibs.sorted().filter { "posix" in it }) {
        val newLib = newLibs.getValue(lib)
        val oldLib = oldLibs.getValue(lib)

        val newKlib = resolveSingleFileKlib(newLib, strategy = ToolingSingleFileKlibResolveStrategy)
        val oldKlib = resolveSingleFileKlib(oldLib, strategy = ToolingSingleFileKlibResolveStrategy)

        val newMetadata = KlibModuleMetadata.read(KotlinMetadataLibraryProvider(newKlib))
        val oldMetadata = KlibModuleMetadata.read(KotlinMetadataLibraryProvider(oldKlib))

        when (val result = MetadataDeclarationsComparator().compare(newMetadata, oldMetadata)) {
            Result.Success -> if (printMatches) println("- [full match] $lib")
            is Result.Failure -> {
                val newPosix: List<KmModuleFragment> = newMetadata.fragments.filter { it.fqName == "platform.posix" }
                val oldPosix: List<KmModuleFragment> = oldMetadata.fragments.filter { it.fqName == "platform.posix" }

                val newFunctions: List<KmFunction> = newPosix.flatMap { it.pkg?.functions.orEmpty() }
                val newFunctionsMap: Map<String, KmFunction> = newFunctions.associateBy { it.name }
                val oldFunctions: List<KmFunction> = oldPosix.flatMap { it.pkg?.functions.orEmpty() }
                val oldFunctionsMap: Map<String, KmFunction> = oldFunctions.associateBy { it.name }

                val newProperties: List<KmProperty> = newPosix.flatMap { it.pkg?.properties.orEmpty() }
                val newPropertiesMap: Map<String, KmProperty> = newProperties.associateBy { it.name }
                val oldProperties: List<KmProperty> = oldPosix.flatMap { it.pkg?.properties.orEmpty() }
                val oldPropertiesMap: Map<String, KmProperty> = oldProperties.associateBy { it.name }

                val newClasses: List<KmClass> = newPosix.flatMap { it.classes }
                val newClassesMap: Map<String, KmClass> = newClasses.associateBy { it.name }
                val oldClasses: List<KmClass> = oldPosix.flatMap { it.classes }
                val oldClassesMap: Map<String, KmClass> = oldClasses.associateBy { it.name }

                val newTypeAliases: List<KmTypeAlias> = newPosix.flatMap { it.pkg?.typeAliases.orEmpty() }
                val newTypeAliasesMap: Map<String, KmTypeAlias> = newTypeAliases.associateBy { it.name }
                val oldTypeAliases: List<KmTypeAlias> = oldPosix.flatMap { it.pkg?.typeAliases.orEmpty() }
                val oldTypeAliasesMap: Map<String, KmTypeAlias> = oldTypeAliases.associateBy { it.name }

                var mismatches = result.mismatches

                // filter out known and acceptable mismatches
                mismatches = mismatches.filter { mismatch ->
                    when (mismatch) {
                        is Mismatch.MissingEntity -> {
                            if (mismatch.kind == "AbbreviatedType") {
                                val relPath = mismatch.relPath
                                if (relPath == "TypeAlias -> ExpandedType") {
                                    if (mismatch.missingInB) { // == missing in old
                                        return@filter false
                                    }
                                } else if (relPath.endsWith("TypeProjection -> Type")) {
                                    if (mismatch.missingInB) { // == missing in old
                                        when (val relPathFirstItem = relPath.substringBefore(' ')) {
                                            "Property", "SetterValueParameter", "Function", "ValueParameter" -> return@filter false
                                            else -> Unit
                                        }
                                    }
                                }
                            }
                        }
                        is Mismatch.DifferentValues -> {
                            if (mismatch.kind == "Classifier") {
                                val klibPathString = klibPath.path
                                if (klibPathString.startsWith("ios_arm32-ios_arm64-ios_x64/platform/")) {
                                    val platformName = klibPathString.substringAfter("ios_arm32-ios_arm64-ios_x64/platform/")
                                    val possiblePlatformNames = setOf("ios_arm32", "ios_arm64", "ios_x64")
                                    if (platformName in possiblePlatformNames) {
                                        val is32Bit = platformName == "ios_arm32"

                                        val newClassName = (mismatch.valueA as KmClassifier.Class).name
                                        val possibleNewClassNames = if (is32Bit)
                                            setOf("kotlin/Int", "kotlin/UInt")
                                        else
                                            setOf("kotlin/Long", "kotlin/ULong")

                                        val oldClassName = (mismatch.valueB as KmClassifier.Class).name
                                        val possibleOldClassNames = setOf(
                                            "platform/posix/__darwin_intptr_t",
                                            "platform/posix/__darwin_ssize_t",
                                            "platform/posix/__darwin_time_t",
                                            "platform/posix/__darwin_clock_t",
                                            "platform/posix/__darwin_pthread_key_t",
                                            "platform/posix/__darwin_size_t"
                                        )

                                        if (newClassName in possibleNewClassNames && oldClassName in possibleOldClassNames) {
                                            return@filter false
                                        }
                                    }
                                }
                            }
                        }
                    }

                    true
                }

                println("- [MISMATCHES: ${mismatches.size}] $lib")

                mismatches.filterIsInstance<Mismatch.MissingEntity>()
                    .groupBy { it.kind }
                    .entries
                    .forEach { (kind, missingEntities: List<Mismatch.MissingEntity>) ->
                        println("  - Missing $kind: ${missingEntities.size}")
                        when (kind) {
                            "AbbreviatedType" -> {
                                val groupedMissingAbbreviatedTypes = mutableMapOf<String, AtomicInteger>()
                                missingEntities.forEach {
                                    val relPath = it.relPath
                                    val typeAliasName = ((it.existentValue as KmType).classifier as KmClassifier.TypeAlias).name
                                    val where = if (it.missingInB) "O: " else "N: "
                                    val key = "$where$typeAliasName at $relPath"

                                    if (it.missingInB && typeAliasName == "platform/posix/__uint8_tVar")
                                        print("")

                                    groupedMissingAbbreviatedTypes.getOrPut(key) { AtomicInteger(0) }.incrementAndGet()
                                }
                                groupedMissingAbbreviatedTypes.entries.sortedBy { it.key }.forEach { (key, counter) ->
                                    println("    - $key: $counter")
                                }
                            }
                            else -> error("Unexpected MissingEntity kind: $kind")
                        }
                    }

                mismatches.filterIsInstance<Mismatch.DifferentValues>()
                    .groupBy { it.kind }
                    .entries
                    .forEach { (kind, differentValues: List<Mismatch.DifferentValues>) ->
                        println("  - Different $kind: ${differentValues.size}")
                        when (kind) {
                            "Classifier" -> {
                                val groupedClassifierMismatches = mutableMapOf<String, AtomicInteger>()
                                differentValues.forEach {
                                    val relPath = it.relPath
                                    val key = "N{${(it.valueA as KmClassifier.Class).name}} vs O{${(it.valueB as KmClassifier.Class).name}} at $relPath"
                                    groupedClassifierMismatches.getOrPut(key) { AtomicInteger(0) }.incrementAndGet()
                                }
                                groupedClassifierMismatches.entries.sortedBy { it.key }.forEach { (key, counter) ->
                                    println("    - $key: $counter")
                                }
                            }
                            else -> error("Unexpected DifferentValues kind: $kind")
                        }
                    }

//                val newClass = newClassesMap["platform/posix/regex_t"]
//                val oldClass = oldClassesMap["platform/posix/regex_t"]

//                listOf("SEM_FAILED", "tzname").forEach { propertyName ->
//                    val oldProp = oldPropertiesMap[propertyName]?.returnType?.signature
//                    val newProp = newPropertiesMap[propertyName]?.returnType?.signature
//
//                    if (oldProp != null && newProp != null) {
//                        println("=== Property $propertyName ===")
//                        if (oldProp == newProp) {
//                            println("[equal]")
//                            println(oldProp)
//                        } else {
//                            println("[old]")
//                            println(oldProp)
//                            println("[new]")
//                            println(newProp)
//                        }
//                    }
//                }

//                println("- Grouped MissingEntity-AbbreviatedType issues:")
//                result.mismatches
//                    .asSequence()
//                    .filterIsInstance<Mismatch.MissingEntity>()
//                    .filter { it.kind == "AbbreviatedType" }
//                    .groupingBy { mismatch ->
//                        var path = mismatch.path.filter { it != "<root>" && !it.startsWith("Module") && !it.startsWith("Package") }
//                            .map { it.substringBefore(' ') }
//                            .map { if (it == "TypeProjection") "Arg" else it }
//
//                        path = run {
//                            val temp = mutableListOf<String>()
//                            var index = 0
//                            while (index < path.size) {
//                                val curr = path[index]
//                                if (curr == "Arg" && index < path.size - 1) {
//                                    val next = path[index + 1]
//                                    if (next == "Type") {
//                                        temp += "Arg/Type"
//                                        index += 2
//                                        continue
//                                    }
//                                }
//                                temp += curr
//                                index++
//                            }
//                            temp
//                        }
//
//                        path = run {
//                            val temp = mutableListOf<String>()
//                            var index = 0
//                            while (index < path.size) {
//                                val curr = path[index]
//                                val usages = when (val indexOfFirstDifferent = path.subList(index, path.size).indexOfFirst { it != curr }) {
//                                    -1 -> path.size - index
//                                    0 -> 1
//                                    else -> indexOfFirstDifferent
//                                }
//                                index += usages
//                                temp += when {
//                                    usages > 1 -> "$curr[$usages]"
//                                    curr == "Arg/Type" -> "$curr[1]"
//                                    else -> curr
//                                }
//                            }
//                            temp
//                        }
//
//                        path.joinToString(" -> ")
//
////                        val type = it.path.last().substringBefore(' ')
////                        val path = it.path
////                            .filter { " " in it && !it.startsWith("Module") && !it.startsWith("Package") }
////                            .map { if (it.startsWith("Class")) "Class " + it.substringAfterLast('/') else it }
////                            .joinToString(" -> ")
////
////                        "$type ($path): ${it.name}[${it.valueB} -> ${it.valueA}]"
//                    }
//                    .eachCount()
//                    .entries.sortedBy { it.key }
//                    .forEach { (key, value) ->
//                        println("\t$key: $value")
//                    }

//                println("- Grouped unique top-level SetterFlag issues:")
//                println("\t" + result.mismatches
//                    .filterIsInstance<Mismatch.DifferentValues>()
//                    .filter { it.kind == "SetterFlag" }
//                    .filter { it.path[it.path.lastIndex - 1].substringBefore(' ') == "Package" }
//                    .groupingBy { it.path[it.path.lastIndex - 1] + ":" + it.path[it.path.lastIndex] }
//                    .eachCount()
//                    .size + " <> " + newProperties.size
//                )

//                val props = result.mismatches
//                    .asSequence()
//                    .filterIsInstance<Mismatch.DifferentValues>()
//                    .filter { it.kind == "SetterFlag" }
//                    .filter { it.path[it.path.lastIndex - 1].substringBefore(' ') == "Package" }
//                    .map { it.path.last().substringAfter(' ') }
//                    .filter { !it.startsWith('_') }
//                    .filter { it[0].isLowerCase() }
//                    .sorted()
//                    .take(10)
//                    .toList()
//                println("- First 10 top-level properties: $props")

//
//                groupedByPath.values.flatten().forEach { mismatch ->
//                    if (mismatch !is Mismatch.DifferentValues) return@forEach
//                    if (mismatch.kind != "Classifier" || mismatch.path.last() != "UnderlyingType") return@forEach
//                    if ((mismatch.valueB as? KmClassifier.Class)?.name !in arrayOf(
//                            "kotlinx/cinterop/CPointer",
//                            "kotlin/Function0",
//                            "kotlin/Function1",
//                            "kotlin/Function2",
//                            "kotlin/Function3"
//                        )
//                    ) return@forEach
//
//                    var fullTypeAliasName = (mismatch.valueA as? KmClassifier.TypeAlias)?.name ?: return@forEach
//
//                    while (true) {
//                        val typeAlias = resolveTypeAlias(fullTypeAliasName)
//
//                        when (val underlyingTypeClassifier = typeAlias.underlyingType.classifier) {
//                            is KmClassifier.Class -> {
//                                if (underlyingTypeClassifier.name !in arrayOf(
//                                        "kotlinx/cinterop/CPointer",
//                                        "kotlin/Function0",
//                                        "kotlin/Function1",
//                                        "kotlin/Function2",
//                                        "kotlin/Function3"
//                                    )
//                                ) {
//                                    error("Unexpected class found: ${underlyingTypeClassifier.name}")
//                                }
//                            }
//                            is KmClassifier.TypeAlias -> {
//                                if (underlyingTypeClassifier.name !in arrayOf(
//                                        "kotlinx/cinterop/CArrayPointer",
//                                        "kotlinx/cinterop/COpaquePointer"
//                                    )
//                                ) {
//                                    fullTypeAliasName = underlyingTypeClassifier.name
//                                    continue
//                                }
//                            }
//                        }
//
//                        val relevantMismatches = groupedByPath.getValue(mismatch.path)
//                        relevantMismatches -= mismatch
//                        relevantMismatches.removeIf { it is Mismatch.MissingEntity && it.kind == "TypeProjection" }
//                        break
//                    }
//                }
//
//                groupedByPath.values.flatten().forEach { mismatch ->
//                    if (mismatch !is Mismatch.DifferentValues) return@forEach
//                    if (mismatch.kind != "Flag" || mismatch.name != "IS_NULLABLE") return@forEach
//                    if (mismatch.path.last() !in arrayOf("UnderlyingType", "ExpandedType")) return@forEach
//                    if (mismatch.valueA.toString() != "false" || mismatch.valueB.toString() != "true") return
//
//                    groupedByPath.getValue(mismatch.path) -= mismatch
//                }
//
//                if ("linux_x64" in klibPath.path) {
//                    groupedByPath.values.flatten().forEach { mismatch ->
//                        if (mismatch !is Mismatch.MissingEntity) return@forEach
//                        if (mismatch.kind != "TypeAlias") return@forEach
//                        val options = listOf("caddr_t", "sig_t", "va_list")
//                        if (mismatch.name !in options && mismatch.name.removeSuffix("Var") !in options) return@forEach
//
//                        groupedByPath.getValue(mismatch.path) -= mismatch
//                    }
//
//                    groupedByPath.values.flatten().forEach { mismatch ->
//                        if (mismatch !is Mismatch.MissingEntity) return@forEach
//                        if (mismatch.kind != "Function") return@forEach
//                        val function = mismatch.presentValue as? KmFunction ?: return@forEach
//                        if (function.valueParameters.any {
//                                (it.type?.classifier as? KmClassifier.Class)?.name == "kotlinx/cinterop/CPointer"
//                                        && (it.type?.arguments?.singleOrNull()?.type?.classifier as? KmClassifier.Class)?.name?.endsWith("va_list_tag") == true
//                            }) {
//                            groupedByPath.getValue(mismatch.path) -= mismatch
//                        }
//                    }
//                }
//
//                groupedByPath.entries.removeIf { (_, values) -> values.isEmpty() }
//
//                if (groupedByPath.isNotEmpty()) {
//                    println("- [MISMATCHES] $lib")
//                    groupedByPath.values.flatten().forEachIndexed { index, mismatch ->
//                        println("  ${index + 1}. $mismatch")
//                    }
//                } else if (printMatches) println("- [match] $lib")
            }
        }
    }
    println("END $klibPath\n")

    return
}

private val Mismatch.relPath: String
    get() {
        val relPath = mutableListOf<String>()
        with(path.asReversed().iterator()) {
            do {
                relPath += next().clean()
            } while (relPath.last() == "TypeProjection" || relPath.last().endsWith("Type"))
        }

        return relPath.reversed().joinToString(" -> ")
    }

private fun String.clean(): String =
    when (val entityName = substringBefore(' ')) {
        "Property", "Function", "TypeProjection", "TypeAlias", "ValueParameter" -> entityName
        else -> this
    }
