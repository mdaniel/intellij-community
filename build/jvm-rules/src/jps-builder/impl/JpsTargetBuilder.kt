// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("ReplaceJavaStaticMethodWithKotlinAnalog", "UnstableApiUsage", "ReplaceGetOrSet", "HardCodedStringLiteral", "RemoveRedundantQualifierName")

package org.jetbrains.bazel.jvm.jps.impl

import com.intellij.openapi.util.text.Formats.formatDuration
import com.intellij.tracing.Tracer.start
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap
import it.unimi.dsi.fastutil.objects.ObjectArraySet
import kotlinx.coroutines.ensureActive
import org.jetbrains.bazel.jvm.jps.RequestLog
import org.jetbrains.bazel.jvm.jps.hashMap
import org.jetbrains.bazel.jvm.jps.linkedSet
import org.jetbrains.bazel.jvm.jps.state.LoadStateResult
import org.jetbrains.bazel.jvm.jps.state.RemovedFileInfo
import org.jetbrains.jps.ModuleChunk
import org.jetbrains.jps.builders.BuildRootDescriptor
import org.jetbrains.jps.builders.BuildTarget
import org.jetbrains.jps.builders.FileProcessor
import org.jetbrains.jps.builders.impl.DirtyFilesHolderBase
import org.jetbrains.jps.builders.java.JavaBuilderUtil
import org.jetbrains.jps.builders.java.JavaSourceRootDescriptor
import org.jetbrains.jps.builders.storage.BuildDataCorruptedException
import org.jetbrains.jps.incremental.*
import org.jetbrains.jps.incremental.fs.BuildFSState
import org.jetbrains.jps.incremental.fs.BuildFSState.CURRENT_ROUND_DELTA_KEY
import org.jetbrains.jps.incremental.fs.BuildFSState.NEXT_ROUND_DELTA_KEY
import org.jetbrains.jps.incremental.fs.CompilationRound
import org.jetbrains.jps.incremental.messages.*
import org.jetbrains.jps.incremental.storage.BuildTargetConfiguration
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlin.time.toJavaDuration

internal class JpsTargetBuilder(
  private val log: RequestLog,
  private val isCleanBuild: Boolean,
  private val dataManager: BazelBuildDataProvider,
) {
  private val builderToDuration = hashMap<Builder, AtomicLong>()
  private val numberOfSourcesProcessedByBuilder = hashMap<Builder, AtomicInteger>()

  suspend fun build(
    context: CompileContextImpl,
    moduleTarget: BazelModuleBuildTarget,
    builders: Array<ModuleLevelBuilder>,
    buildState: LoadStateResult?,
  ): Int {
    try {
      val buildSpan = start("JpsTargetBuilder.runBuild")
      context.setDone(0.0f)
      context.addBuildListener(ChainedTargetsBuildListener(context, dataManager))
      val allModuleLevelBuildersBuildStartedSpan = start("All ModuleLevelBuilder.buildStarted")
      for (builder in builders) {
        builder.buildStarted(context)
      }
      allModuleLevelBuildersBuildStartedSpan.complete()

      try {
        val span = start("build target")
        buildTarget(context = context, target = moduleTarget, builders = builders, buildState = buildState)
        span.complete()
      }
      finally {
        for (builder in builders) {
          builder.buildFinished(context)
        }
      }
      buildSpan.complete()

      for ((builder, time) in builderToDuration) {
        val processedSources = numberOfSourcesProcessedByBuilder.get(builder)?.get() ?: 0
        val time = time.get().toDuration(DurationUnit.NANOSECONDS)
        val message = "Build duration: ${builder.presentableName} took ${formatDuration(time.toJavaDuration())}; " +
          processedSources + " sources processed" +
          (if (processedSources == 0) "" else " (${time.inWholeMilliseconds / processedSources} ms per file)")
        log.info(message)
      }
    }
    catch (e: StopBuildException) {
      // some builder decided to stop the build - report optional progress message if any
      e.message?.takeIf { it.isNotEmpty() }?.let {
        log.processMessage(ProgressMessage(it))
      }
      return if (log.hasErrors()) 1 else 0
    }
    catch (e: BuildDataCorruptedException) {
      log.warn("Internal caches are corrupted or have outdated format, forcing project rebuild: $e")
      throw RebuildRequestedException(e)
    }
    catch (e: ProjectBuildException) {
      val cause = e.cause
      if (cause is IOException || cause is BuildDataCorruptedException || (cause is RuntimeException && cause.cause is IOException)) {
        log.warn("Internal caches are corrupted or have outdated format, forcing project rebuild: $e")
        throw RebuildRequestedException(cause)
      }
      else {
        // should stop the build with error
        throw e
      }
    }
    return 0
  }

  // return true if changed something, false otherwise
  private suspend fun runModuleLevelBuilders(
    context: CompileContext,
    target: BazelModuleBuildTarget,
    builders: Array<ModuleLevelBuilder>,
  ): Boolean {
    val chunk = ModuleChunk(java.util.Set.of<ModuleBuildTarget>(target))
    for (builder in builders) {
      builder.chunkBuildStarted(context, chunk)
    }

    if (!isCleanBuild) {
      completeRecompiledSourcesSet(context, target, dataManager)
    }

    var doneSomething = false
    val outputConsumer = ChunkBuildOutputConsumerImpl(context = context, target = target, dataManager = dataManager)
    try {
      val fsState = context.projectDescriptor.fsState
      val dirtyFilesHolder = object : DirtyFilesHolderBase<JavaSourceRootDescriptor, ModuleBuildTarget>(context) {
        override fun processDirtyFiles(processor: FileProcessor<JavaSourceRootDescriptor, ModuleBuildTarget>) {
          fsState.processFilesToRecompile(context, target, processor)
        }
      }

      var rebuildFromScratchRequested = false
      var nextPassRequired: Boolean
      do {
        nextPassRequired = false
        fsState.beforeNextRoundStart(context, chunk)

        if (!isCleanBuild) {
          cleanOutputsCorrespondingToChangedFiles(
            context = context,
            log = log,
            target = target,
            dataManager = dataManager,
          )
        }

        try {
          var buildersPassed = 0
          var instrumentedClassesSaved = false
          for (builder in builders) {
            if (builder.category == BuilderCategory.CLASS_POST_PROCESSOR && !instrumentedClassesSaved) {
              instrumentedClassesSaved = true
              // ensure changes from instruments are visible to class post-processors
              saveInstrumentedClasses(outputConsumer)
            }

            outputConsumer.setCurrentBuilderName(builder.presentableName)

            val deletedFiles = fsState.getAndClearDeletedPaths(target)
            require(deletedFiles.isEmpty()) {
              "Unexpected files to delete: $deletedFiles"
            }

            val start = System.nanoTime()
            val processedSourcesBefore = outputConsumer.getNumberOfProcessedSources()
            val buildResult = builder.build(context, chunk, dirtyFilesHolder, outputConsumer)
            storeBuilderStatistics(
              builder = builder,
              elapsedTime = System.nanoTime() - start,
              processedFiles = outputConsumer.getNumberOfProcessedSources() - processedSourcesBefore,
            )

            if (buildResult != null && buildResult != ModuleLevelBuilder.ExitCode.NOTHING_DONE) {
              doneSomething = true
            }
            if (buildResult == ModuleLevelBuilder.ExitCode.ABORT) {
              throw StopBuildException("Builder ${builder.presentableName} requested build stop")
            }

            coroutineContext.ensureActive()

            if (buildResult == ModuleLevelBuilder.ExitCode.ADDITIONAL_PASS_REQUIRED) {
              nextPassRequired = true
            }
            else if (buildResult == ModuleLevelBuilder.ExitCode.CHUNK_REBUILD_REQUIRED) {
              if (!rebuildFromScratchRequested && !isCleanBuild) {
                notifyChunkRebuildRequested(context, chunk, builder)
                // allow rebuild from scratch only once per chunk
                rebuildFromScratchRequested = true
                // forcibly mark all files in the chunk dirty
                fsState.clearContextRoundData(context)
                FSOperations.markDirty(context, CompilationRound.NEXT, chunk, null)
                // reverting to the beginning
                nextPassRequired = true
                outputConsumer.clear()
                break
              }
              else {
                log.debug("Builder ${builder.presentableName} requested second chunk rebuild")
              }
            }

            buildersPassed++
          }
        }
        finally {
          outputConsumer.setCurrentBuilderName(null)
          val moreToCompile = JavaBuilderUtil.updateMappingsOnRoundCompletion(context, dirtyFilesHolder, chunk)
          if (moreToCompile) {
            nextPassRequired = true
          }
          JavaBuilderUtil.clearDataOnRoundCompletion(context)
        }
      } while (nextPassRequired)
    }
    finally {
      saveInstrumentedClasses(outputConsumer)
      outputConsumer.fireFileGeneratedEvents()
      outputConsumer.clear()
      for (builder in builders) {
        builder.chunkBuildFinished(context, chunk)
      }
      if (Utils.errorsDetected(context)) {
        context.processMessage(CompilerMessage("", BuildMessage.Kind.JPS_INFO, "Errors occurred while compiling module ${chunk.presentableShortName}"))
      }
    }

    return doneSomething
  }

  private suspend fun buildTarget(
    context: CompileContext,
    target: BazelModuleBuildTarget,
    builders: Array<ModuleLevelBuilder>,
    buildState: LoadStateResult?,
  ) {
    val targets = java.util.Set.of<BuildTarget<*>>(target)
    try {
      context.setCompilationStartStamp(targets, System.currentTimeMillis())

      Utils.ERRORS_DETECTED_KEY.set(context, false)

      var doneSomething = false

      val fsState = context.projectDescriptor.fsState
      require(!fsState.isInitialScanPerformed(target))
      if (isCleanBuild || buildState == null) {
        initFsStateForCleanBuild(context = context, target = target)
      }
      else {
        val projectDescriptor = context.projectDescriptor
        require(context.getUserData(CURRENT_ROUND_DELTA_KEY) == null)
        require(context.getUserData(NEXT_ROUND_DELTA_KEY) == null)
        // linked - stable results
        val buildRootIndex = projectDescriptor.buildRootIndex as BazelBuildRootIndex
        val k = Array<BuildRootDescriptor>(buildState.changedFiles.size) {
          buildRootIndex.fileToDescriptors.get(buildState.changedFiles.get(it))!!
        }
        val v = Array<Set<Path>>(buildState.changedFiles.size) {
          ObjectArraySet(arrayOf(buildState.changedFiles.get(it)))
        }
        val fsState = projectDescriptor.fsState
        fsState.getDelta(target).initRecompile(Object2ObjectArrayMap(k, v))
        fsState.markInitialScanPerformed(target)

        val deletedFiles = buildState.deletedFiles
        if (!deletedFiles.isEmpty()) {
          doneSomething = deleteOutputsAssociatedWithDeletedPaths(context = context, target = target, deletedFiles = deletedFiles, log = log)
        }
      }

      fsState.beforeChunkBuildStart(context, targets)

      val runBuildersSpan = start("runBuilders")
      if (runModuleLevelBuilders(context, target, builders)) {
        doneSomething = true
      }
      runBuildersSpan.complete()

      fsState.clearContextRoundData(context)
      fsState.clearContextChunk(context)

      if (doneSomething) {
        markTargetUpToDate(context = context, target = target, dataManager = dataManager)
      }
    }
    finally {
      try {
        // restore deleted paths that were not processed
        val map = context.getUserData(Utils.REMOVED_SOURCES_KEY)
        if (map != null) {
          val fsState = context.projectDescriptor.fsState
          for (entry in map.entries) {
            for (file in entry.value) {
              fsState.registerDeleted(context, entry.key, file, null)
            }
          }
        }
      }
      finally {
        context.putUserData(Utils.REMOVED_SOURCES_KEY, null)
      }
    }
  }

  private fun storeBuilderStatistics(builder: Builder, elapsedTime: Long, processedFiles: Int) {
    builderToDuration.computeIfAbsent(builder) { AtomicLong() }.addAndGet(elapsedTime)
    numberOfSourcesProcessedByBuilder.computeIfAbsent(builder) { AtomicInteger() }.addAndGet(processedFiles)
  }
}

private class ChainedTargetsBuildListener(private val context: CompileContextImpl, private val dataManager: BazelBuildDataProvider) : BuildListener {
  override fun filesGenerated(event: FileGeneratedEvent) {
    val projectDescriptor = context.projectDescriptor
    val fsState = projectDescriptor.fsState
    val buildRootIndex = projectDescriptor.buildRootIndex as BazelBuildRootIndex
    for (pair in event.paths) {
      val relativePath = pair.getSecond()
      val file = if (relativePath == ".") Path.of(pair.getFirst()) else Path.of(pair.getFirst(), relativePath)
      val buildRootDescriptor = buildRootIndex.fileToDescriptors.get(file) ?: continue
      val target = buildRootDescriptor.target
      if (event.sourceTarget != target) {
        fsState.markDirty(
          /* context = */ context,
          /* round = */ CompilationRound.NEXT,
          /* file = */ file,
          /* buildRootDescriptor = */ buildRootDescriptor,
          /* stampStorage = */ dataManager.getFileStampStorage(target),
          /* saveEventStamp = */ false,
        )
      }
    }
  }

  override fun filesDeleted(event: FileDeletedEvent) {
    val fsState = context.projectDescriptor.fsState
    val buildRootIndex = context.projectDescriptor.buildRootIndex as BazelBuildRootIndex
    for (path in event.filePaths) {
      val file = Path.of(path)
      val rootDescriptor = buildRootIndex.fileToDescriptors.get(file) ?: continue
      fsState.registerDeleted(context, rootDescriptor.target, file)
    }
  }
}

private fun deleteOutputsAssociatedWithDeletedPaths(
  context: CompileContext,
  target: ModuleBuildTarget,
  deletedFiles: List<RemovedFileInfo>,
  log: RequestLog,
): Boolean {
  val dirsToDelete = linkedSet<Path>()
  var doneSomething = false
  // delete outputs associated with removed paths
  for (item in deletedFiles) {
    val deletedOutputFiles = ArrayList<Path>()
    for (output in item.outputs) {
      val deleted = Files.deleteIfExists(output)
      if (deleted) {
        deletedOutputFiles.add(output)
        output.parent?.let {
          dirsToDelete.add(it)
        }
      }
    }
    if (!deletedOutputFiles.isEmpty()) {
      doneSomething = true
      log.info("Deleted files: $deletedOutputFiles")
      context.processMessage(FileDeletedEvent(deletedOutputFiles.map { it.toString() }))
    }
  }

  val removedSources = context.getUserData(Utils.REMOVED_SOURCES_KEY)?.get(target)
  if (removedSources == null) {
    Utils.REMOVED_SOURCES_KEY.set(context, java.util.Map.of(target, deletedFiles.map { it.sourceFile } as Collection<Path>))
  }
  else {
    val set = linkedSet<Path>()
    set.addAll(removedSources)
    deletedFiles.mapTo(set) { it.sourceFile }
    context.putUserData(Utils.REMOVED_SOURCES_KEY, java.util.Map.of(target, set as Collection<Path>))
  }

  FSOperations.pruneEmptyDirs(context, dirsToDelete)
  return doneSomething
}

internal fun reportRebuiltModules(context: CompileContextImpl) {
  val modules = BuildTargetConfiguration.MODULES_WITH_TARGET_CONFIG_CHANGED_KEY.get(context)
  if (modules.isNullOrEmpty()) {
    return
  }

  val text = "${modules.joinToString { m -> "'" + m.name + "'" }} was fully rebuilt due to project configuration changes"
  context.processMessage(CompilerMessage("", BuildMessage.Kind.INFO, text))
}

internal fun reportUnprocessedChanges(context: CompileContextImpl, moduleTarget: ModuleBuildTarget) {
  if (context.projectDescriptor.fsState.hasUnprocessedChanges(context, moduleTarget)) {
    context.processMessage(UnprocessedFSChangesNotification())
  }
}

private inline fun processFilesToRecompile(
  context: CompileContext,
  target: BazelModuleBuildTarget,
  fsState: BuildFSState,
  processor: (Path, JavaSourceRootDescriptor) -> Boolean,
): Boolean {
  val scope = context.scope
  val delta = fsState.getEffectiveFilesDelta(context, target)
  delta.lockData()
  try {
    for (entry in delta.sourceMapToRecompile.entries) {
      val root = entry.key as JavaSourceRootDescriptor
      for (file in entry.value) {
        if (!scope.isAffected(target, file)) {
          continue
        }
        if (!processor(file, root)) {
          return false
        }
      }
    }
    return true
  }
  finally {
    delta.unlockData()
  }
}

/**
 * if an output file is generated from multiple sources, make sure all of them are added for recompilation
 */
private fun completeRecompiledSourcesSet(context: CompileContext, target: BazelModuleBuildTarget, dataManager: BazelBuildDataProvider) {
  val projectDescriptor = context.projectDescriptor
  val affected = mutableListOf<List<String>>()
  val affectedSources = HashSet<Path>()

  val sourceToOut = dataManager.sourceToOutputMapping
  processFilesToRecompile(context = context, target = target, fsState = projectDescriptor.fsState) { file, root ->
    if (!affectedSources.add(file)) {
      sourceToOut.getDescriptor(file)?.outputs?.let {
        affected.add(it)
      }
    }
    true
  }

  if (affected.isEmpty()) {
    return
  }

  // one output can be produced by different sources, so, we find intersection by outputs
  val affectedSourceFiles = sourceToOut.findAffectedSources(affected)
  if (affectedSourceFiles.isEmpty()) {
    return
  }

  val fileToDescriptors = (context.projectDescriptor.buildRootIndex as BazelBuildRootIndex).fileToDescriptors
  val stampStorage = dataManager.stampStorage
  val fsState = context.projectDescriptor.fsState
  for (file in affectedSourceFiles) {
    val rootDescriptor = fileToDescriptors.get(file) ?: continue
    fsState.markDirtyIfNotDeleted(
      context,
      CompilationRound.CURRENT,
      file,
      rootDescriptor,
      stampStorage
    )
  }
}

private fun notifyChunkRebuildRequested(context: CompileContext, chunk: ModuleChunk, builder: ModuleLevelBuilder) {
  var infoMessage = "Builder \"${builder.presentableName}\" requested rebuild of module chunk \"${chunk.name}\""
  var kind = BuildMessage.Kind.JPS_INFO
  val scope = context.scope
  for (target in chunk.targets) {
    if (!scope.isWholeTargetAffected(target)) {
      infoMessage += ".\n"
      infoMessage += "Consider building whole project or rebuilding the module."
      kind = BuildMessage.Kind.INFO
      break
    }
  }
  context.processMessage(CompilerMessage("", kind, infoMessage))
}

private fun saveInstrumentedClasses(outputConsumer: ChunkBuildOutputConsumerImpl) {
  for (compiledClass in outputConsumer.compiledClasses.values) {
    if (compiledClass.isDirty) {
      compiledClass.save()
    }
  }
}