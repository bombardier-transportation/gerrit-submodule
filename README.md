# Gerrit submodule plugin

The purpose of this plugin is to add support for merge order enforcement.

In order to add a merge order enforcement put the following meta data in the commit message:

Depends-On: repo~branch~commitId|changeId

Read as changeId (or commitId) needs to be merged into branch in repo before
this commit can be merged and the submodule pointer for repo needs to point to
or past the changeId (or commitId).

# Build instructions

mvn clean package

# Deploy instructions

ssh -p PORT USER@HOST gerrit plugin install -n submodule.jar - <target/submodule-0.3.jar
