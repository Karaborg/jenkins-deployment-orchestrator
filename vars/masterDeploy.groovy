import com.company.deploy.PipelineConfig
import com.company.deploy.EnvironmentResolver
import com.company.deploy.ServiceResolver
import com.company.deploy.JenkinsClient
import com.company.deploy.BuildService
import com.company.deploy.PushService
import com.company.deploy.PromotionService
import com.company.deploy.DeployService
import com.company.deploy.HealthCheckService
import com.company.deploy.TagControlService
import com.company.deploy.NotificationService
import com.company.deploy.ImageTagService
import com.company.deploy.ConfigurationValidationService
import com.company.deploy.ConfigurationPatchValidationService
import com.company.deploy.ConfigurationService
import groovy.json.JsonOutput

def call() {

    def config =
            new PipelineConfig(this)

    config.load()

    def serviceResolver = new ServiceResolver(config.services)

    configureJobParameters(config.environments, serviceResolver)

    def environmentResolver =
            new EnvironmentResolver(
                    config.environments
            )

    def jenkinsClient =
            new JenkinsClient(this)

    def buildService =
            new BuildService(
                    this,
                    config,
                    serviceResolver,
                    jenkinsClient
            )

    def pushService =
            new PushService(
                    this,
                    config,
                    serviceResolver,
                    jenkinsClient
            )

    def promotionService =
            new PromotionService(
                    this,
                    config,
                    serviceResolver,
                    jenkinsClient
            )

    def deployService =
            new DeployService(
                    this,
                    config,
                    serviceResolver,
                    jenkinsClient
            )

    def healthCheckService =
            new HealthCheckService(
                    this,
                    config,
                    serviceResolver
            )

    def tagControlService =
            new TagControlService(
                    this,
                    config,
                    serviceResolver
            )

    def notificationService =
            new NotificationService(this)

    def imageTagService =
            new ImageTagService(config.services)

    def configurationValidationService =
            new ConfigurationValidationService(
                    this,
                    config
            )

    def configurationPatchValidationService =
            new ConfigurationPatchValidationService(this, config.services)

    def configurationService =
            new ConfigurationService(
                    this,
                    config,
                    deployService
            )

    pipeline {

        agnet any

        stages {

            stage('Validate Configuration') {

                steps {

                    script {

                        def envConfig =
                                environmentResolver.resolve(
                                        params.ENVIRONMENT
                                )

                        def services =
                                serviceResolver.selectedServices(
                                        params
                                )

                        configurationPatchValidationService.validate(params)

                        if (!services && !params.CONFIG_DEPLOY) {
                            error(
                                    'En az bir microservice seçilmelidir.'
                            )
                        }

                        configurationValidationService.validate(
                                params,
                                envConfig,
                                services
                        )

                        def deploymentParams =
                                withDeploymentImageTags(
                                        params,
                                        envConfig,
                                        services,
                                        imageTagService,
                                        serviceResolver
                                )

                        echo "Environment     : ${envConfig.name}"
                        echo "Environment Type: ${envConfig.type}"
                        echo "Build Type      : ${envConfig.buildType}"
                        echo "Services        : ${services.join(', ')}"
                        if (params.DRY_RUN) {
                            echo 'Execution Mode  : DRY RUN'
                echo 'Helm changes, ArgoCD, tag control and health check will be skipped. Read-only Helm validation will run for cloud environments.'
                        }
                        if (envConfig.type != 'on-prem') {
                            echo 'Image Tags      :'
                            deploymentParams.IMAGE_TAGS.each {
                                service, imageTag ->
                                    echo "  ${service}: ${imageTag}"
                            }
                        }

                        env.BUILD_JOB_STATUS = 'NOT COMPLETED'
                        env.BUILD_JOB_DETAILS = ''
                        env.BUILD_PHASE_DURATION_SECONDS = ''
                        env.DEPLOYABLE_SERVICES = services.join(',')
                        env.FAILED_BUILD_SERVICES = ''
                        env.FORCE_DEPLOY_FILTER_APPLIED = 'false'
                        env.DEPLOY_JOB_DETAILS = ''
                        env.IMAGE_PUSH_STATUS = 'NOT RUN'
                        env.DEPLOY_STATUS = 'NOT COMPLETED'
                        env.TAG_CHECK_STATUS = 'NOT COMPLETED'
                        env.CONFIGURATION_DEPLOY_STATUS = 'NOT COMPLETED'
                        env.CONFIGURATION_TARGETS = ''
                        env.CONFIGURATION_REQUESTED_TARGETS = ''

                        if (params.CONFIG_DEPLOY) {
                            env.BUILD_JOB_STATUS = 'SKIPPED'
                            env.IMAGE_PUSH_STATUS = 'SKIPPED'
                            env.DEPLOY_STATUS = 'SKIPPED'
                            env.TAG_CHECK_STATUS = 'SKIPPED'
                        }
                    }
                }
            }

            stage('Build') {

                when {
                    expression {
                        serviceResolver.selectedServices(params).size() > 0 ||
                                hasCommonBuild(params)
                    }
                }

                steps {

                    script {

                        def envConfig =
                                environmentResolver.resolve(
                                        params.ENVIRONMENT
                                )

                        def services =
                                serviceResolver.selectedServices(params)

                        long buildPhaseStartedAtMillis =
                                System.currentTimeMillis()

                        def buildDetails = buildService.execute(
                                withImageTags(
                                        params,
                                        envConfig,
                                        services,
                                        imageTagService
                                ),
                                envConfig
                        )

                        env.BUILD_JOB_DETAILS = JsonOutput.toJson(buildDetails)
                        env.BUILD_PHASE_DURATION_SECONDS = Math.round(
                                (System.currentTimeMillis() -
                                        buildPhaseStartedAtMillis) / 1000.0d
                        ).toString()

                        def failedBuilds = buildDetails.findAll {
                            buildName, build ->
                                build.result != 'SUCCESS' &&
                                        build.result != 'NOT RUN'
                        }

                        if (failedBuilds) {
                            env.BUILD_JOB_STATUS = 'FAILURE'
                            def commonBuildFailed = failedBuilds.values().any {
                                build -> build.service?.startsWith('COMMON_MS')
                            }
                            env.DEPLOYABLE_SERVICES = services.findAll {
                                service -> !failedBuilds.values()*.service.contains(service)
                            }.join(',')
                            env.FAILED_BUILD_SERVICES =
                                    failedBuilds.values()*.service.join(',')

                            if (params.FORCE_DEPLOY && !commonBuildFailed) {
                                env.FORCE_DEPLOY_FILTER_APPLIED = 'true'
                                echo(
                                        'FORCE DEPLOY: Başarısız build servisleri ' +
                                                'sonraki adımlardan çıkarıldı: ' +
                                                failedBuilds.values()*.service.join(', ')
                                )
                            } else {
                            if (commonBuildFailed) {
                                echo(
                                        'Common MS build başarısız olduğu için ' +
                                                'FORCE DEPLOY uygulanmayacak.'
                                )
                            }
                            error(
                                    'Build başarısız: ' +
                                            failedBuilds.values()
                                                    .collect { build ->
                                                        "${build.service} (#${build.buildNumber}, ${build.result})"
                                                    }
                                                    .join(', ')
                            )
                            }
                        }

                        if (!failedBuilds) {
                            env.BUILD_JOB_STATUS = 'SUCCESS'
                        }
                    }
                }
            }

            stage('Push Image') {

                when {

                    expression {

                        def envConfig =
                                environmentResolver.resolve(
                                        params.ENVIRONMENT
                                )

                        return deployableServices(params, serviceResolver).size() > 0 &&
                                envConfig.imagePush
                    }
                }

                steps {

                    script {

                        def envConfig =
                                environmentResolver.resolve(
                                        params.ENVIRONMENT
                                )

                        def services = deployableServices(
                                params,
                                serviceResolver
                        )

                        pushService.execute(
                                withSelectedServices(
                                        withImageTags(
                                        params,
                                        envConfig,
                                        services,
                                        imageTagService
                                        ),
                                        services, serviceResolver
                                ),
                                envConfig
                        )

                        env.IMAGE_PUSH_STATUS = 'SUCCESS'
                    }
                }
            }

            stage('ECR Promotion') {

                when {

                    expression {

                        def envConfig =
                                environmentResolver.resolve(
                                        params.ENVIRONMENT
                                )

                        return deployableServices(params, serviceResolver).size() > 0 &&
                                envConfig.ecrPromotion
                    }
                }

                steps {

                    script {

                        def envConfig =
                                environmentResolver.resolve(
                                        params.ENVIRONMENT
                                )

                        def services = deployableServices(params, serviceResolver)

                        promotionService.execute(
                                withSelectedServices(withImageTags(
                                        params,
                                        envConfig,
                                        services,
                                        imageTagService
                                ), services, serviceResolver),
                                envConfig
                        )
                    }
                }
            }

            stage('Prepare Configuration') {

                when {
                    expression { params.CONFIG_DEPLOY }
                }

                steps {
                    script {
                        def envConfig = environmentResolver.resolve(params.ENVIRONMENT)
                        if (params.DRY_RUN) {
                            echo 'DRY RUN: Configuration update was skipped.'
                            env.CONFIGURATION_DEPLOY_STATUS = 'SKIPPED'
                        } else {
                            def configurationDetails = configurationService.execute(
                                    params,
                                    envConfig,
                                    false
                            )
                            env.CONFIGURATION_TARGETS =
                                    (configurationDetails.targets ?: []).join(',')
                            env.CONFIGURATION_REQUESTED_TARGETS =
                                    (configurationDetails.requestedTargets ?: []).join(',')
                            env.CONFIGURATION_DEPLOY_STATUS =
                                    env.CONFIGURATION_TARGETS ? 'SUCCESS' : 'SKIPPED'
                        }
                    }
                }
            }

            stage('Deploy') {

                when {
                    expression {
                        deployableServices(params, serviceResolver).size() > 0 ||
                                params.CONFIG_DEPLOY
                    }
                }

                steps {

                    script {

                        def envConfig =
                                environmentResolver.resolve(
                                        params.ENVIRONMENT
                                )

                        def services = deployableServices(params, serviceResolver)

                        def configurationServices =
                                env.CONFIGURATION_TARGETS ?
                                        env.CONFIGURATION_TARGETS.tokenize(',') : []

                        if (params.FORCE_DEPLOY) {
                            configurationServices -= failedBuildServices()
                        }

                        if (params.DRY_RUN) {
                            if (services) {
                                deployService.validateDryRun(envConfig, services)
                                env.DEPLOY_STATUS = 'DRY RUN'
                            }
                        } else {
                            def deployDetails = [:]

                            if (services) {
                                deployDetails = deployService.execute(
                                        withSelectedServices(withDeploymentImageTags(
                                                params,
                                                envConfig,
                                                services,
                                                imageTagService,
                                                serviceResolver
                                        ), services, serviceResolver),
                                        envConfig,
                                        (services + configurationServices).unique()
                                )
                            } else if (envConfig.type != 'on-prem' && configurationServices) {
                                deployService.syncConfiguration(
                                        envConfig.name,
                                        configurationServices
                                )
                            }

                            env.DEPLOY_JOB_DETAILS =
                                    JsonOutput.toJson(deployDetails)

                            def failedDeployments = deployDetails.findAll {
                                service, deployment ->
                                    deployment.result != 'SUCCESS'
                            }

                            if (failedDeployments) {
                                env.DEPLOY_STATUS = 'FAILURE'
                                error(
                                        'On-prem deploy başarısız: ' +
                                                failedDeployments.keySet().join(', ')
                                )
                            }

                            if (params.CONFIG_DEPLOY && envConfig.type == 'on-prem') {
                                configurationService.activateOnPrem(
                                        envConfig,
                                        (configurationServices - services)
                                )
                            }

                            if (services) {
                                env.DEPLOY_STATUS = 'SUCCESS'
                            }
                        }
                    }
                }
            }

            stage('Tag Control') {

                when {
                    expression {
                        deployableServices(params, serviceResolver).size() > 0
                    }
                }

                steps {

                    script {

                        def envConfig =
                                environmentResolver.resolve(
                                        params.ENVIRONMENT
                                )

                        def services = deployableServices(params, serviceResolver)

                        if (params.DRY_RUN) {
                            echo 'DRY RUN: Tag control was skipped.'
                            env.TAG_CHECK_STATUS = 'DRY RUN'
                        } else {
                            tagControlService.check(
                                    withSelectedServices(withDeploymentImageTags(
                                            params,
                                            envConfig,
                                            services,
                                            imageTagService,
                                            serviceResolver
                                    ), services, serviceResolver),
                                    envConfig
                            )

                            env.TAG_CHECK_STATUS = 'SUCCESS'
                        }
                    }
                }
            }

            stage('HealthCheck') {

                when {
                    expression {
                        deployableServices(params, serviceResolver).size() > 0 ||
                                params.CONFIG_DEPLOY
                    }
                }

                steps {

                    script {

                        def envConfig =
                                environmentResolver.resolve(
                                        params.ENVIRONMENT
                                )

                        def services = deployableServices(params, serviceResolver)

                        def healthCheckServices = (services +
                                deployableConfigurationServices(params)
                        ).unique()

                        if (!healthCheckServices) {
                            echo 'Health check skipped: no application or changed configuration targets.'
                            env.HEALTH_CHECK_RESULTS = '{}'
                        } else if (params.DRY_RUN) {
                            echo 'DRY RUN: Health check was skipped.'
                            env.HEALTH_CHECK_RESULTS =
                                    envConfig.type == 'on-prem' ? '' :
                                            JsonOutput.toJson(
                                                    healthCheckServices.collectEntries { service ->
                                                        [(service): [status: 'DRY RUN']]
                                                    }
                                            )
                        } else {
                            env.HEALTH_CHECK_RESULTS =
                                    envConfig.type == 'on-prem' ? '' :
                                            JsonOutput.toJson(
                                                    healthCheckServices.collectEntries { service ->
                                                        [(service): [status: 'FAILED']]
                                                    }
                                            )

                            catchError(
                                    buildResult: 'SUCCESS',
                                    stageResult: 'FAILURE'
                            ) {

                                def healthResults = healthCheckService.check(
                                        params,
                                        envConfig,
                                        healthCheckServices
                                )

                                env.HEALTH_CHECK_RESULTS =
                                        JsonOutput.toJson(healthResults)

                                def failedServices = healthResults.findAll {
                                    service, result ->
                                        result.status == 'FAILED'
                                }

                                if (failedServices) {
                                    error(
                                            'Health check failed for: ' +
                                                    failedServices.keySet().join(', ')
                                    )
                                }
                            }
                        }
                    }
                }
            }

        }

        post {

            always {

                script {

                    def envConfig =
                            environmentResolver.resolve(
                                    params.ENVIRONMENT
                            )

                    def services =
                            serviceResolver.selectedServices(
                                    params
                            )

                    if (params.CONFIG_DEPLOY) {
                        services = (services +
                                (env.CONFIGURATION_REQUESTED_TARGETS ?
                                        env.CONFIGURATION_REQUESTED_TARGETS.tokenize(',') : [])
                        ).unique()
                    }

                    def deploymentParams =
                            withDeploymentImageTags(
                                    params,
                                    envConfig,
                                    services,
                                    imageTagService,
                                    serviceResolver
                            )

                    notificationService.sendDeploymentSuccess([
                            environment: params.ENVIRONMENT,
                            environmentDns: envConfig.dns,
                            environmentType: envConfig.type,
                            dryRun: params.DRY_RUN,
                            configurationDeploy: params.CONFIG_DEPLOY,
                            applicationDeploy: serviceResolver.selectedServices(params).size() > 0 ||
                                    hasCommonBuild(params),
                            configurationServices: deployableConfigurationServices(params),
                            applicationServices: deployableServices(params, serviceResolver),
                            triggeredBy: triggeredBy(),
                            jenkinsBuildUrl: env.BUILD_URL ?: '',
                            startedAt: deploymentStartedAt(),
                            jobStartedAtMillis: currentBuild.startTimeInMillis,
                            imageTags: deploymentParams.IMAGE_TAGS,
                            backendBranch: params.BE_BRANCH_NAME,
                            frontendBranch: params.FE_BRANCH_NAME,
                            imagePush: envConfig.imagePush,
                            healthResultsJson: env.HEALTH_CHECK_RESULTS,
                            buildDetailsJson: env.BUILD_JOB_DETAILS,
                            deployDetailsJson: env.DEPLOY_JOB_DETAILS,
                            buildPhaseDurationSeconds:
                                    env.BUILD_PHASE_DURATION_SECONDS,
                            stepStatuses: [
                                    build    : env.BUILD_JOB_STATUS,
                                    imagePush: env.IMAGE_PUSH_STATUS,
                                    deploy   : env.DEPLOY_STATUS,
                                    configuration: env.CONFIGURATION_DEPLOY_STATUS,
                                    tagCheck : env.TAG_CHECK_STATUS
                            ],
                            pipelineStatus:
                                    currentBuild.currentResult ?: 'SUCCESS',
                            services: services,
                            commonBuild: commonBuildLabel(params),
                            mailTo: params.MAIL_TO
                    ])
                }
            }
        }
    }
}

private void configureJobParameters(Map environments, def serviceResolver) {

    def environmentChoices = environments.keySet().collect {
        it.toUpperCase()
    }

    try {
        properties([
                buildDiscarder(logRotator(numToKeepStr: '7')),
                parameters(
                        deploymentParameterDefinitions(
                                environmentChoices,
                                environments, serviceResolver,
                                true
                        )
                )
        ])
    } catch (Exception exception) {
        echo(
                'Active Choices Plugin bulunamadı veya yapılandırılamadı. ' +
                        'Standart ENVIRONMENT seçimi kullanılacak. ' +
                        "Detay: ${exception.message}"
        )

        properties([
                buildDiscarder(logRotator(numToKeepStr: '7')),
                parameters(
                        deploymentParameterDefinitions(
                                environmentChoices,
                                environments, serviceResolver,
                                false
                        )
                )
        ])
    }
}

private List deploymentParameterDefinitions(
        List environmentChoices,
        Map environments,
        def serviceResolver,
        boolean activeChoicesEnabled
) {

    def definitions = []

    if (activeChoicesEnabled) {
        definitions << commonBuildParameter()
        definitions << backendServicesParameter(serviceResolver)
    } else {
        definitions << commonBuildFallbackParameter()
        definitions.addAll(backendServiceBooleanParameters(serviceResolver))
    }

    definitions.addAll([
            stringParameterDefinition(
                    'BE_BRANCH_NAME',
                    'develop',
                    'Backend branch name'
            ),
            booleanParameterDefinition(
                    'CONFIG_DEPLOY',
                    'Apply configuration patch'
            )
    ])

    if (activeChoicesEnabled) {
        definitions << configurationPatchParameter()
        definitions << frontendServiceParameter(serviceResolver)
    } else {
        definitions << frontendServiceFallbackParameter(serviceResolver)
    }

    definitions.addAll([
            stringParameterDefinition(
                    'FE_BRANCH_NAME',
                    'develop',
                    'Frontend branch name'
            )
    ])

    if (activeChoicesEnabled) {
        definitions.add(activeEnvironmentParameter(environmentChoices))
        definitions.add(environmentInfoParameter(environments))
    } else {
        definitions.add([
                $class     : 'ChoiceParameterDefinition',
                name       : 'ENVIRONMENT',
                choices    : environmentChoices.join('\n'),
                description: 'Target environment'
        ])
    }

    definitions.add(stringParameterDefinition(
            'MAIL_TO',
            '',
            'Notification mail address'
    ))
    definitions.add(booleanParameterDefinition(
            'DRY_RUN',
            'Run build and image-push jobs without applying deployment changes'
    ))
    definitions.add(booleanParameterDefinition(
            'FORCE_DEPLOY',
            'Continue with successfully built services when one or more builds fail'
    ))

    return definitions
}

private List backendServiceBooleanParameters(def serviceResolver) {

    return serviceResolver.backendServices.collect { service ->
        booleanParameterDefinition(service, "Build and deploy ${serviceResolver.displayName(service)}")
    }
}

private Map backendServicesParameter(def serviceResolver) {

    def services = serviceResolver.backendServices

    return [
            $class      : 'ChoiceParameter',
            choiceType  : 'PT_CHECKBOX',
            name        : 'BACKEND_SERVICES',
            description : 'Select backend microservices to build and deploy',
            filterable  : false,
            randomName  : 'deployment-backend-services',
            script      : [
                    $class        : 'GroovyScript',
                    fallbackScript: [
                            classpath: [],
                            sandbox  : true,
                            script   : "return ${services.inspect()}"
                    ],
                    script        : [
                            classpath: [],
                            sandbox  : true,
                            script   : "return ${services.inspect()}"
                    ]
            ]
    ]
}

private Map configurationPatchParameter() {

    return [
            $class               : 'DynamicReferenceParameter',
            choiceType           : 'ET_FORMATTED_HTML',
            name                 : 'CONFIG_PATCH',
            description          : '',
            referencedParameters : 'CONFIG_DEPLOY',
            omitValueField       : true,
            randomName           : 'deployment-configuration-patch',
            script               : [
                    $class        : 'GroovyScript',
                    fallbackScript: [
                            classpath: [],
                            sandbox  : true,
                            script   : "return '<div style=\"color:#ef4444\">Configuration patch alanı yüklenemedi.</div>'"
                    ],
                    script        : [
                            classpath: [],
                            sandbox  : true,
                            script   : configurationPatchScript()
                    ]
            ]
    ]
}

private String configurationPatchScript() {

    return '''
def configDeployEnabled =
        CONFIG_DEPLOY?.toString() in ['on', 'true']

if (!configDeployEnabled) {
    return ''
}

return \'\'\'
<div style="margin:8px 0">

  <details style="margin-bottom:10px">
    <summary style="cursor:pointer; font-weight:600">
      Örnek configuration patch aşağıdaki gibidir:
    </summary>

    <pre style="margin:8px 0; padding:10px; border-radius:4px; background:#1f2937; overflow:auto"># common
example.feature.enabled=false

# api
service.request.max-size-bytes=10485760</pre>

  </details>

  <label for="config-patch" style="font-weight:600">
    Configuration patch
  </label>

  <textarea
      id="config-patch"
      name="value"
      rows="10"
      style="width:100%; margin-top:6px; font-family:monospace"
      placeholder="# common&#10;example.feature.enabled=false&#10;&#10;# api&#10;service.request.max-size-bytes=10485760"></textarea>

  <div style="margin-top:5px; color:#6b7280">
    Sadece eklenecek veya güncellenecek property\'leri girin.
  </div>
</div>
\'\'\'
'''
}

private Map booleanParameterDefinition(String name, String description) {

    return [
            $class     : 'BooleanParameterDefinition',
            name       : name,
            defaultValue: false,
            description: description
    ]
}

private Map stringParameterDefinition(
        String name,
        String defaultValue,
        String description
) {

    return [
            $class     : 'StringParameterDefinition',
            name       : name,
            defaultValue: defaultValue,
            description: description,
            trim       : true
    ]
}

private Map activeEnvironmentParameter(List environmentChoices) {

    return [
            $class      : 'ChoiceParameter',
            choiceType  : 'PT_SINGLE_SELECT',
            name        : 'ENVIRONMENT',
            description : 'Target environment',
            filterable  : false,
            randomName  : 'deployment-environment-choice',
            script      : [
                    $class        : 'GroovyScript',
                    fallbackScript: [
                            classpath: [],
                            sandbox  : true,
                            script   : "return ${environmentChoices.inspect()}"
                    ],
                    script        : [
                            classpath: [],
                            sandbox  : true,
                            script   : "return ${environmentChoices.inspect()}"
                    ]
            ]
    ]
}

private Map environmentInfoParameter(Map environments) {

    return [
            $class               : 'DynamicReferenceParameter',
            choiceType           : 'ET_FORMATTED_HTML',
            name                 : 'ENVIRONMENT_INFO',
            description          : 'Selected environment information',
            referencedParameters : 'ENVIRONMENT',
            omitValueField       : true,
            randomName           : 'deployment-environment-info',
            script               : [
                    $class        : 'GroovyScript',
                    fallbackScript: [
                            classpath: [],
                            sandbox  : true,
                            script   : "return '<div style=\"color:#6b7280\">Environment information is unavailable.</div>'"
                    ],
                    script        : [
                            classpath: [],
                            sandbox  : true,
                            script   : environmentInfoScript(environments)
                    ]
            ]
    ]
}

private String environmentInfoScript(Map environments) {

    def entries = environments.collect { environmentName, environmentConfig ->
        def name = escapeSingleQuote(environmentName.toUpperCase())
        def dns = escapeSingleQuote(environmentConfig.dns ?: 'Not configured')
        def implemented = environmentConfig.implemented == null ?
                true : environmentConfig.implemented.toString().toBoolean()

        "'${name}': [dns: '${dns}', implemented: ${implemented}]"
    }.join(',\n')

    return """
def environmentMetadata = [
${entries}
]
def selectedEnvironment = ENVIRONMENT ?: ''
def selected = environmentMetadata[selectedEnvironment]

if (!selected) {
    return '<div style="color:#6b7280; padding:4px 0">Select an environment to view its DNS.</div>'
}

return '<div style="padding:8px 10px; border-left:3px solid #60a5fa; color:#9ca3af">' +
        '<b style="color:#e5e7eb">' + selectedEnvironment + '</b>' +
        ' &nbsp;|&nbsp; DNS: ' +
        (selected.dns == 'Not configured' ?
                '<span style="color:#9ca3af">Not configured</span>' :
                '<a href="https://' + selected.dns +
                        '" target="_blank" rel="noopener noreferrer" ' +
                        'style="color:#60a5fa">' +
                        selected.dns + '</a>') +
        (selected.implemented ? '' :
                ' &nbsp;|&nbsp; <span style="color:#f87171; font-weight:700">NOT IMPLEMENTED YET</span>') +
        '</div>'
"""
}

private String escapeSingleQuote(def value) {

    return value.toString()
            .replace('\\', '\\\\')
            .replace("'", "\\'")
}

private String triggeredBy() {

    def userCause = currentBuild
            .getBuildCauses('hudson.model.Cause$UserIdCause')
            ?.find { cause -> cause.userName }

    if (userCause?.userName) {
        return userCause.userName
    }

    def cause = currentBuild.getBuildCauses()?.find {
        it.shortDescription
    }

    return cause?.shortDescription ?: 'Jenkins'
}

private String commonBuildLabel(def params) {

    if (isCommonBuildWithoutUnitTest(params)) {
        return 'COMMON_MS (WITHOUT UNIT TEST)'
    }

    return isCommonBuild(params) ? 'COMMON_MS' : ''
}

private boolean hasCommonBuild(def params) {
    return isCommonBuild(params) || isCommonBuildWithoutUnitTest(params)
}

private boolean isCommonBuild(def params) {
    return params.COMMON_BUILD == 'COMMON_MS' || params.COMMON_MS == true
}

private boolean isCommonBuildWithoutUnitTest(def params) {
    return params.COMMON_BUILD == 'COMMON_MS_WITHOUT_UNIT_TEST' ||
            params.COMMON_MS_WITHOUT_UNIT_TEST == true
}

private String deploymentStartedAt() {

    def startedAt = new Date(currentBuild.startTimeInMillis)

    return startedAt.format(
            'yyyy-MM-dd HH:mm:ss z',
            TimeZone.getTimeZone('Europe/Istanbul')
    )
}

private Map commonBuildParameter() {

    def choices = [
            'NONE:selected',
            'COMMON_MS',
            'COMMON_MS_WITHOUT_UNIT_TEST'
    ]

    return [
            $class      : 'ChoiceParameter',
            choiceType  : 'PT_RADIO',
            name        : 'COMMON_BUILD',
            description : 'Select at most one Common MS build',
            filterable  : false,
            randomName  : 'deployment-common-build',
            script      : [
                    $class        : 'GroovyScript',
                    fallbackScript: [
                            classpath: [],
                            sandbox  : true,
                            script   : "return ${choices.inspect()}"
                    ],
                    script        : [
                            classpath: [],
                            sandbox  : true,
                            script   : "return ${choices.inspect()}"
                    ]
            ]
    ]
}

private Map commonBuildFallbackParameter() {

    return [
            $class     : 'ChoiceParameterDefinition',
            name       : 'COMMON_BUILD',
            choices    : [
                    'NONE',
                    'COMMON_MS',
                    'COMMON_MS_WITHOUT_UNIT_TEST'
            ].join('\n'),
            description: 'Select at most one Common MS build'
    ]
}

private Map frontendServiceParameter(def serviceResolver) {

    def choices = ['NONE:selected'] + serviceResolver.frontendServices

    return [
            $class      : 'ChoiceParameter',
            choiceType  : 'PT_RADIO',
            name        : 'FRONTEND_SERVICE',
            description : 'Select at most one frontend to build and deploy',
            filterable  : false,
            randomName  : 'deployment-frontend-service',
            script      : [
                    $class        : 'GroovyScript',
                    fallbackScript: [
                            classpath: [],
                            sandbox  : true,
                            script   : "return ${choices.inspect()}"
                    ],
                    script        : [
                            classpath: [],
                            sandbox  : true,
                            script   : "return ${choices.inspect()}"
                    ]
            ]
    ]
}

private Map frontendServiceFallbackParameter(def serviceResolver) {

    return [
            $class     : 'ChoiceParameterDefinition',
            name       : 'FRONTEND_SERVICE',
            choices    : (['NONE'] + serviceResolver.frontendServices).join('\n'),
            description: 'Select at most one frontend to build and deploy'
    ]
}

private List deployableServices(
        def params,
        ServiceResolver serviceResolver
) {

    if (env.FORCE_DEPLOY_FILTER_APPLIED != 'true') {
        return serviceResolver.selectedServices(params)
    }

    return env.DEPLOYABLE_SERVICES ?
            env.DEPLOYABLE_SERVICES.tokenize(',') : []
}

private List failedBuildServices() {

    return env.FAILED_BUILD_SERVICES ?
            env.FAILED_BUILD_SERVICES.tokenize(',') : []
}

private List deployableConfigurationServices(def params) {

    def services = env.CONFIGURATION_TARGETS ?
            env.CONFIGURATION_TARGETS.tokenize(',') : []

    return params.FORCE_DEPLOY ? services - failedBuildServices() : services
}

private Map withSelectedServices(def params, List services, def serviceResolver) {

    def selectedFrontend = services.find {
        serviceResolver.isFrontend(it)
    } ?: 'NONE'

    def frontendSelections = [FRONTEND_SERVICE: selectedFrontend]

    return params + frontendSelections + [
            BACKEND_SERVICES: services.findAll { !serviceResolver.isFrontend(it) }
    ]
}

private Map withImageTags(
        def params,
        Map environmentConfig,
        List services,
        ImageTagService imageTagService
) {

    if (environmentConfig.type == 'on-prem') {
        return params + [IMAGE_TAGS: [:]]
    }

    if (!env.DEPLOYMENT_IMAGE_TAG_TIMESTAMP) {
        env.DEPLOYMENT_IMAGE_TAG_TIMESTAMP =
                imageTagService.timestamp()
    }

    return params + [
            IMAGE_TAGS: imageTagService.generate(
                    params,
                    services,
                    env.DEPLOYMENT_IMAGE_TAG_TIMESTAMP
            )
    ]
}

private Map withDeploymentImageTags(
        def params,
        Map environmentConfig,
        List services,
        ImageTagService imageTagService,
        ServiceResolver serviceResolver
) {

    def imageTagParams = withImageTags(
            params,
            environmentConfig,
            services,
            imageTagService
    )

    if (!environmentConfig.appendServiceNameToImageTag) {
        return imageTagParams
    }

    return imageTagParams + [
            IMAGE_TAGS: imageTagParams.IMAGE_TAGS.collectEntries {
                service, imageTag ->
                    [(service): "${imageTag}-${serviceResolver.imageName(service)}"]
            }
    ]
}
