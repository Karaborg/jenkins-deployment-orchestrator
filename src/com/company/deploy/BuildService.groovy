package com.company.deploy

class BuildService implements Serializable {

    private final def script
    private final PipelineConfig config
    private final ServiceResolver serviceResolver
    private final JenkinsClient jenkinsClient

    BuildService(
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
            Map environmentConfig
    ) {

        def environmentType =
                environmentConfig.type

        def buildType =
                environmentConfig.buildType

        def environmentJobs =
                config.buildJobs[buildType]

        if (!environmentJobs) {
            script.error(
                    "No build jobs defined for build type: ${buildType}"
            )
        }

        /*
         * Remote Jenkins
         */

        def jenkinsDns =
                environmentJobs.jenkinsDns

        def credentialId =
                environmentJobs.credentialId

        if (!jenkinsDns) {
            script.error(
                    "Build Jenkins DNS tanımlanmamış: ${buildType}"
            )
        }

        if (!credentialId) {
            script.error(
                    "Build Jenkins credentialId tanımlanmamış: ${buildType}"
            )
        }

        /*
         * Common MS
         */
        def commonJobs = config.buildJobs.common
        def buildDetails = [:]

        if (isCommonBuildWithoutUnitTest(params)) {

            def commonJob =
                    commonJobs.commonMsWithoutUnitTest

            if (!commonJob) {
                script.error(
                        "COMMON_MS_WITHOUT_UNIT_TEST için " +
                                "build job bulunamadı."
                )
            }

            def commonBuild = jenkinsClient.triggerAndWait(
                    commonJobs.jenkinsDns,
                    commonJob.job,
                    commonJobs.credentialId,
                    backendBranchParameters(params),
                    'Common MS Build Without Unit Test'
            )

            buildDetails['Common MS Build Without Unit Test'] = [
                    service: 'COMMON_MS (WITHOUT UNIT TEST)'
            ] + commonBuild

            if (commonBuild.result != 'SUCCESS') {
                return notRunBuildDetails(params, buildDetails)
            }

        } else if (isCommonBuild(params)) {

            def commonJob =
                    commonJobs.commonMs

            if (!commonJob) {
                script.error(
                        "COMMON_MS için build job bulunamadı."
                )
            }

            def commonBuild = jenkinsClient.triggerAndWait(
                    commonJobs.jenkinsDns,
                    commonJob.job,
                    commonJobs.credentialId,
                    backendBranchParameters(params),
                    'Common MS Build'
            )

            buildDetails['Common MS Build'] = [
                    service: 'COMMON_MS'
            ] + commonBuild

            if (commonBuild.result != 'SUCCESS') {
                return notRunBuildDetails(params, buildDetails)
            }
        }

        /*
         * Services
         */

        def selectedServices =
                serviceResolver.selectedServices(params)

        // On-prem frontend artifacts are built by their deployment jobs.
        // Do not look for or trigger a separate frontend build job here.
        def servicesToBuild = environmentType == 'on-prem' ?
                selectedServices.findAll { !serviceResolver.isFrontend(it) } :
                selectedServices

        def parallelJobs = [:]

        servicesToBuild.each { service ->

            def imageTag =
                    params.IMAGE_TAGS?.get(service)

            def jobConfig =
                    environmentJobs[service]

            if (!jobConfig) {

                script.error(
                        "${service} için " +
                                "${params.ENVIRONMENT} ortamında " +
                                "build job bulunamadı."
                )
            }

            def jobName =
                    jobConfig.job

            def branchSource =
                    serviceResolver.isFrontend(service) ?
                            'FE_BRANCH_NAME' : 'BE_BRANCH_NAME'

            def branchName =
                    params[branchSource]?.trim()

            if (!branchName) {
                script.error(
                        "${service} için ${branchSource} değeri boş olamaz."
                )
            }

            def displayName =
                    serviceResolver.displayName(service) +
                            ' Build'

            parallelJobs[displayName] = {

                if (environmentType == 'on-prem') {

                    return [
                            service: service
                    ] + jenkinsClient.triggerAndWait(
                            jenkinsDns,
                            jobName,
                            credentialId,
                            branchParameters(service, branchName) + [
                                    REVISION: '0.0.1'
                            ],
                            displayName
                    )

                } else {

                    if (!imageTag) {
                        script.error(
                                "${service} için otomatik image tag oluşturulamadı."
                        )
                    }

                    return [
                            service: service
                    ] + jenkinsClient.triggerAndWait(
                            jenkinsDns,
                            jobName,
                            credentialId,
                            [
                                    MAIL_TO      : params.MAIL_TO,
                                    IMAGE_TAG    : imageTag,
                                    IMAGE_VERSION: imageTag,
                                    PLATFORM     : jobConfig.platform ?: ''
                            ] + branchParameters(
                                    service,
                                    branchName
                            ),
                            displayName
                    )
                }
            }
        }

        if (parallelJobs) {
            buildDetails.putAll(script.parallel(parallelJobs) as Map)
        }

        return buildDetails
    }

    private Map notRunBuildDetails(def params, Map buildDetails) {

        serviceResolver.selectedServices(params).each { service ->
            buildDetails[serviceResolver.displayName(service) + ' Build'] = [
                    service: service,
                    result : 'NOT RUN'
            ]
        }

        return buildDetails
    }

    /**
     * Gives every target the generic parameter it may already use and the
     * service-specific one.  Crucially, a target never receives the branch
     * parameter belonging to the other application type.
     */
    private Map branchParameters(
            String service,
            String branchName
    ) {

        def specificParameter = serviceResolver.isFrontend(service) ?
                'FE_BRANCH_NAME' : 'BE_BRANCH_NAME'

        def parameters = [
                (specificParameter): branchName,
                BRANCH_NAME       : branchName
        ]

        return parameters
    }

    private static Map backendBranchParameters(def params) {
        def branchName = params.BE_BRANCH_NAME?.trim()

        if (!branchName) {
            throw new IllegalArgumentException(
                    'COMMON_MS için BE_BRANCH_NAME değeri boş olamaz.'
            )
        }

        return branchParameters('COMMON_MS', branchName)
    }

    private static boolean isCommonBuild(def params) {
        return params.COMMON_BUILD == 'COMMON_MS' || params.COMMON_MS == true
    }

    private static boolean isCommonBuildWithoutUnitTest(def params) {
        return params.COMMON_BUILD == 'COMMON_MS_WITHOUT_UNIT_TEST' ||
                params.COMMON_MS_WITHOUT_UNIT_TEST == true
    }
}
