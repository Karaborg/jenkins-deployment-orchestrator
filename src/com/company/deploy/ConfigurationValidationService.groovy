package com.company.deploy

class ConfigurationValidationService implements Serializable {

    private final def script
    private final PipelineConfig config

    ConfigurationValidationService(
            def script,
            PipelineConfig config
    ) {
        this.script = script
        this.config = config
    }

    void validate(
            def params,
            Map environmentConfig,
            List selectedServices
    ) {

        def errors = []
        def environment = environmentConfig.name
        def buildType = environmentConfig.buildType
        def buildConfig = config.buildJobs[buildType]

        if (!environmentConfig.implemented && !params.DRY_RUN) {
            errors << "${environment} environment henüz implement edilmedi; deployment başlatılamaz"
        }

        if (!buildConfig) {
            errors << "Build type tanımlı değil: ${buildType}"
        } else {
            required(
                    errors,
                    buildConfig.jenkinsDns,
                    "${buildType} build Jenkins DNS"
            )
            required(
                    errors,
                    buildConfig.credentialId,
                    "${buildType} build credentialId"
            )

            servicesRequiringBuild(environmentConfig, selectedServices).each { service ->
                def serviceBuildConfig = buildConfig[service]

                if (!serviceBuildConfig?.job) {
                    errors << "${service} için ${buildType} build job tanımlı değil"
                }
            }
        }

        validateCommonBuilds(params, errors)
        validateBranches(params, selectedServices, errors)

        if (environmentConfig.type == 'on-prem') {
            validateOnPrem(environment, selectedServices, errors)
        } else {
            validateCloud(
                    environment,
                    environmentConfig,
                    selectedServices,
                    errors
            )
        }

        if (errors) {
            script.error(
                    'Deployment configuration validation failed:\n - ' +
                            errors.join('\n - ')
            )
        }

        script.echo('Deployment configuration validation passed.')
    }

    private void validateCommonBuilds(def params, List errors) {

        if (!isCommonBuild(params) && !isCommonBuildWithoutUnitTest(params)) {
            return
        }

        def commonConfig = config.buildJobs.common

        required(errors, commonConfig?.jenkinsDns, 'Common MS Jenkins DNS')
        required(errors, commonConfig?.credentialId, 'Common MS credentialId')

        def jobConfig = isCommonBuildWithoutUnitTest(params) ?
                commonConfig?.commonMsWithoutUnitTest : commonConfig?.commonMs

        required(errors, jobConfig?.job, 'Common MS build job')
    }

    /*
     * On-prem frontend artifacts are built by their dedicated deployment jobs.
     * They intentionally have no entry under the on-prem build job configuration.
     */
    private List servicesRequiringBuild(
            Map environmentConfig,
            List selectedServices
    ) {

        if (environmentConfig.type != 'on-prem') {
            return selectedServices
        }

        return selectedServices.findAll { service ->
            !isFrontend(service)
        }
    }

    private void validateOnPrem(
            String environment,
            List selectedServices,
            List errors
    ) {

        def deployConfig = config.deployJobs[environment]

        if (!deployConfig) {
            errors << "${environment} için on-prem deploy job tanımı yok"
            return
        }

        required(errors, deployConfig.jenkinsDns, "${environment} on-prem deploy Jenkins DNS")
        required(errors, deployConfig.credentialId, "${environment} on-prem deploy credentialId")

        selectedServices.each { service ->
            def deploymentService = deploymentConfigKey(service)
            required(
                    errors,
                    deployConfig[deploymentService],
                    "${service} için ${environment} on-prem deploy job"
            )
        }
    }

    private void validateCloud(
            String environment,
            Map environmentConfig,
            List selectedServices,
            List errors
    ) {

        def helmEnvironment = config.helm.environments?.get(environment)

        if (!helmEnvironment) {
            errors << "${environment} için Helm environment tanımı yok"
        } else {
            def repositoryName = helmEnvironment.repository
            required(errors, repositoryName, "${environment} Helm repository adı")

            def repositoryConfig = config.helm.repositories?.get(repositoryName)
            required(errors, repositoryConfig?.repo, "${repositoryName} Helm repository URL")
            required(errors, repositoryConfig?.credentialId, "${repositoryName} Helm credentialId")
            required(errors, helmEnvironment.branch, "${environment} Helm branch")

            selectedServices.each { service ->
                def serviceConfig = helmEnvironment.services?.get(
                        deploymentConfigKey(service)
                )
                required(errors, serviceConfig?.chartPath, "${service} Helm chartPath")
                required(errors, serviceConfig?.valuesFile, "${service} Helm valuesFile")
            }
        }

        def argocdEnvironment = config.argocd.environments?.get(environment)

        if (!argocdEnvironment) {
            errors << "${environment} için ArgoCD environment tanımı yok"
        } else {
            def credentialName = argocdEnvironment.credential
            required(errors, credentialName, "${environment} ArgoCD credential adı")

            def credentialConfig = config.argocd.credentials?.get(credentialName)
            required(errors, credentialConfig?.server, "${credentialName} ArgoCD server")
            required(errors, credentialConfig?.credentialId, "${credentialName} ArgoCD credentialId")

            selectedServices.each { service ->
                def deploymentService = deploymentConfigKey(service)
                required(
                        errors,
                        argocdEnvironment.services?.get(deploymentService)?.application,
                        "${service} ArgoCD application"
                )
            }
        }

        if (environmentConfig.imagePush) {
            def pushConfig = config.pushJobs[environmentConfig.type]

            required(errors, pushConfig?.job, "${environmentConfig.type} image push job")
            required(errors, pushConfig?.jenkinsDns, "${environmentConfig.type} image push Jenkins DNS")
            required(errors, pushConfig?.credentialId, "${environmentConfig.type} image push credentialId")
            required(errors, pushConfig?.parameters?.environment, "${environmentConfig.type} image push environment")
        }
    }

    private void validateBranches(
            def params,
            List selectedServices,
            List errors
    ) {

        def frontendSelected = selectedServices.any {
            isFrontend(it)
        }

        def backendSelected = selectedServices.any {
            !isFrontend(it)
        }

        if (frontendSelected) {
            required(errors, params.FE_BRANCH_NAME, 'FE_BRANCH_NAME')
        }

        if (backendSelected || isCommonBuild(params) ||
                isCommonBuildWithoutUnitTest(params)) {
            required(errors, params.BE_BRANCH_NAME, 'BE_BRANCH_NAME')
        }
    }

    private void required(
            List errors,
            def value,
            String label
    ) {

        if (!value?.toString()?.trim()) {
            errors << "Eksik tanım: ${label}"
        }
    }

    private String deploymentConfigKey(String service) {
        return config.services[service]?.deploymentKey ?: service
    }

    private boolean isFrontend(String service) {
        return config.services[service]?.type == 'frontend'
    }

    private static boolean isCommonBuild(def params) {
        return params.COMMON_BUILD == 'COMMON_MS' || params.COMMON_MS == true
    }

    private static boolean isCommonBuildWithoutUnitTest(def params) {
        return params.COMMON_BUILD == 'COMMON_MS_WITHOUT_UNIT_TEST' ||
                params.COMMON_MS_WITHOUT_UNIT_TEST == true
    }
}
