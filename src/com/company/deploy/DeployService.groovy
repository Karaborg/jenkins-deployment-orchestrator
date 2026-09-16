package com.company.deploy

class DeployService implements Serializable {

    private final def script
    private final PipelineConfig config
    private final ServiceResolver serviceResolver
    private final JenkinsClient jenkinsClient
    private static final int DEPLOYMENT_SETTLE_DELAY = 10

    DeployService(
            def script,
            PipelineConfig config,
            ServiceResolver serviceResolver,
            JenkinsClient jenkinsClient
    ) {
        this.script = script
        this.config = config
        this.serviceResolver = serviceResolver
        this.jenkinsClient = jenkinsClient
    }

    Map execute(
            def params,
            Map environmentConfig,
            List syncServices = null
    ) {

        def environment =
                environmentConfig.name

        def environmentType =
                environmentConfig.type

        def selectedServices =
                serviceResolver.selectedServices(params)

        if (environmentType == 'on-prem') {

            return deployOnPrem(
                    params,
                    environment,
                    selectedServices
            )

        } else {

            deployHelm(
                    params,
                    environment,
                    selectedServices,
                    false,
                    syncServices ?: selectedServices
            )

            return [:]
        }
    }

    void validateDryRun(Map environmentConfig, List selectedServices) {

        if (environmentConfig.type == 'on-prem') {
            script.echo(
                    'DRY RUN: On-prem deploy job validation is not applicable.'
            )
            return
        }

        script.echo(
                'DRY RUN: Validating Helm repository, branch and selected chart files.'
        )

        deployHelm(
                [:],
                environmentConfig.name,
                selectedServices,
                true,
                selectedServices
        )
    }

    void syncConfiguration(String environment, List services) {
        syncArgoCd([:], environment, services)
    }

    private Map deployOnPrem(
            def params,
            String environment,
            List selectedServices
    ) {

        def environmentJobs =
                config.deployJobs[environment]

        if (!environmentJobs) {
            script.error(
                    "No deploy jobs defined for environment: ${environment}"
            )
        }

        def jenkinsDns = environmentJobs.jenkinsDns
        def credentialId = environmentJobs.credentialId

        if (!jenkinsDns || !credentialId) {
            script.error(
                    "On-prem deploy Jenkins ayarları bulunamadı: ${environment}"
            )
        }

        def deployJobs = [:]

        selectedServices.each { service ->

            def jobName = environmentJobs[
                    serviceResolver.deploymentConfigKey(service)
            ]

            if (!jobName) {
                script.error(
                        "${service} için ${environment} " +
                                "ortamında deploy job bulunamadı."
                )
            }

            def displayName =
                    serviceResolver.displayName(service) +
                            ' Deploy'

            deployJobs[displayName] = {

                try {
                    return [service: service, result: 'SUCCESS'] +
                            jenkinsClient.triggerAndWait(
                                    jenkinsDns,
                                    jobName,
                                    credentialId,
                                    branchParameters(service, params) + [
                                            REVISION: '0.0.1'
                                    ],
                                    displayName
                            )
                } catch (Exception exception) {
                    script.echo("${service} deploy başarısız: ${exception.message}")
                    return [
                            service: service,
                            result : 'FAILED',
                            error  : exception.message ?: 'Unknown deployment error'
                    ]
                }
            }
        }

        def parallelResults = deployJobs ? script.parallel(deployJobs) as Map : [:]

        return parallelResults.values().collectEntries { deployment ->
            [(deployment.service): deployment]
        }
    }

    /**
     * Deployment jobs may use either the generic or service-specific branch
     * parameter.  Do not leak the backend branch to frontend jobs, or vice
     * versa.
     */
    private Map branchParameters(String service, def params) {

        def frontend = serviceResolver.isFrontend(service)
        def parameterName = frontend ? 'FE_BRANCH_NAME' : 'BE_BRANCH_NAME'
        def branchName = params[parameterName]?.trim()

        if (!branchName) {
            script.error("${service} için ${parameterName} değeri boş olamaz.")
        }

        return [
                (parameterName): branchName,
                BRANCH_NAME    : branchName
        ]
    }

    private void deployHelm(
            def params,
            String environment,
            List selectedServices,
            boolean readOnly,
            List syncServices
    ) {

        def helmEnvironment =
                config.helm.environments?.get(environment)

        if (!helmEnvironment) {
            script.error(
                    "No Helm configuration defined " +
                            "for environment: ${environment}"
            )
        }

        def repositoryName =
                helmEnvironment.repository

        def repositoryConfig =
                config.helm.repositories?.get(repositoryName)

        if (!repositoryConfig) {
            script.error(
                    "No Helm repository defined: ${repositoryName}"
            )
        }

        def helmRepo =
                repositoryConfig.repo

        def helmCredentialId =
                repositoryConfig.credentialId

        if (!helmRepo) {
            script.error(
                    "Helm repository URL tanımlanmamış: ${repositoryName}"
            )
        }

        if (!helmCredentialId) {
            script.error(
                    "Helm repository credentialId tanımlanmamış: ${repositoryName}"
            )
        }

        def helmBranch =
                helmEnvironment.branch

            selectedServices.each { service ->

            def serviceConfig = helmEnvironment.services?.get(
                    serviceResolver.deploymentConfigKey(service)
            )

            if (!serviceConfig) {

                script.error(
                        "${service} için ${environment} " +
                                "ortamında Helm configuration bulunamadı."
                )
            }
        }

        def helmDirectory = "helm-${environment}"

        try {
            script.dir(helmDirectory) {

                script.deleteDir()

                script.git(
                    url: helmRepo,
                    branch: helmBranch,
                    credentialsId: helmCredentialId
            )

                selectedServices.each { service ->

                def helmConfig = helmEnvironment.services[
                        serviceResolver.deploymentConfigKey(service)
                ]

                if (readOnly && !script.fileExists(helmConfig.chartPath)) {
                    script.error(
                            "${service} için Helm chart path bulunamadı: " +
                                    helmConfig.chartPath
                    )
                }

                script.dir(helmConfig.chartPath) {

                    if (readOnly) {
                        validateHelmFiles(service, helmConfig)
                    } else {
                        def imageTag =
                                params.IMAGE_TAGS?.get(service)

                        if (!imageTag) {
                            script.error(
                                    "${service} için otomatik image tag oluşturulamadı."
                            )
                        }

                        updateHelm(
                                service,
                                environment,
                                helmConfig,
                                imageTag
                        )
                    }
                }
            }

            if (!readOnly) {
                script.withCredentials([
                        script.gitUsernamePassword(
                                credentialsId: helmCredentialId,
                                gitToolName: 'git'
                        )
                ]) {

                    script.sh """
                        git add -A

                        git commit \
                            -m "updated image tags" \
                            || true

                        git push origin ${helmBranch}
                    """
                }
            }
            }
        } finally {
            script.dir(helmDirectory) {
                script.deleteDir()
            }
        }

        if (!readOnly) {
            syncArgoCd(
                    params,
                    environment,
                    syncServices
            )
        }
    }

    private void validateHelmFiles(String service, Map helmConfig) {

        if (script.fileExists(helmConfig.valuesFile)) {
            script.echo(
                    "DRY RUN: ${service} values file verified: " +
                            helmConfig.valuesFile
            )
            return
        }

        if (script.fileExists('Chart.yaml')) {
            script.echo(
                    "DRY RUN: ${service} values file is absent; " +
                            'Chart.yaml fallback verified.'
            )
            return
        }

        script.error(
                "${service} için ${helmConfig.valuesFile} veya Chart.yaml bulunamadı."
        )
    }

    private void updateHelm(
            String service,
            String environment,
            Map helmConfig,
            String imageTag
    ) {

        script.sh """
            echo "========================================"
            echo "Updating ${service}"
            echo "Environment : ${environment}"
            echo "Repository  : ${helmConfig.repo ?: ''}"
            echo "Branch      : ${helmConfig.branch ?: ''}"
            echo "========================================"

            VALUES_FILE="${helmConfig.valuesFile}"

            if [ -f "\$VALUES_FILE" ]; then

                IMAGE_TAG_VALUE=\$(grep -E '^[[:space:]]*tag:[[:space:]]*".*"[[:space:]]*\$' "\$VALUES_FILE" | head -1 | sed -E 's/^[[:space:]]*tag:[[:space:]]*"([^"]*)".*/\\1/')

                if [ -n "\$IMAGE_TAG_VALUE" ]; then

                    sed -i -E '0,/^[[:space:]]*tag:[[:space:]]*".*"[[:space:]]*\$/s//  tag: "${imageTag}"/' "\$VALUES_FILE"

                else

                    sed -i 's/^appVersion:.*/appVersion: "${imageTag}"/' Chart.yaml

                fi

            else

                sed -i 's/^appVersion:.*/appVersion: "${imageTag}"/' Chart.yaml

            fi

            if [ -f "\$VALUES_FILE" ]; then
                echo "Updated \$VALUES_FILE:"
            fi
        """
    }

    private void syncArgoCd(
            def params,
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

        def syncJobs = [:]

        selectedServices.each { service ->

            def argocdConfig = argocdEnvironment.services?.get(
                    serviceResolver.deploymentConfigKey(service)
            )

            if (!argocdConfig) {
                script.error(
                        "${service} için ${environment} " +
                                "ortamında ArgoCD configuration bulunamadı."
                )
            }

            syncJobs["${service} ArgoCD Sync"] = {

                script.withCredentials([
                        script.usernamePassword(
                                credentialsId:
                                        argocdCredentialId,
                                usernameVariable:
                                        'ARGOCD_USERNAME',
                                passwordVariable:
                                        'ARGOCD_PASSWORD'
                        )
                ]) {

                    script.sh """
                        argocd login ${argocdServer} \
                            --username "\$ARGOCD_USERNAME" \
                            --password "\$ARGOCD_PASSWORD" \
                            --grpc-web

                        argocd app get ${argocdConfig.application} \
                            --server ${argocdServer} \
                            --hard-refresh \
                            --grpc-web

                        argocd app sync ${argocdConfig.application} \
                            --server ${argocdServer} \
                            --grpc-web

                        sleep ${DEPLOYMENT_SETTLE_DELAY}

                        argocd app get ${argocdConfig.application} \
                            --server ${argocdServer} \
                            --refresh \
                            --grpc-web
                    """
                }
            }
        }

        if (syncJobs) {
            script.parallel(syncJobs)
        }
    }
}
