package com.company.deploy

class ArgoCdTagChecker implements Serializable {

    private static final int MAX_TAG_CHECK_ATTEMPTS = 3
    private static final int TAG_CHECK_RETRY_DELAY_SECONDS = 5

    private final def script
    private final PipelineConfig config

    ArgoCdTagChecker(
            def script,
            PipelineConfig config
    ) {
        this.script = script
        this.config = config
    }

    void check(
            String environment,
            List selectedServices,
            Map expectedImageTags,
            int attempt = 1
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
        script.echo "          TAG CONTROL"
        script.echo "========================================"
        script.echo "Attempt      : ${attempt}/${MAX_TAG_CHECK_ATTEMPTS}"
        script.echo "Environment  : ${environment}"
        script.echo "Expected Tags: ${expectedImageTags}"
        script.echo "========================================"

        def results = [:]

        applications.each { service, application ->

            results[service] = [
                    application: application,
                    imageTag   : 'UNKNOWN',
                    status     : 'PENDING'
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

            applications.each { service, application ->

                def actualImageTag =
                        getDeploymentImageTag(
                                argocdServer,
                                application
                        )

                results[service].imageTag =
                        actualImageTag

                def expectedImageTag = expectedImageTags[service]

                if (actualImageTag == expectedImageTag) {

                    results[service].status =
                            'MATCH'

                } else {

                    results[service].status =
                            'MISMATCH'
                }

                script.echo(
                        "${service} -> " +
                                "${application} -> " +
                                "Actual: ${actualImageTag}, " +
                                "Expected: ${expectedImageTag}, " +
                                "Result: ${results[service].status}"
                )
            }
        }

        printSummary(
                results,
                expectedImageTags
        )

        def mismatchedServices =
                results.findAll {
                    service, result ->
                        result.status == 'MISMATCH'
                }

        if (mismatchedServices) {

            if (attempt < MAX_TAG_CHECK_ATTEMPTS) {
                script.echo(
                        'ArgoCD yeni image bilgisini henüz yansıtmamış olabilir. ' +
                                "${TAG_CHECK_RETRY_DELAY_SECONDS} saniye sonra tekrar kontrol edilecek."
                )

                script.sleep(
                        time: TAG_CHECK_RETRY_DELAY_SECONDS,
                        unit: 'SECONDS'
                )

                check(
                        environment,
                        selectedServices,
                        expectedImageTags,
                        attempt + 1
                )

                return
            }

            def mismatchNames =
                    mismatchedServices.collect {
                        service, result ->
                            "${service} (${result.application})"
                    }

            script.error(
                    "Image tag mismatch detected for: " +
                            mismatchNames.join(', ')
            )
        }

        script.echo(
                "All selected services have the expected image tag."
        )
    }

    private String getDeploymentImageTag(
            String argocdServer,
            String application
    ) {

        def output =
                script.sh(
                        script: """
                            argocd app manifests ${application} \
                                --server ${argocdServer} \
                                --source live \
                                --grpc-web
                        """,
                        returnStdout: true
                ).trim()

        def imageLine = output.readLines().find { line ->
            line.trim().startsWith('image:')
        }

        def image = imageLine
                ?.trim()
                ?.substring('image:'.length())
                ?.trim()
                ?.replace('"', '')
                ?.replace("'", '')

        if (!image || !image.contains(':')) {
            return 'UNKNOWN'
        }

        return image.substring(
                image.lastIndexOf(':') + 1
        )
    }

    private String deploymentConfigKey(String service) {
        return config.services[service]?.deploymentKey ?: service
    }

    private void printSummary(
            Map results,
            Map expectedImageTags
    ) {

        script.echo ""
        script.echo "========================================"
        script.echo "       TAG CONTROL SUMMARY"
        script.echo "========================================"
        script.echo "Expected Tags: ${expectedImageTags}"
        script.echo "----------------------------------------"

        results.each { service, result ->

            script.echo(
                    "${service} -> " +
                            "${result.application}"
            )

            script.echo(
                    "  Actual : ${result.imageTag}"
            )

            script.echo(
                    "  Result : ${result.status}"
            )
        }

        script.echo "========================================"
    }
}
