/*
 * Copyright 2024 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.android.qsync;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.idea.blaze.android.manifest.ManifestParser;
import com.google.idea.blaze.base.qsync.ProjectProtoTransformProvider;
import com.google.idea.blaze.base.qsync.QuerySyncProject;
import com.google.idea.blaze.common.Context;
import com.google.idea.blaze.common.artifact.BuildArtifactCache;
import com.google.idea.blaze.common.artifact.CachedArtifact;
import com.google.idea.blaze.exception.BuildException;
import com.google.idea.blaze.qsync.deps.ArtifactDirectories;
import com.google.idea.blaze.qsync.deps.ProjectProtoUpdate;
import com.google.idea.blaze.qsync.deps.ProjectProtoUpdateOperation;
import com.google.idea.blaze.qsync.java.AddAndroidResPackages;
import com.google.idea.blaze.qsync.java.AddDependencyAars;
import com.google.idea.blaze.qsync.project.BuildGraphData;
import com.google.idea.blaze.qsync.project.ProjectPath;
import com.google.idea.blaze.qsync.project.ProjectProto.Project;
import com.google.idea.blaze.qsync.project.ProjectProtoTransform;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** A {@link ProjectProtoTransform} that adds android specific information to the project proto. */
public class AndroidProjectProtoTransform implements ProjectProtoTransform {

  /**
   * Provides a {@link ProjectProtoTransform} that adds android specific information to the project
   * proto.
   */
  public static class Provider implements ProjectProtoTransformProvider {
    @Override
    public List<ProjectProtoTransform> createTransforms(QuerySyncProject project) {
      return ImmutableList.of(new AndroidProjectProtoTransform(project));
    }
  }

  private final ImmutableList<ProjectProtoUpdateOperation> updateOperations;

  private AndroidProjectProtoTransform(QuerySyncProject project) {
    Map<ProjectPath, Map<String, Path>> existingContents =
      ImmutableMap.of(ArtifactDirectories.DEFAULT, ProjectProtoTransform.getExistingContents(project.getProjectPathResolver().resolve(ArtifactDirectories.DEFAULT)));

    updateOperations =
        ImmutableList.of(
            new AddDependencyAars(
              project.getArtifactTracker()::getStateSnapshot,
              (buildArtifact, artifactDirectory) -> {
                BuildArtifactCache artifactCache = project.getBuildArtifactCache();
                if (artifactCache.get(buildArtifact.digest()).isPresent()) {
                  return buildArtifact.blockingGetFrom(artifactCache);
                }

                if (existingContents.containsKey(artifactDirectory)) {
                  Path artifactPath = existingContents.get(artifactDirectory).get(buildArtifact.digest());
                  if (artifactPath != null) {
                    return new CachedArtifact(artifactPath);
                  }
                }
                throw new BuildException(
                  "Artifact" + buildArtifact.artifactPath() + " missing from the cache: " + artifactCache + " and " + artifactDirectory);
              },
              project.getProjectDefinition(),
              in -> ManifestParser.parseManifestFromInputStream(in).packageName),
            new AddAndroidResPackages(project.getArtifactTracker()::getStateSnapshot));
  }

  @Override
  public Project apply(Project proto, BuildGraphData graph, Context<?> context)
      throws BuildException {
    ProjectProtoUpdate update = new ProjectProtoUpdate(proto, graph, context);
    for (var op : updateOperations) {
      op.update(update);
    }
    return update.build();
  }
}
