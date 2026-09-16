package com.company.deploy

class PromotionService implements Serializable {

    private final def script
    private final PipelineConfig config
    private final ServiceResolver serviceResolver
    private final JenkinsClient jenkinsClient

    PromotionService(
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

        if (!environmentConfig.ecrPromotion) {
            return
        }

        def promotionConfigKey = environmentConfig.promotionConfig

        def promotionConfig =
                config.promotionJobs[promotionConfigKey]

        if (!promotionConfig) {
            script.error(
                    "No ECR Promotion configuration defined " +
                            "for environment: ${promotionConfigKey}"
            )
        }

        def selectedServices = serviceResolver.selectedServices(params)

        if (!selectedServices) {
            script.error(
                    "ECR Promotion için en az bir " +
                            "microservice seçilmelidir."
            )
        }

        selectedServices.groupBy { service ->
            params.IMAGE_TAGS?.get(service)
        }.each { imageTag, services ->
            if (!imageTag) {
                script.error('ECR Promotion için otomatik image tag oluşturulamadı.')
            }

            def imageNames = services
                    .collect { serviceResolver.imageName(it) }
                    .findAll { it }
                    .unique()
                    .join(',')

            script.echo "========================================"
            script.echo "ECR Promotion"
            script.echo "Source Type : ${promotionConfig.parameters.sourceType}"
            script.echo "Target Type : ${promotionConfig.parameters.targetType}"
            script.echo "Services    : ${imageNames}"
            script.echo "Source Tag  : ${imageTag}"
            script.echo "Target Tag  : ${imageTag}"
            script.echo "Job         : ${promotionConfig.job}"
            script.echo "========================================"

            def parameterNames = promotionConfig.parameterNames ?: [:]
            def remoteParameters = [
                    (parameterNames.sourceType ?: 'SOURCE_TYPE'):
                            promotionConfig.parameters.sourceType,
                    (parameterNames.targetType ?: 'TARGET_TYPE'):
                            promotionConfig.parameters.targetType,
                    (parameterNames.services ?: 'SERVICES'):
                            imageNames,
                    (parameterNames.sourceImageTag ?: 'SOURCE_IMAGE_TAG'):
                            imageTag,
                    (parameterNames.targetImageTag ?: 'TARGET_IMAGE_TAG'):
                            imageTag
            ]

            jenkinsClient.triggerAndWait(
                    promotionConfig.jenkinsDns,
                    promotionConfig.job,
                    promotionConfig.credentialId,
                    remoteParameters,
                    "ECR Promotion (${imageTag})"
            )
        }
    }
}
