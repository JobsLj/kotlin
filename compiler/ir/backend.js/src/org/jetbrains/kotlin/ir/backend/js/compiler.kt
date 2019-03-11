/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js

import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.backend.common.LoggingContext
import org.jetbrains.kotlin.backend.common.phaser.invokeToplevel
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.builtins.functions.functionInterfacePackageFragmentProvider
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.descriptors.impl.CompositePackageFragmentProvider
import org.jetbrains.kotlin.descriptors.impl.ModuleDescriptorImpl
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.ir.backend.js.lower.serialization.ir.*
import org.jetbrains.kotlin.ir.backend.js.lower.serialization.metadata.JsKlibMetadataModuleDescriptor
import org.jetbrains.kotlin.ir.backend.js.lower.serialization.metadata.JsKlibMetadataSerializationUtil
import org.jetbrains.kotlin.ir.backend.js.lower.serialization.metadata.JsKlibMetadataVersion
import org.jetbrains.kotlin.ir.backend.js.lower.serialization.metadata.createJsKlibMetadataPackageFragmentProvider
import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.IrModuleToJsTransformer
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.util.ExternalDependenciesGenerator
import org.jetbrains.kotlin.ir.util.SymbolTable
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.js.analyze.TopDownAnalyzerFacadeForJS
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.progress.ProgressIndicatorAndCompilationCanceledStatus
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi2ir.Psi2IrTranslator
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.CompilerDeserializationConfiguration
import org.jetbrains.kotlin.storage.LockBasedStorageManager
import org.jetbrains.kotlin.utils.DFS
import java.io.File

sealed class TranslationResult {
    class CompiledJsCode(val jsCode: String) : TranslationResult()

    object CompiledKlib : TranslationResult()
}

data class CompiledModule(
    val moduleName: String,
    val isBuiltIn: Boolean,
    val klibPath: String,
    val dependencies: List<CompiledModule>
)

enum class CompilationMode {
    KLIB,
    JS
}

private val moduleHeaderFileName = "module.kji"
private val declarationsDirName = "ir/"
private val debugDataFileName = "debug.txt"
private val logggg = object : LoggingContext {
    override var inVerbosePhase: Boolean
        get() = TODO("not implemented")
        set(_) {}

    override fun log(message: () -> String) {}
}

private fun metadataFileName(moduleName: String) = "$moduleName.${JsKlibMetadataSerializationUtil.CLASS_METADATA_FILE_EXTENSION}"

private val CompilerConfiguration.metadataVersion
    get() = get(CommonConfigurationKeys.METADATA_VERSION) as? JsKlibMetadataVersion ?: JsKlibMetadataVersion.INSTANCE

fun compile(
    project: Project,
    files: List<KtFile>,
    configuration: CompilerConfiguration,
    compileMode: CompilationMode,
    dependencies: List<CompiledModule> = emptyList(),
    outputKlibPath: String
): TranslationResult {
    val sortedDeps: List<CompiledModule> = DFS.topologicalOrder(dependencies) { it.dependencies }.reversed()
    val builtInsDeps = sortedDeps.filter { it.isBuiltIn }
    assert(builtInsDeps.size == 1)

    val depsDescriptors = DependencyDescriptors(
        LookupTracker.DO_NOTHING,
        configuration.metadataVersion,
        configuration.languageVersionSettings
    )

    val builtInsDep = dependencies.firstOrNull()
    val builtInModule = if (builtInsDep != null) depsDescriptors[builtInsDep] else null // null in case compiling builtInModule itself
    assert(builtInsDep?.isBuiltIn ?: true)

    val analysisResult =
        TopDownAnalyzerFacadeForJS.analyzeFiles(
            files,
            project,
            configuration,
            sortedDeps.map { depsDescriptors[it] },
            friendModuleDescriptors = emptyList(),
            thisIsBuiltInsModule = builtInModule == null,
            customBuiltInsModule = builtInModule
        )

    ProgressIndicatorAndCompilationCanceledStatus.checkCanceled()
    TopDownAnalyzerFacadeForJS.checkForErrors(files, analysisResult.bindingContext)

    val moduleDescriptor = analysisResult.moduleDescriptor as ModuleDescriptorImpl
    val symbolTable = SymbolTable()

    val psi2IrTranslator = Psi2IrTranslator(configuration.languageVersionSettings)
    val psi2IrContext = psi2IrTranslator.createGeneratorContext(moduleDescriptor, analysisResult.bindingContext, symbolTable)
    val irBuiltIns = psi2IrContext.irBuiltIns

    val deserializer = IrKlibProtoBufModuleDeserializer(moduleDescriptor, logggg, irBuiltIns, symbolTable, null)

    val deserializedModuleFragments = sortedDeps.map {
        val moduleFile = File(it.klibPath, moduleHeaderFileName)
        deserializer.deserializeIrModule(depsDescriptors[it], moduleFile.readBytes(), File(it.klibPath), false)
    }

    val moduleFragment = psi2IrTranslator.generateModuleFragment(psi2IrContext, files, deserializer)

    if (compileMode == CompilationMode.KLIB) {
        deserializedModuleFragments.forEach {
            ExternalDependenciesGenerator(it.descriptor, symbolTable, irBuiltIns).generateUnboundSymbolsAsDependencies()
        }
        deserializedModuleFragments.forEach { it.patchDeclarationParents() }
        val moduleName = configuration.get(CommonConfigurationKeys.MODULE_NAME) as String
        serializeModuleIntoKlib(
            moduleName,
            configuration.metadataVersion,
            configuration.languageVersionSettings,
            psi2IrContext.symbolTable,
            psi2IrContext.bindingContext,
            outputKlibPath,
            dependencies,
            moduleFragment
        )

        return TranslationResult.CompiledKlib
    }

    val context = JsIrBackendContext(moduleDescriptor, irBuiltIns, symbolTable, moduleFragment, configuration, compileMode)

    deserializedModuleFragments.forEach {
        ExternalDependenciesGenerator(
            it.descriptor,
            symbolTable,
            irBuiltIns,
            deserializer = deserializer
        ).generateUnboundSymbolsAsDependencies()
    }

    // TODO: check the order
    val irFiles = deserializedModuleFragments.flatMap { it.files } + moduleFragment.files

    moduleFragment.files.clear()
    moduleFragment.files += irFiles

    ExternalDependenciesGenerator(
        moduleDescriptor = context.module,
        symbolTable = context.symbolTable,
        irBuiltIns = context.irBuiltIns
    ).generateUnboundSymbolsAsDependencies()
    moduleFragment.patchDeclarationParents()

    jsPhases.invokeToplevel(context.phaseConfig, context, moduleFragment)

    val jsProgram = moduleFragment.accept(IrModuleToJsTransformer(context), null)
    return TranslationResult.CompiledJsCode(jsProgram.toString())
}

private fun loadKlibMetadata(
    moduleName: String,
    klibPath: String,
    isBuiltIn: Boolean,
    lookupTracker: LookupTracker,
    storageManager: LockBasedStorageManager,
    metadataVersion: JsKlibMetadataVersion,
    languageVersionSettings: LanguageVersionSettings,
    builtinsModule: ModuleDescriptorImpl?,
    dependencies: List<ModuleDescriptorImpl>
): ModuleDescriptorImpl {
    assert(isBuiltIn == (builtinsModule === null))

    val metadataFile = File(klibPath, metadataFileName(moduleName))

    val serializer = JsKlibMetadataSerializationUtil
    val parts = serializer.readModuleAsProto(metadataFile.readBytes())
    val builtIns = builtinsModule?.builtIns ?: object : KotlinBuiltIns(storageManager) {}
    val md = ModuleDescriptorImpl(Name.special("<$moduleName>"), storageManager, builtIns)
    if (isBuiltIn) builtIns.builtInsModule = md
    val currentModuleFragmentProvider = createJsKlibMetadataPackageFragmentProvider(
        storageManager, md, parts.header, parts.body, metadataVersion,
        CompilerDeserializationConfiguration(languageVersionSettings),
        lookupTracker
    )

    val packageFragmentProvider = if (isBuiltIn) {
        val functionFragmentProvider = functionInterfacePackageFragmentProvider(storageManager, md)
        CompositePackageFragmentProvider(listOf(functionFragmentProvider, currentModuleFragmentProvider))
    } else currentModuleFragmentProvider

    md.initialize(packageFragmentProvider)
    md.setDependencies(listOf(md) + dependencies)

    return md
}


class DependencyDescriptors(
    private val lookupTracker: LookupTracker,
    private val metadataVersion: JsKlibMetadataVersion,
    private val languageVersionSettings: LanguageVersionSettings
) {
    private val storageManager: LockBasedStorageManager = LockBasedStorageManager("DependencyDescriptors")
    private var runtimeModule: ModuleDescriptorImpl? = null

    private val descriptors = mutableMapOf<CompiledModule, ModuleDescriptorImpl>()

    operator fun get(current: CompiledModule): ModuleDescriptorImpl = descriptors.getOrPut(current) {
        loadKlibMetadata(
            current.moduleName,
            current.klibPath,
            current.isBuiltIn,
            lookupTracker,
            storageManager,
            metadataVersion,
            languageVersionSettings,
            runtimeModule,
            current.dependencies.map { get(it) }
        ).also {
            if (current.isBuiltIn) runtimeModule = it
        }
    }
}

fun serializeModuleIntoKlib(
    moduleName: String,
    metadataVersion: JsKlibMetadataVersion,
    languageVersionSettings: LanguageVersionSettings,
    symbolTable: SymbolTable,
    bindingContext: BindingContext,
    klibPath: String,
    dependencies: List<CompiledModule>,
    moduleFragment: IrModuleFragment
) {
    val declarationTable = DeclarationTable(moduleFragment.irBuiltins, DescriptorTable(), symbolTable)

    val serializedIr = IrModuleSerializer(logggg, declarationTable/*, onlyForInlines = false*/).serializedIrModule(moduleFragment)
    val serializer = JsKlibMetadataSerializationUtil

    val moduleDescription =
        JsKlibMetadataModuleDescriptor(moduleName, dependencies.map { it.moduleName }, moduleFragment.descriptor)
    val serializedData = serializer.serializeMetadata(
        bindingContext,
        moduleDescription,
        languageVersionSettings,
        metadataVersion
    ) { declarationDescriptor ->
        val index = declarationTable.descriptorTable.get(declarationDescriptor)
        index?.let { newDescriptorUniqId(it) }
    }

    val klibDir = File(klibPath).also {
        it.deleteRecursively()
        it.mkdirs()
    }

    val moduleFile = File(klibDir, moduleHeaderFileName)
    moduleFile.writeBytes(serializedIr.module)

    val irDeclarationDir = File(klibDir, declarationsDirName).also { it.mkdir() }

    for ((id, data) in serializedIr.declarations) {
        val file = File(irDeclarationDir, id.declarationFileName)
        file.writeBytes(data)
    }

    val debugFile = File(klibDir, debugDataFileName)

    for ((id, data) in serializedIr.debugIndex) {
        debugFile.appendText(id.toString())
        debugFile.appendText(" --- ")
        debugFile.appendText(data)
        debugFile.appendText("\n")
    }

    File(klibDir, "${moduleDescription.name}.${JsKlibMetadataSerializationUtil.CLASS_METADATA_FILE_EXTENSION}").also {
        it.writeBytes(serializedData.asByteArray())
    }
}