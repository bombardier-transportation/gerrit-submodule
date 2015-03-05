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
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.reviewdb.client.Project.NameKey;
import com.google.gerrit.server.events.CommitReceivedEvent;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.validators.CommitValidationException;
import com.google.gerrit.server.git.validators.CommitValidationListener;
import com.google.gerrit.server.git.validators.CommitValidationMessage;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.FooterKey;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Listen
@Singleton
public class DependsOnCommitValidator implements CommitValidationListener {
  private static final Logger log = LoggerFactory
      .getLogger(DependsOnCommitValidator.class);

  private final GerritApi api;
  private final GitRepositoryManager repoManager;

  public static final FooterKey DEPENDS_ON = new FooterKey("Depends-On");
  public static final Pattern DEPENDS_ON_CHANGE_PATTERN = Pattern
      .compile("^(.*)~(.*)~(I[0-9a-f]{8,}.*)$");
  public static final Pattern DEPENDS_ON_COMMIT_PATTERN = Pattern
      .compile("^(.*)~(.*)~([0-9a-f]{8,}.*)$");

  @Inject
  public DependsOnCommitValidator(GerritApi api,
      GitRepositoryManager repoManager) throws ConfigInvalidException,
      IOException {
    this.api = api;
    this.repoManager = repoManager;
  }


  @Override
  public List<CommitValidationMessage> onCommitReceived(
      CommitReceivedEvent receiveEvent) throws CommitValidationException {
    final List<String> dependsOnFooters =
        receiveEvent.commit.getFooterLines(DEPENDS_ON);

    List<CommitValidationMessage> messages =
        new LinkedList<CommitValidationMessage>();

    for (String dependency : dependsOnFooters) {
      dependency = dependency.trim();

      log.info("Validating dependency: " + dependency);

      boolean valid = false;

      Matcher changeMatcher = DEPENDS_ON_CHANGE_PATTERN.matcher(dependency);
      Matcher commitMatcher = DEPENDS_ON_COMMIT_PATTERN.matcher(dependency);

      try {
        if (changeMatcher.matches()) {
          String project = changeMatcher.group(1);
          String branch = changeMatcher.group(2);
          String changeId = changeMatcher.group(3);

          log.info("Commit depends on change " + changeId + " in " + project
              + "[" + branch + "]");

          for (ChangeInfo change : api.changes().query().get()) {
            if (change.project.equals(project) && change.branch.equals(branch)
                && change.changeId.equals(changeId)) {
              valid = true;
            }
          }
        } else if (commitMatcher.matches()) {
          String project = commitMatcher.group(1);
          String branch = commitMatcher.group(2);
          String commitId = commitMatcher.group(3);

          log.info("Commit depends on commit " + commitId + " in " + project
              + "[" + branch + "]");

          Repository repo = repoManager.openRepository(NameKey.parse(project));
          try {
            RevWalk rw = new RevWalk(repo);
            RevCommit branchHead =
                rw.parseCommit(repo.getRef(branch).getObjectId());
            rw.reset();

            RevCommit commit = rw.parseCommit(ObjectId.fromString(commitId));
            rw.reset();

            valid = rw.isMergedInto(commit, branchHead);
          } finally {
            repo.close();
          }
        }
      } catch (Exception e) {
        log.info("Exception while validating " + dependency + ": "
            + e.toString());
      }

      if (!valid) {
        messages.add(new CommitValidationMessage("Dependency: " + dependency
            + " not found!", true));
        log.info("Failed validation of " + dependency);
      } else {
        log.info("Successfully validated " + dependency);
      }
    }
    if (messages.size() > 0) {
      throw new CommitValidationException(
          String.format("Failed to validate some dependencies for the commit!%n%n"
              + " * Ensure that the Depends-On footers are correct.%n"
              + " * Ensure that the dependencies are known to gerrit.%n%n"
              + "Then try again!"), messages);
    }

    return messages;
  }

}
