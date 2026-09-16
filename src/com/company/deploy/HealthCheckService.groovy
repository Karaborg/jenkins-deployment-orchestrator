package com.company.deploy

class HealthCheckService implements Serializable {

    private final def script
    private final PipelineConfig config
    private final ServiceResolver serviceResolver

    HealthCheckService(
            def script,
            PipelineConfig config,
            ServiceResolver serviceResolver
    ) {
        this.script = script
        this.config = config
        this.serviceResolver = serviceResolver
    }

    Map check(
            def params,
            Map environmentConfig,
            List services = null
    ) {

        def environment =
                environmentConfig.name

        def environmentType =
                environmentConfig.type

        def selectedServices = services ?: serviceResolver.selectedServices(params)

        if (environmentType == 'on-prem') {

            script.echo(
                    "On-Prem health check henüz implement edilmedi."
            )

            return [:]
        }

        return new ArgoCdHealthChecker(
                script,
                config
        ).check(
                environment,
                selectedServices
        )
    }
}
