package com.company.deploy

class PipelineConfig implements Serializable {

    private final def script

    private Map environments
    private Map buildJobs
    private Map deployJobs
    private Map pushJobs
    private Map promotionJobs
    private Map helm
    private Map argocd
    private Map configuration
    private Map services

    PipelineConfig(def script) {
        this.script = script
    }

    void load() {
        environments = loadYaml('deployment/environments.yml')
        buildJobs = loadYaml('deployment/build-jobs.yml')
        deployJobs = loadYaml('deployment/deploy-jobs.yml')
        pushJobs = loadYaml('deployment/push-jobs.yml')
        promotionJobs = loadYaml('deployment/promotion-jobs.yml')
        helm = loadYaml('deployment/helm.yml')
        argocd = loadYaml('deployment/argocd.yml')
        configuration = loadYaml('deployment/configuration.yml')
        services = loadYaml('deployment/services.yml')
    }

    Map getEnvironments() {
        return environments?.environments ?: [:]
    }

    Map getBuildJobs() {
        return buildJobs?.buildTypes ?: [:]
    }

    Map getDeployJobs() {
        return deployJobs?.environments ?: [:]
    }

    Map getPushJobs() {
        return pushJobs?.environments ?: [:]
    }

    Map getPromotionJobs() {
        return promotionJobs?.environments ?: [:]
    }


    Map getHelm() {
        return helm ?: [:]
    }

    Map getArgocd() {
        return argocd ?: [:]
    }

    Map getConfiguration() {
        return configuration ?: [:]
    }

    Map getServices() {
        return services?.services ?: [:]
    }

    private Map loadYaml(String path) {

        def content = script.libraryResource(path)

        if (!content) {
            throw new IllegalStateException(
                    "Library resource bulunamadı: ${path}"
            )
        }

        return script.readYaml(text: content) as Map
    }
}
