package com.company.deploy

class EnvironmentResolver implements Serializable {

    private final Map environments

    EnvironmentResolver(Map environments) {
        this.environments = environments ?: [:]
    }

    Map resolve(String environment) {

        def normalized = environment?.toLowerCase()

        if (!normalized) {
            throw new IllegalArgumentException(
                    'Environment boş olamaz.'
            )
        }

        def config = environments[normalized]

        if (!config) {
            throw new IllegalArgumentException(
                    "Unknown environment: ${environment}"
            )
        }

        return [
                name     : normalized,
                type     : config.type,
                dns      : config.dns,
                buildType: config.buildType,
                imagePush: config.imagePush == true,
                imageProvision: config.imageProvision == true,
                ecrPromotion: config.ecrPromotion == true,
                implemented: config.implemented == null ||
                        config.implemented.toString().toBoolean(),
                appendServiceNameToImageTag:
                        config.appendServiceNameToImageTag == true,
                promotionConfig: config.promotionConfig ?: config.type
        ]
    }
}
