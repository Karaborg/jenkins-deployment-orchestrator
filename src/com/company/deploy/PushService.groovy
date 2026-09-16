package com.company.deploy

class PushService implements Serializable {

    private final def script
    private final PipelineConfig config
    private final ServiceResolver serviceResolver
    private final JenkinsClient jenkinsClient

    PushService(
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

    void execute(
            def params,
            Map environmentConfig
    ) {

        if (!environmentConfig.imagePush) {
            return
        }

        def pushConfig = config.pushJobs[environmentConfig.type]

        executePush(params, pushConfig, 'Push Image')
    }

    private void executePush(
            def params,
            Map pushConfig,
            String phaseName
    ) {

        if (!pushConfig) {
            script.error(
                    "No ${phaseName} configuration defined for the selected environment."
            )
        }

        def selectedServices =
                serviceResolver.selectedServices(params)

        if (!selectedServices) {
            script.error(
                    "${phaseName} için en az bir " +
                            "microservice seçilmelidir."
            )
        }

        def servicesByTag = selectedServices.groupBy { service ->
            params.IMAGE_TAGS?.get(service)
        }

        servicesByTag.each { imageTag, services ->

            if (!imageTag) {
                script.error("${phaseName} için otomatik image tag oluşturulamadı.")
            }

            def imageNames = services
                    .collect { serviceResolver.imageName(it) }
                    .findAll { it }
                    .unique()
                    .join(',')

            script.echo "========================================"
            script.echo phaseName
            script.echo "Environment : ${pushConfig.parameters.environment}"
            script.echo "Services    : ${imageNames}"
            script.echo "Image Tag   : ${imageTag}"
            script.echo "Remote Job  : ${pushConfig.job}"
            script.echo "========================================"

            def parameterNames = pushConfig.parameterNames ?: [:]
            def remoteParameters = [
                    (parameterNames.environment ?: 'ENVIRONMENT'):
                            pushConfig.parameters.environment,
                    (parameterNames.services ?: 'SERVICES'):
                            imageNames,
                    (parameterNames.imageTag ?: 'HARBOR_IMAGE_TAG'):
                            imageTag
            ]

            jenkinsClient.triggerAndWait(
                    pushConfig.jenkinsDns,
                    pushConfig.job,
                    pushConfig.credentialId,
                    remoteParameters,
                    "${phaseName} (${imageTag})"
            )
        }
    }
}
