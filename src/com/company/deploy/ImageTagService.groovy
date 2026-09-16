package com.company.deploy

import java.util.TimeZone

class ImageTagService implements Serializable {

    private final Set frontendServices

    ImageTagService(Map services) {
        this.frontendServices = services.findAll { _, config ->
            config.type == 'frontend'
        }.keySet() as Set
    }

    String timestamp() {
        return new Date().format(
                'yyyyMMddHHmm',
                TimeZone.getTimeZone('Europe/Istanbul')
        )
    }

    Map generate(
            def params,
            List selectedServices,
            String timestamp
    ) {

        if (!timestamp?.trim()) {
            throw new IllegalArgumentException(
                    'Image tag timestamp cannot be empty.'
            )
        }

        return selectedServices.collectEntries { service ->
            String branch = frontendServices.contains(service) ?
                    params.FE_BRANCH_NAME : params.BE_BRANCH_NAME

            [(service): buildTag(branch, timestamp)]
        }
    }

    String forService(def params, String service) {
        return params.IMAGE_TAGS?.get(service)
    }

    private String buildTag(String branch, String timestamp) {

        String branchWithoutGitFlowPrefix = branch
                ?.trim()
                ?.toLowerCase()
                ?.replaceFirst('^(release|hotfix)/', '')

        String normalizedBranch = branchWithoutGitFlowPrefix
                ?.replaceAll('[^a-z0-9]', '')

        if (!normalizedBranch) {
            throw new IllegalArgumentException(
                    "Branch name cannot produce a valid image tag: '${branch}'"
            )
        }

        return "1.0.${normalizedBranch}.${timestamp}"
    }
}
