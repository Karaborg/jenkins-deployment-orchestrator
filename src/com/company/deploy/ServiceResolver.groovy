package com.company.deploy

class ServiceResolver implements Serializable {

    private final Map services
    private final Set backendServices
    private final Set frontendServices

    ServiceResolver(Map services) {
        this.services = services ?: [:]
        this.backendServices = this.services.findAll { _, config ->
            config.type != 'frontend'
        }.keySet() as Set
        this.frontendServices = this.services.findAll { _, config ->
            config.type == 'frontend'
        }.keySet() as Set
    }

    List selectedServices(def params) {

        Set selectedBackends = selectedBackendServices(params)
        def selectedFrontend = selectedFrontendService(params)

        return services.keySet().findAll { service ->
            backendServices.contains(service) ?
                    selectedBackends.contains(service) :
                    service == selectedFrontend
        }
    }

    private Set selectedBackendServices(def params) {

        def selected = params.BACKEND_SERVICES

        if (selected instanceof Collection) {
            return selected.collect { it.toString().trim() }
                    .findAll { it in backendServices } as Set
        }

        if (selected?.toString()?.trim()) {
            return selected.toString().split(/[\n,]/)
                    .collect { it.trim() }
                    .findAll { it in backendServices } as Set
        }

        // Active Choices unavailable/replay from an older build: retain boolean fallback.
        return backendServices.findAll { params[it] == true } as Set
    }

    private String selectedFrontendService(def params) {

        def selected = params.FRONTEND_SERVICE?.toString()?.trim()

        if (selected in frontendServices) {
            return selected
        }

        if (selected) {
            return ''
        }

        // Retain compatibility with builds created before FRONTEND_SERVICE.
        return frontendServices.find { params[it] == true } ?: ''
    }

    String getImageNames(def params) {

        return selectedServices(params)
                .collect { service ->
                    services[service]?.imageName
                }
                .findAll { it }
                .unique()
                .join(',')
    }

    String displayName(String service) {

        return services[service]?.displayName ?: service
    }

    String imageName(String service) {
        return services[service]?.imageName
    }

    String deploymentConfigKey(String service) {
        return services[service]?.deploymentKey ?: service
    }

    boolean isFrontend(String service) {
        return service in frontendServices
    }

    List getBackendServices() { return backendServices as List }
    List getFrontendServices() { return frontendServices as List }
}
