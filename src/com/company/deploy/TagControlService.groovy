package com.company.deploy

class TagControlService implements Serializable {

    private final def script
    private final PipelineConfig config
    private final ServiceResolver serviceResolver

    TagControlService(
            def script,
            PipelineConfig config,
            ServiceResolver serviceResolver
    ) {
        this.script = script
        this.config = config
        this.serviceResolver = serviceResolver
    }

    void check(
            def params,
            Map environmentConfig
    ) {

        def environment =
                environmentConfig.name

        def environmentType =
                environmentConfig.type

        def expectedImageTags =
                params.IMAGE_TAGS ?: [:]

        def selectedServices =
                serviceResolver.selectedServices(params)

        if (environmentType == 'on-prem') {

            script.echo(
                    "On-Prem tag control henüz implement edilmedi."
            )

            return
        }

        new ArgoCdTagChecker(
                script,
                config
        ).check(
                environment,
                selectedServices,
                expectedImageTags
        )
    }
}
