/*******************************************************************************
 * Copyright (c) 2018, 2019 IBM Corp. and others
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
import hudson.slaves.OfflineCause

defaultSetupLabel = 'worker'
defaultLabel = 'ci.role.build || ci.role.test'
defaultMode = 'cleanup'
defaultTime = '12'
defaultUnits = 'HOURS'

SETUP_LABEL = params.SETUP_LABEL
if (!SETUP_LABEL) {
    SETUP_LABEL = defaultSetupLabel
}

LABEL = params.LABEL
if (!LABEL) {
    LABEL = defaultLabel
}

// expected MODE: cleanup | sanitize | all
MODES = []
if (!params.MODE) {
    MODES.add(defaultMode)
} else if (params.MODE.equals('all')) {
    MODES.addAll(['cleanup', 'sanitize'])
} else {
    MODES = params.MODE.trim().replaceAll("\\s","").tokenize(',')
}

TIMEOUT_TIME = params.TIMEOUT_TIME
if (!TIMEOUT_TIME) {
    TIMEOUT_TIME = defaultTime
}

TIMEOUT_UNITS = params.TIMEOUT_UNITS
if (!TIMEOUT_UNITS) {
    TIMEOUT_UNITS = defaultUnits
} else {
    TIMEOUT_UNITS = TIMEOUT_UNITS.toUpperCase()
}

SLACK_CHANNEL = params.SLACK_CHANNEL

jobs = [:]
offlineSlaves = [:]
buildNodes = []

timeout(time: TIMEOUT_TIME.toInteger(), unit: TIMEOUT_UNITS) {
    timestamps {
        node(SETUP_LABEL) {
            try {
                for (aNode in jenkins.model.Jenkins.instance.getLabel(LABEL).getNodes()) {
                    def nodeName = aNode.getDisplayName()

                    if (aNode.toComputer().isOffline()) {
                        // skip offline slave
                        def offlineCause = aNode.toComputer().getOfflineCause()
                        if (offlineCause instanceof OfflineCause.UserCause) {
                            // skip offline node disconnected by users
                            offlineSlaves.put(nodeName, offlineCause.toString())
                        } else {
                            // cache nodes, will attempt to reconnect nodes disconnected by system later
                            buildNodes.add(nodeName)
                        }
                        continue
                    }

                    def nodeLabels = []
                    if (aNode.getLabelString()) {
                        nodeLabels.addAll(aNode.getLabelString().tokenize(' '))
                    }

                    buildNodes.add(nodeName)

                    // cache job
                    jobs["${nodeName}"] = {
                        node("${nodeName}") {
                            // Sanitize first to catch any dangling processes.
                            if (MODES.contains('sanitize')) {
                                stage("${nodeName} - Sanitize slave") {
                                    sanitize_node(nodeName)
                                }
                            }

                            if (MODES.contains('cleanup')) {
                                stage("${nodeName} - Cleanup Workspaces") {
                                    def buildWorkspace = "${env.WORKSPACE}"
                                    if (nodeLabels.contains('sw.os.windows')) {
                                        // convert windows path to unix path
                                        buildWorkspace = sh(script: "cygpath -u '${env.WORKSPACE}'", returnStdout: true).trim()
                                    }

                                    sh 'find /tmp -maxdepth 1 -user $USER'

                                    // Cleanup OSX shared memory and content in /cores
                                    if (nodeLabels.contains('sw.os.osx')) {
                                        retry(2) {
                                            sh """
                                                ipcs -ma
                                                ipcs -ma | awk '/^m / { if (\$9 == 0) { print \$2 }}' | xargs -n 1 ipcrm -m
                                                ipcs -ma
                                                du -sh /cores
                                                ls -al /cores
                                                find /tmp -maxdepth 1 -user $USER
                                                du -sh /cores
                                            """
                                        }
                                    }

                                    // Clean up defunct pipelines workspaces
                                    def retStatus = 0
                                    retry(3) {
                                        if (retStatus != 0) {
                                            sleep(time: SLEEP_TIME.toInteger(), unit: 'SECONDS')
                                        }

                                        retStatus = sh script: "find `cd ../ && pwd` -maxdepth 1 -path `pwd` -o -prune -print", returnStatus: true
                                    }
                                }
                            }
                        }
                    }
                }

                if (offlineSlaves) {
                    println("Offline slaves: ${offlineSlaves.toString()}")
                }

            } catch(e) {
                if (SLACK_CHANNEL) {
                    slackSend channel: SLACK_CHANNEL, color: 'danger', message: "Failed: ${env.JOB_NAME} #${env.BUILD_NUMBER} (<${env.BUILD_URL}|Open>)"
                }
                throw e
            } finally {
                cleanWs()
            }
        }

        try {
            parallel jobs
        } finally {
            if (MODES.contains('sanitize')) {
                def offlineNodes = []
                for (label in buildNodes.sort()) {
                    if (jenkins.model.Jenkins.instance.getNode(label)) {
                        def aComputer = jenkins.model.Jenkins.instance.getNode(label).toComputer()

                        if (aComputer.isOffline() && !(aComputer.getOfflineCause() instanceof OfflineCause.UserCause)) {
                            // reconnect slave (asynchronously)
                            println("${label}: Reconnecting...")
                            aComputer.connect(true)

                            if (aComputer.isOffline()) {
                                echo "Node: ${JENKINS_URL}${aComputer.getUrl()} - Status: offline - Cause: ${aComputer.getOfflineCause().toString()}"
                                offlineNodes.add("<${JENKINS_URL}${aComputer.getUrl()}|${aComputer.getDisplayName()}>")
                            } else {
                                println("${label} is back online: ${aComputer.isOnline()}")
                            }
                        }
                    }
                }

                if (!offlineNodes.isEmpty() && SLACK_CHANNEL) {
                    slackSend channel: SLACK_CHANNEL, color: 'warning', message: "${env.JOB_NAME} #${env.BUILD_NUMBER} (<${env.BUILD_URL}|Open>) left nodes offline: ${offlineNodes.join(',')}"
                }
            }
        }
    }
}

/*
* Kill all processes and reconnect a Jenkins node
*/
def sanitize_node(nodeName) {
    def workingNode = jenkins.model.Jenkins.instance.getNode(nodeName)
    def workingComputer = workingNode.toComputer()

    workingComputer.setTemporarilyOffline(true, null)
    try {
        println("\t ${nodeName}: Killing all owned processes...")
        sh "ps -fu ${env.USER} | grep [j]ava | egrep -v 'slave.jar|remoting.jar'"
    } catch(e) {
        println(e.getMessage())
    }
}
