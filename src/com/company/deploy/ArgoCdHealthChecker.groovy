package com.company.deploy

import groovy.json.JsonSlurperClassic

class ArgoCdHealthChecker implements Serializable {

    private final def script
    private final PipelineConfig config

    private static final int TIMEOUT_SECONDS = 120
    private static final int POLL_INTERVAL_SECONDS = 10

    ArgoCdHealthChecker(
            def script,
            PipelineConfig config
    ) {
        this.script = script
        this.config = config
    }

    Map check(
            String environment,
            List selectedServices
    ) {

        def argocdEnvironment =
                config.argocd.environments?.get(environment)

        if (!argocdEnvironment) {
            script.error(
                    "No ArgoCD configuration defined " +
                            "for environment: ${environment}"
            )
        }

        def credentialName =
                argocdEnvironment.credential

        def credentialConfig =
                config.argocd.credentials?.get(credentialName)

        if (!credentialConfig) {
            script.error(
                    "No ArgoCD credential configuration defined: " +
                            "${credentialName}"
            )
        }

        def argocdServer =
                credentialConfig.server

        def argocdCredentialId =
                credentialConfig.credentialId

        if (!argocdServer) {
            script.error(
                    "ArgoCD server tanımlanmamış: ${credentialName}"
            )
        }

        if (!argocdCredentialId) {
            script.error(
                    "ArgoCD credentialId tanımlanmamış: ${credentialName}"
            )
        }

        def applications = [:]

        selectedServices.each { service ->

            def serviceConfig =
                    argocdEnvironment.services?.get(
                            deploymentConfigKey(service)
                    )

            if (!serviceConfig) {
                script.error(
                        "${service} için ${environment} " +
                                "ortamında ArgoCD configuration bulunamadı."
                )
            }

            applications[service] =
                    serviceConfig.application
        }

        script.echo ""
        script.echo "========================================"
        script.echo "          HEALTH CHECK"
        script.echo "========================================"
        script.echo "Environment : ${environment}"
        script.echo "Timeout     : ${TIMEOUT_SECONDS}s"
        script.echo "Interval    : ${POLL_INTERVAL_SECONDS}s"
        script.echo "========================================"

        def results = [:]

        applications.each { service, application ->

            results[service] = [
                    application : application,
                    syncStatus  : 'PENDING',
                    healthStatus: 'PENDING',
                    status      : 'PENDING'
            ]
        }

        script.withCredentials([
                script.usernamePassword(
                        credentialsId: argocdCredentialId,
                        usernameVariable: 'ARGOCD_USERNAME',
                        passwordVariable: 'ARGOCD_PASSWORD'
                )
        ]) {

            script.sh """
                argocd login ${argocdServer} \
                    --username "\$ARGOCD_USERNAME" \
                    --password "\$ARGOCD_PASSWORD" \
                    --grpc-web
            """

            long startTime =
                    System.currentTimeMillis()

            while (true) {

                boolean allHealthy = true

                applications.each { service, application ->

                    /*
                     * Daha önce HEALTHY olmuş servisi
                     * tekrar kontrol etmiyoruz.
                     */
                    if (results[service].status == 'HEALTHY') {
                        return
                    }

                    def applicationStatus =
                            getApplicationStatus(
                                    argocdServer,
                                    application
                            )

                    def syncStatus =
                            applicationStatus?.status?.sync?.status
                                    ?: 'Unknown'

                    def healthStatus =
                            applicationStatus?.status?.health?.status
                                    ?: 'Unknown'

                    results[service].syncStatus =
                            syncStatus

                    results[service].healthStatus =
                            healthStatus

                    def serviceHealthy =
                            syncStatus == 'Synced' &&
                                    healthStatus == 'Healthy'

                    if (serviceHealthy) {

                        results[service].status =
                                'HEALTHY'

                    } else {

                        results[service].status =
                                'NOT_READY'

                        allHealthy = false
                    }

                    script.echo(
                            "${service} -> " +
                                    "${application} -> " +
                                    "Sync: ${syncStatus}, " +
                                    "Health: ${healthStatus}"
                    )
                }

                if (allHealthy) {
                    break
                }

                long elapsed =
                        System.currentTimeMillis() - startTime

                if (elapsed >=
                        TIMEOUT_SECONDS * 1000L) {

                    script.echo(
                            "Health check timeout reached."
                    )

                    applications.each { service, application ->

                        if (results[service].status != 'HEALTHY') {

                            results[service].status =
                                    'FAILED'
                        }
                    }

                    break
                }

                script.sleep(
                        time: POLL_INTERVAL_SECONDS,
                        unit: 'SECONDS'
                )
            }
        }

        printSummary(results)

        return results
    }

    private Map getApplicationStatus(
            String argocdServer,
            String application
    ) {

        def output =
                script.sh(
                        script: """
                            argocd app get ${application} \
                                --server ${argocdServer} \
                                --grpc-web \
                                -o json
                        """,
                        returnStdout: true
                ).trim()

        return new JsonSlurperClassic()
                .parseText(output)
    }

    private String deploymentConfigKey(String service) {
        return config.services[service]?.deploymentKey ?: service
    }

    private void printSummary(
            Map results
    ) {

        script.echo ""
        script.echo "========================================"
        script.echo "       HEALTH CHECK SUMMARY"
        script.echo "========================================"

        results.each { service, result ->

            script.echo(
                    "${service} -> " +
                            "${result.application}"
            )

            script.echo(
                    "  Sync   : ${result.syncStatus}"
            )

            script.echo(
                    "  Health : ${result.healthStatus}"
            )

            script.echo(
                    "  Result : ${result.status}"
            )
        }

        script.echo "========================================"
    }
}
