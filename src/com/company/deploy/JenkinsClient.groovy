package com.company.deploy

import groovy.json.JsonSlurperClassic

class JenkinsClient implements Serializable {

    private final def script

    JenkinsClient(def script) {
        this.script = script
    }

    Map triggerAndWait(
            String jenkinsDns,
            String jobName,
            String credentialId,
            Map parameters,
            String name
    ) {

        def buildNumber
        long queueStartMillis = System.currentTimeMillis()
        long buildStartMillis = 0L
        long queueDurationSeconds = 0L
        Map buildDetails = [:]
        def safeName = name.toLowerCase().replaceAll('[^a-z0-9-]', '-')
        def cookieFile = "${script.env.WORKSPACE}/${safeName}-cookies.txt"
        def headersFile = "${script.env.WORKSPACE}/${safeName}-headers.txt"
        def bodyFile = "${script.env.WORKSPACE}/${safeName}-body.txt"

        try {
            script.withCredentials([
                script.usernamePassword(
                        credentialsId: credentialId,
                        usernameVariable: 'JENKINS_USER',
                        passwordVariable: 'JENKINS_TOKEN'
                )
            ]) {

                cleanupTemporaryFiles(cookieFile, headersFile, bodyFile)

            def crumbResponse = script.sh(
                    script: """
                        curl -sS \
                            -c "${cookieFile}" \
                            -u "\$JENKINS_USER:\$JENKINS_TOKEN" \
                            "https://${jenkinsDns}/crumbIssuer/api/json"
                    """,
                    returnStdout: true
            ).trim()

            if (!crumbResponse) {
                script.error(
                        "${name}: Jenkins Crumb response boş."
                )
            }

            def crumbData =
                    new JsonSlurperClassic().parseText(crumbResponse)

            def crumbField =
                    crumbData.crumbRequestField

            def crumb =
                    crumbData.crumb

            def parameterArgs =
                    parameters.collect { key, value ->

                        def escapedValue =
                                value == null
                                        ? ''
                                        : value.toString()

                        "--data-urlencode '${key}=${escapedValue}'"
                    }.join(' \\\n')

            script.sh """
                curl -sS \
                    -D "${headersFile}" \
                    -o "${bodyFile}" \
                    -X POST \
                    -b "${cookieFile}" \
                    -u "\$JENKINS_USER:\$JENKINS_TOKEN" \
                    -H "${crumbField}: ${crumb}" \
                    ${parameterArgs} \
                    "https://${jenkinsDns}/${jobApiPath(jobName)}/buildWithParameters"
            """

            def queueLocation = script.sh(
                    script: """
                        awk 'tolower(\$1) == "location:" {print \$2}' \
                            "${headersFile}" |
                            tr -d '\\r'
                    """,
                    returnStdout: true
            ).trim()

            if (!queueLocation) {

                script.echo "===== REMOTE JENKINS RESPONSE ====="

                script.sh """
                    cat "${headersFile}" || true
                """

                script.sh """
                    cat "${bodyFile}" || true
                """

                script.error(
                        "${name}: Jenkins queue URL alınamadı."
                )
            }

            script.echo "${name} Queue URL: ${queueLocation}"

            def queueId =
                    queueLocation.tokenize('/')[-1]

            script.echo "${name} Queue ID: ${queueId}"

            script.timeout(
                    time: 30,
                    unit: 'MINUTES'
            ) {

                script.waitUntil {

                    def queueResponse = script.sh(
                            script: """
                                curl -sS \
                                    -u "\$JENKINS_USER:\$JENKINS_TOKEN" \
                                    "https://${jenkinsDns}/queue/item/${queueId}/api/json"
                            """,
                            returnStdout: true
                    ).trim()

                    def queueData =
                            new JsonSlurperClassic()
                                    .parseText(queueResponse)

                    if (queueData.cancelled) {
                        script.error(
                                "${name}: Jenkins queue'da iptal edildi."
                        )
                    }

                    if (queueData.executable) {

                        buildNumber =
                                queueData.executable.number as Integer

                        buildStartMillis = System.currentTimeMillis()
                        queueDurationSeconds = Math.round(
                                (buildStartMillis - queueStartMillis) / 1000.0d
                        ) as Long

                        script.echo(
                                "${name} Build Number: ${buildNumber}"
                        )

                        return true
                    }

                    script.echo(
                            "${name} job henüz başlamadı..."
                    )

                    script.sleep(5)

                    return false
                }
            }

            script.timeout(
                    time: 120,
                    unit: 'MINUTES'
            ) {

                script.waitUntil {

                    def buildResponse = script.sh(
                            script: """
                                curl -sS \
                                    -u "\$JENKINS_USER:\$JENKINS_TOKEN" \
                                    "https://${jenkinsDns}/${jobApiPath(jobName)}/${buildNumber}/api/json"
                            """,
                            returnStdout: true
                    ).trim()

                    def buildData =
                            new JsonSlurperClassic()
                                    .parseText(buildResponse)

                    if (buildData.building == true) {

                        script.echo(
                                "${name} #${buildNumber} Status: RUNNING"
                        )

                        script.sleep(10)

                        return false
                    }

                    def result =
                            buildData.result

                    if (!result) {

                        script.echo(
                                "${name} #${buildNumber} Status: UNKNOWN"
                        )

                        script.sleep(10)

                        return false
                    }

                    script.echo(
                            "${name} #${buildNumber} Status: ${result}"
                    )

                    def buildDurationMillis =
                            buildData.duration ?: (
                                    System.currentTimeMillis() - buildStartMillis
                            )

                    buildDetails = [
                            buildNumber         : buildNumber,
                            result              : result,
                            queueDurationSeconds: queueDurationSeconds,
                            buildDurationSeconds: Math.round(
                                    (buildDurationMillis as Long) / 1000.0d
                            ) as Long
                    ]

                    if (result == 'SUCCESS') {
                        script.echo(
                                "${name} #${buildNumber} başarıyla tamamlandı."
                        )
                    } else {
                        script.echo(
                                "${name} başarısız oldu. " +
                                        "Build #${buildNumber}, Result: ${result}"
                        )
                    }

                    return true
                }
            }
            }
        } finally {
            cleanupTemporaryFiles(cookieFile, headersFile, bodyFile)
        }

        return buildDetails
    }

    private void cleanupTemporaryFiles(
            String cookieFile,
            String headersFile,
            String bodyFile
    ) {

        script.sh(
                script: """
                    rm -f "${cookieFile}" "${headersFile}" "${bodyFile}"
                """,
                returnStatus: true
        )
    }

    private static String jobApiPath(String jobName) {

        if (!jobName?.trim()) {
            throw new IllegalArgumentException('Jenkins job name cannot be empty.')
        }

        return jobName
                .tokenize('/')
                .collect { segment -> "job/${segment}" }
                .join('/')
    }
}
