/*
 * Copyright 2013-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.jvm.java;

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.NativeLinkables;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;

/** Common utilities for working with {@link JavaLibrary} objects. */
public class JavaLibraryRules {

  /** Utility class: do not instantiate. */
  private JavaLibraryRules() {}

  public static void addCompileToJarSteps(
      BuildTarget target,
      ProjectFilesystem filesystem,
      BuildContext context,
      BuildableContext buildableContext,
      Optional<Path> outputJar,
      SourcePathRuleFinder ruleFinder,
      ImmutableSortedSet<SourcePath> srcs,
      ImmutableSortedSet<SourcePath> resources,
      ImmutableList<String> postprocessClassesCommands,
      ImmutableSortedSet<SourcePath> compileTimeClasspathSourcePaths,
      boolean trackClassUsage,
      @Nullable Path depFileRelativePath,
      CompileToJarStepFactory compileStepFactory,
      Optional<Path> resourcesRoot,
      Optional<SourcePath> manifestFile,
      RemoveClassesPatternsMatcher classesToRemoveFromJar,
      ImmutableList.Builder<Step> steps) {
    // Always create the output directory, even if there are no .java files to compile because there
    // might be resources that need to be copied there.
    Path outputDirectory = DefaultJavaLibrary.getClassesDir(target, filesystem);

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), filesystem, outputDirectory)));

    // We don't want to add provided to the declared or transitive deps, since they're only used at
    // compile time.
    ImmutableSortedSet<Path> compileTimeClasspathPaths =
        compileTimeClasspathSourcePaths
            .stream()
            .map(context.getSourcePathResolver()::getAbsolutePath)
            .collect(MoreCollectors.toImmutableSortedSet());

    // If there are resources, then link them to the appropriate place in the classes directory.
    JavaPackageFinder finder = context.getJavaPackageFinder();
    if (resourcesRoot.isPresent()) {
      finder = new ResourcesRootPackageFinder(resourcesRoot.get(), finder);
    }

    steps.add(
        new CopyResourcesStep(
            filesystem, context, ruleFinder, target, resources, outputDirectory, finder));

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(),
                filesystem,
                DefaultJavaLibrary.getOutputJarDirPath(target, filesystem))));

    // Only run javac if there are .java files to compile or we need to shovel the manifest file
    // into the built jar.
    if (!srcs.isEmpty()) {
      ClassUsageFileWriter usedClassesFileWriter;
      if (trackClassUsage) {
        Preconditions.checkNotNull(depFileRelativePath);
        usedClassesFileWriter = new DefaultClassUsageFileWriter(depFileRelativePath);

        buildableContext.recordArtifact(depFileRelativePath);
      } else {
        usedClassesFileWriter = NoOpClassUsageFileWriter.instance();
      }

      // This adds the javac command, along with any supporting commands.
      Path pathToSrcsList = BuildTargets.getGenPath(filesystem, target, "__%s__srcs");
      steps.add(
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(), filesystem, pathToSrcsList.getParent())));

      Path scratchDir = BuildTargets.getGenPath(filesystem, target, "lib__%s____working_directory");

      steps.addAll(
          MakeCleanDirectoryStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(), filesystem, scratchDir)));
      Optional<Path> workingDirectory = Optional.of(scratchDir);

      Optional<Path> generatedCodeDirectory = getAnnotationPath(filesystem, target);

      ImmutableSortedSet<Path> javaSrcs =
          srcs.stream()
              .map(context.getSourcePathResolver()::getRelativePath)
              .collect(MoreCollectors.toImmutableSortedSet());

      compileStepFactory.createCompileToJarStep(
          context,
          javaSrcs,
          target,
          context.getSourcePathResolver(),
          ruleFinder,
          filesystem,
          compileTimeClasspathPaths,
          outputDirectory,
          generatedCodeDirectory,
          workingDirectory,
          pathToSrcsList,
          postprocessClassesCommands,
          ImmutableSortedSet.of(outputDirectory),
          /* mainClass */ Optional.empty(),
          manifestFile.map(context.getSourcePathResolver()::getAbsolutePath),
          outputJar.get(),
          usedClassesFileWriter,
          /* output params */
          steps,
          buildableContext,
          classesToRemoveFromJar);
    }

    if (outputJar.isPresent()) {
      Path output = outputJar.get();

      // No source files, only resources
      if (srcs.isEmpty()) {
        compileStepFactory.createJarStep(
            filesystem,
            outputDirectory,
            Optional.empty(),
            manifestFile.map(context.getSourcePathResolver()::getAbsolutePath),
            classesToRemoveFromJar,
            output,
            steps);
      }
      buildableContext.recordArtifact(output);
    }
  }

  public static Optional<Path> getAnnotationPath(ProjectFilesystem filesystem, BuildTarget target) {
    return Optional.of(BuildTargets.getAnnotationPath(filesystem, target, "__%s_gen__"));
  }

  static void addAccumulateClassNamesStep(
      BuildTarget target,
      ProjectFilesystem filesystem,
      @Nullable SourcePath sourcePathToOutput,
      BuildableContext buildableContext,
      BuildContext buildContext,
      ImmutableList.Builder<Step> steps) {

    Path pathToClassHashes = JavaLibraryRules.getPathToClassHashes(target, filesystem);
    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(), filesystem, pathToClassHashes.getParent())));
    steps.add(
        new AccumulateClassNamesStep(
            filesystem,
            Optional.ofNullable(sourcePathToOutput)
                .map(buildContext.getSourcePathResolver()::getRelativePath),
            pathToClassHashes));
    buildableContext.recordArtifact(pathToClassHashes);
  }

  static JavaLibrary.Data initializeFromDisk(
      BuildTarget buildTarget, ProjectFilesystem filesystem, OnDiskBuildInfo onDiskBuildInfo)
      throws IOException {
    List<String> lines =
        onDiskBuildInfo.getOutputFileContentsByLine(getPathToClassHashes(buildTarget, filesystem));
    ImmutableSortedMap<String, HashCode> classHashes =
        AccumulateClassNamesStep.parseClassHashes(lines);

    return new JavaLibrary.Data(classHashes);
  }

  private static Path getPathToClassHashes(BuildTarget buildTarget, ProjectFilesystem filesystem) {
    return BuildTargets.getGenPath(filesystem, buildTarget, "%s.classes.txt");
  }

  /**
   * @return all the transitive native libraries a rule depends on, represented as a map from their
   *     system-specific library names to their {@link SourcePath} objects.
   */
  public static ImmutableMap<String, SourcePath> getNativeLibraries(
      Iterable<BuildRule> deps, final CxxPlatform cxxPlatform) throws NoSuchBuildTargetException {
    // Allow the transitive walk to find NativeLinkables through the BuildRuleParams deps of a
    // JavaLibrary or CalculateAbi object. The deps may be either one depending if we're compiling
    // against ABI rules or full rules
    Predicate<Object> traverse = r -> r instanceof JavaLibrary || r instanceof CalculateAbi;
    return NativeLinkables.getTransitiveSharedLibraries(cxxPlatform, deps, traverse);
  }

  public static ImmutableSortedSet<BuildRule> getAbiRules(
      BuildRuleResolver resolver, Iterable<BuildRule> inputs) throws NoSuchBuildTargetException {
    ImmutableSortedSet.Builder<BuildRule> abiRules = ImmutableSortedSet.naturalOrder();
    for (BuildRule input : inputs) {
      if (input instanceof HasJavaAbi && ((HasJavaAbi) input).getAbiJar().isPresent()) {
        Optional<BuildTarget> abiJarTarget = ((HasJavaAbi) input).getAbiJar();
        BuildRule abiJarRule = resolver.requireRule(abiJarTarget.get());
        abiRules.add(abiJarRule);
      }
    }
    return abiRules.build();
  }

  public static ZipArchiveDependencySupplier getAbiClasspath(
      BuildRuleResolver resolver, Iterable<BuildRule> inputs) throws NoSuchBuildTargetException {
    return new ZipArchiveDependencySupplier(
        new SourcePathRuleFinder(resolver),
        getAbiRules(resolver, inputs)
            .stream()
            .map(BuildRule::getSourcePathToOutput)
            .collect(MoreCollectors.toImmutableSortedSet()));
  }

  /**
   * Iterates the input BuildRules and translates them to their ABI rules when possible. This is
   * necessary when constructing a BuildRuleParams object, for example, where we want to translate
   * rules to their ABI rules, but not skip over BuildRules such as GenAidl, CxxLibrary, NdkLibrary,
   * AndroidResource, etc. These should still be returned from this method, but without translation.
   */
  public static ImmutableSortedSet<BuildRule> getAbiRulesWherePossible(
      BuildRuleResolver resolver, Iterable<BuildRule> inputs) throws NoSuchBuildTargetException {
    ImmutableSortedSet.Builder<BuildRule> abiRules = ImmutableSortedSet.naturalOrder();
    for (BuildRule dep : inputs) {
      if (dep instanceof HasJavaAbi) {
        Optional<BuildTarget> abiJarTarget = ((HasJavaAbi) dep).getAbiJar();
        if (abiJarTarget.isPresent()) {
          abiRules.add(resolver.requireRule(abiJarTarget.get()));
        }
      } else {
        abiRules.add(dep);
      }
    }
    return abiRules.build();
  }
}
