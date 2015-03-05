package com.bombardier.transportation.ewea;

/**
 * Copyright 2015 Bombardier Transportation AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

import com.google.gerrit.extensions.annotations.Listen;
import com.google.gerrit.reviewdb.client.Branch.NameKey;
import com.google.gerrit.reviewdb.client.PatchSet.Id;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.git.CodeReviewCommit;
import com.google.gerrit.server.git.CommitMergeStatus;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.validators.MergeValidationException;
import com.google.gerrit.server.git.validators.MergeValidationListener;
import com.google.gerrit.server.project.ProjectState;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.BlobBasedConfig;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.FooterKey;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Listen
@Singleton
public class DependsOnMergeValidator implements MergeValidationListener {
  private static final Logger log = LoggerFactory
      .getLogger(DependsOnMergeValidator.class);

  private final GitRepositoryManager repoManager;

  public static final FooterKey CHANGE_ID = new FooterKey("Change-Id");
  public static final Pattern CHANGE_ID_PATTERN = Pattern
      .compile("^(I[0-9a-f]{8,}.*)$");
  public static final Pattern SUBMODULE_URL_PATTERN = Pattern
      .compile("^\\.\\./(.*)$");

  @Inject
  public DependsOnMergeValidator(GitRepositoryManager repoManager)
      throws ConfigInvalidException, IOException {
    this.repoManager = repoManager;
  }

  @Override
  public void onPreMerge(Repository repo, CodeReviewCommit commit,
      ProjectState destProject, NameKey destBranch, Id patchSetId)
      throws MergeValidationException {
    final List<String> dependsOnFooters =
        commit.getFooterLines(DependsOnCommitValidator.DEPENDS_ON);

    Map<String, String> moduleCommitMap =
        parseSubmodules(repo, commit.getTree());

    for (String dependency : dependsOnFooters) {
      dependency = dependency.trim();

      log.info("Validating dependency: " + dependency);

      boolean valid = false;

      Matcher changeMatcher =
          DependsOnCommitValidator.DEPENDS_ON_CHANGE_PATTERN
              .matcher(dependency);
      Matcher commitMatcher =
          DependsOnCommitValidator.DEPENDS_ON_COMMIT_PATTERN
              .matcher(dependency);

      // Validate that the submodule pointer is pointing to/past our dependency
      // 1. Adjust search start to current submodule pointer
      // 2. Validate that submodule pointer is merged into branch in sub repo
      // 3. Search backwards from submodule pointer for dependency

      if (changeMatcher.matches()) {
        String project = changeMatcher.group(1);
        String branch = changeMatcher.group(2);
        String changeId = changeMatcher.group(3);
        String submoduleCommit = moduleCommitMap.get(project);

        log.info("Merge depends on change " + changeId + " in " + project + "["
            + branch + "] to be an ancestor of " + submoduleCommit);

        if (submoduleCommit != null
            && validateChangeIdDependency(project, branch, changeId,
                submoduleCommit)) {
          log.info("Successfully validated " + dependency);
          valid = true;
        }
      } else if (commitMatcher.matches()) {
        String project = commitMatcher.group(1);
        String branch = commitMatcher.group(2);
        String commitId = commitMatcher.group(3);
        String submoduleCommit = moduleCommitMap.get(project);

        log.info("Merge depends on commit " + commitId + " in " + project + "["
            + branch + "] to be an ancestor of " + submoduleCommit);

        if (submoduleCommit != null
            && validateCommitDependency(project, branch, commitId,
                submoduleCommit)) {
          log.info("Successfully validated " + dependency);
          valid = true;
        }
      }

      if (!valid) {
        log.info("Failed validation of " + dependency);
        throw new MergeValidationException(CommitMergeStatus.MISSING_DEPENDENCY);
      }
    }
  }

  private boolean isChangeMergedInto(String changeId, RevCommit commit) {
    final List<String> idList = commit.getFooterLines(CHANGE_ID);

    for (String change : idList) {
      change = change.trim();
      Matcher changeMatcher = CHANGE_ID_PATTERN.matcher(change);
      if (changeMatcher.matches() && changeMatcher.group(1).equals(changeId)) {
        log.info("Change " + changeId + " is an ancestor of "
            + commit.getId().toString());
        return true;
      }
    }

    for (RevCommit parent : commit.getParents()) {
      if (isChangeMergedInto(changeId, parent)) {
        return true;
      }
    }

    return false;
  }

  private Map<String, String> parseGitModules(Repository repo, RevTree tree) {
    Map<String, String> pathModuleMap = new HashMap<String, String>();

    try {
      BlobBasedConfig gitmodules =
          new BlobBasedConfig(new Config(), repo, tree, ".gitmodules");
      for (String section : gitmodules.getSections()) {
        if (section.equals("submodule")) {
          for (String subSection : gitmodules.getSubsections(section)) {
            String path = null;
            String module = null;

            for (String name : gitmodules.getNames(section, subSection)) {
              String value = gitmodules.getString(section, subSection, name);
              if (name.equals("url")) {
                Matcher m = SUBMODULE_URL_PATTERN.matcher(value);
                if (m.matches()) {
                  module = m.group(1);
                }
              } else if (name.equals("path")) {
                path = value;
              }
            }
            if (path != null && module != null) {
              log.info("Submodule: " + module + " is registered at " + path);
              pathModuleMap.put(path, module);
            }
          }
        }
      }
    } catch (Exception e) {
      log.info("Failed to parse .gitmodules file: " + e.toString());
    }

    return pathModuleMap;
  }

  private Map<String, String> parseSubmodules(Repository repo, RevTree tree) {
    Map<String, String> moduleCommitMap = new HashMap<String, String>();

    Map<String, String> pathModuleMap = parseGitModules(repo, tree);

    if (pathModuleMap.size() > 0) {
      TreeWalk walker = new TreeWalk(repo);
      try {
        walker.addTree(tree);
        walker.setRecursive(true);
        while (walker.next()) {
          if (walker.getFileMode(0).equals(FileMode.GITLINK)) {
            String module = pathModuleMap.get(walker.getPathString());
            if (module != null) {
              String commit = walker.getObjectId(0).name();
              log.info("Submodule: " + module + " points at " + commit);
              moduleCommitMap.put(module, commit);
            }
          }
        }
      } catch (Exception e) {
        log.info("Failed to resolve submodule pointers: " + e.toString());
      }
    }

    return moduleCommitMap;
  }

  private boolean validateChangeIdDependency(String project, String branch,
      String changeId, String submoduleCommit) {
    boolean valid = false;

    Project.NameKey projectKey = Project.NameKey.parse(project);

    try {
      Repository depRepo = repoManager.openRepository(projectKey);
      RevWalk rw = new RevWalk(depRepo);
      try {
        RevCommit branchHead =
            rw.parseCommit(depRepo.getRef(branch).getObjectId());
        rw.reset();

        RevCommit subCommit =
            rw.parseCommit(ObjectId.fromString(submoduleCommit));
        rw.reset();

        if (rw.isMergedInto(subCommit, branchHead)) {
          rw.reset();
          valid = isChangeMergedInto(changeId, subCommit);
        }
      } finally {
        depRepo.close();
      }
    } catch (Exception e) {
      log.info("Dependency validation failed: " + e.toString());
    }

    return valid;
  }

  private boolean validateCommitDependency(String project, String branch,
      String commitId, String submoduleCommit) {
    Project.NameKey projectKey = Project.NameKey.parse(project);

    boolean valid = false;

    try {
      Repository depRepo = repoManager.openRepository(projectKey);
      try {
        RevWalk rw = new RevWalk(depRepo);
        RevCommit branchHead =
            rw.parseCommit(depRepo.getRef(branch).getObjectId());
        rw.reset();

        RevCommit subCommit =
            rw.parseCommit(ObjectId.fromString(submoduleCommit));
        rw.reset();

        if (rw.isMergedInto(subCommit, branchHead)) {
          rw.reset();
          RevCommit depCommit = rw.parseCommit(ObjectId.fromString(commitId));
          rw.reset();

          valid = rw.isMergedInto(depCommit, subCommit);
        }
      } finally {
        depRepo.close();
      }
    } catch (Exception e) {
      log.info("Dependency validation failed: " + e.toString());
    }

    return valid;
  }

}
