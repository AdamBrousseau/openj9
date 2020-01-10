/*******************************************************************************
 * Copyright (c) 2019, 2020 IBM Corp. and others
 *
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which accompanies this
 * distribution and is available at https://www.eclipse.org/legal/epl-2.0/
 * or the Apache License, Version 2.0 which accompanies this distribution and
 * is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * This Source Code may also be made available under the following
 * Secondary Licenses when the conditions for such availability set
 * forth in the Eclipse Public License, v. 2.0 are satisfied: GNU
 * General Public License, version 2 with the GNU Classpath
 * Exception [1] and GNU General Public License, version 2 with the
 * OpenJDK Assembly Exception [2].
 *
 * [1] https://www.gnu.org/software/classpath/license.html
 * [2] http://openjdk.java.net/legal/assembly-exception.html
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0 OR GPL-2.0 WITH Classpath-exception-2.0 OR LicenseRef-GPL-2.0 WITH Assembly-exception
 *******************************************************************************/
SETUP_LABEL = params.SETUP_LABEL
if (!SETUP_LABEL) {
    SETUP_LABEL = 'worker'
}

LABEL = params.LABEL
if (!LABEL) {
    LABEL = 'ci.role.build'
}

CLEAN_CACHE_DIR = params.CLEAN_CACHE_DIR
if ((CLEAN_CACHE_DIR == null) || (CLEAN_CACHE_DIR == '')) {
    CLEAN_CACHE_DIR = false
}

UPDATE_BUILD_NODES = params.UPDATE_BUILD_NODES

EXTENSIONS_REPOS = [[name: "openj9", url: "git@github.ibm.com:runtimes/openj9.git"],
                    [name: "omr", url: "git@github.ibm.com:runtimes/openj9-omr.git"],
                    [name: "closedj9", url: "git@github.ibm.com:runtimes/closedj9.git"],
                    [name: "tooling", url: "git@github.ibm.com:runtimes/tooling.git"],
                    [name: "fips", url: "git@github.ibm.com:runtimes/fips.git"],
                    [name: "binaries", url: "git@github.ibm.com:runtimes/binaries.git"],]

properties([buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '', numToKeepStr: '10')),
            pipelineTriggers([cron('''# Saturdays
                                        H H * * 6''')])])

def jobs = [:]

timeout(time: 6, unit: 'HOURS') {
    timestamps {
        node(SETUP_LABEL) {
            try{
                def gitConfig = scm.getUserRemoteConfigs().get(0)
                def remoteConfigParameters = [url: "${gitConfig.getUrl()}"]

                if (gitConfig.getCredentialsId()) {
                    remoteConfigParameters.put("credentialsId", "${gitConfig.getCredentialsId()}")
                }

                checkout changelog: false,
                        poll: false,
                        scm: [$class: 'GitSCM',
                        branches: [[name: scm.branches[0].name]],
                        doGenerateSubmoduleConfigurations: false,
                        extensions: [[$class: 'CloneOption',
                                      reference: "${HOME}/openjdk_cache"]],
                        submoduleCfg: [],
                        userRemoteConfigs: [remoteConfigParameters]]

                def variableFile = load 'buildenv/jenkins/common/variables-functions.groovy'
                variableFile.parse_variables_file()
                variableFile.set_user_credentials()

                def buildNodes = jenkins.model.Jenkins.instance.getLabel(LABEL).getNodes()
                def slaveNodes = []
                def setupNodesNames = []
                def buildNodesNames = []

                // Update IBM repos cache on slaves
                for (aNode in buildNodes) {
                    if (aNode.toComputer().isOffline()) {
                        // skip offline slave
                        continue
                    }

                    def nodeName = aNode.getDisplayName()
                    buildNodesNames.add(nodeName)

                    def osLabels = ['sw.os.aix', 'sw.os.linux', 'sw.os.osx', 'sw.os.windows']
                    def foundLabel = false
                    def nodeLabels = aNode.getLabelString().tokenize(' ')
                    for (osLabel in osLabels) {
                        if (nodeLabels.contains(osLabel)) {
                            foundLabel = true
                            break
                        }
                    }

                    def repos = []
                    if (jenkins.model.Jenkins.instance.getLabel(SETUP_LABEL).getNodes().contains(aNode)) {
                        // add OpenJ9 repo
                        repos.addAll(EXTENSIONS_REPOS)
                        setupNodesNames.add(aNode)
                    }

                    jobs["${nodeName}"] = {
                        node("${nodeName}"){
                            stage("${nodeName} - Update Reference Repo") {
                                refresh(nodeName, "${HOME}/openjdk_cache", repos, foundLabel)
                            }
                        }
                    }
                }

                echo "Setup nodes: ${setupNodesNames.toString()}"
                echo "Build nodes: ${buildNodesNames.toString()}"

            } finally {
                cleanWs()
            }
        }

        parallel jobs
    }
}

/*
* Creates and updates the git reference repository cache on the node.
*/
def refresh(node, cacheDir, repos, isKnownOs) {
    if (CLEAN_CACHE_DIR) {
        sh "rm -fr ${cacheDir}"
    }

    dir("${cacheDir}") {
        stage("${node} - Config") {
            sh "git init --bare"
            repos.each { repo ->
                config(repo.name, repo.url)
            }
        }
        stage("${node} - Fetch") {
            if (params.USER_CREDENTIALS_ID && isKnownOs) {
                 sshagent(credentials:["${params.USER_CREDENTIALS_ID}"]) {
                    sh 'git fetch --all'
                }
            } else {
                sh "git fetch --all"
            }
        }
        stage("${node} - GC Repo") {
            sh "git gc --aggressive --prune=all"
        }
    }
}

/*
* Add a git remote.
*/
def config(remoteName, remoteUrl) {
    sh "git config remote.${remoteName}.url ${remoteUrl}"
    sh "git config remote.${remoteName}.fetch +refs/heads/*:refs/remotes/${remoteName}/*"
}
