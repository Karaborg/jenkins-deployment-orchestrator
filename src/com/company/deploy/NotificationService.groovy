package com.company.deploy

import groovy.json.JsonSlurperClassic

class NotificationService {

    def script

    NotificationService(def script) {
        this.script = script
    }

    void sendDeploymentSuccess(Map details) {

        def recipients = details.mailTo?.trim()

        if (!recipients) {
            script.echo('MAIL_TO is empty. Deployment notification will not be sent.')
            return
        }

        script.emailext(
                subject: "${subjectPrefix(details)} ${deploymentStatus(details)} - ${details.environment}",
                to: recipients,
                mimeType: 'text/html',
                body: buildDeploymentBody(details)
        )
    }

    private String buildDeploymentBody(Map details) {

        boolean configurationOnly = details.configurationDeploy &&
                !details.applicationDeploy

        def columns = serviceColumns(details)
        def healthCheckResults = healthResults(details)
        def buildDetails = parseBuildDetails(details)
        def deployDetails = parseDeployDetails(details)
        def services = servicesWithCommonBuild(details)

        def serviceHeaders = columns.collect { column ->
            "<th>${column}</th>"
        }.join("\n")

        def serviceRows = services.collect { service ->
            def statusCells = columns.collect { column ->
                "<td>${serviceStatus(column, service, healthCheckResults, details.stepStatuses ?: [:], buildDetails, deployDetails, details.configurationServices ?: [], details.applicationServices ?: [], details.environmentType)}</td>"
            }.join("\n")

            """
            <tr>
                <td>${service}</td>
                ${statusCells}
            </tr>
            """
        }.join("\n")

        def deploymentFlow

        if (details.environmentType == 'on-prem') {

            deploymentFlow = configurationOnly ? """
                <b>Jenkins Build</b> (SKIPPED)
                → Configuration Deployment${dryRunSuffix(details)}
            """ : """
                <b>Jenkins Build</b>
                → On-Prem Deployment${dryRunSuffix(details)}
            """

        } else {

            def steps = configurationOnly ?
                    ['Jenkins Build (SKIPPED)', 'Configuration Update'] :
                    ['Jenkins Build']

            if (details.imagePush && !configurationOnly) {
                steps << 'Image Push'
            }

            if (details.dryRun == true) {
                steps << 'Helm Validation (read-only)'
            }

            steps.addAll(configurationOnly ? [
                    "Helm Update${dryRunSuffix(details)}",
                    "ArgoCD Sync${dryRunSuffix(details)}",
                    'Tag Control (SKIPPED)',
                    'Health Check (SKIPPED)'
            ] : [
                    "Helm Update${dryRunSuffix(details)}",
                    "ArgoCD Sync${dryRunSuffix(details)}",
                    "Tag Control${dryRunSuffix(details)}",
                    "Health Check${dryRunSuffix(details)}"
            ])

            deploymentFlow = steps.join(' → ')
        }

        def deploymentStatus = deploymentStatus(details)

        return """
<html>
<body>

<h2>${headline(deploymentStatus, details.dryRun == true)}</h2>

<h3>Deployment Information</h3>

<table border="1" cellpadding="6" cellspacing="0">
    <tr>
        <td><b>Environment</b></td>
        <td>${details.environment}</td>
    </tr>
    <tr>
        <td><b>Environment DNS</b></td>
        <td>${environmentDnsLink(details.environmentDns)}</td>
    </tr>
    ${executionModeRow(details)}
    <tr>
        <td><b>Triggered By</b></td>
        <td>${details.triggeredBy ?: 'Jenkins'}</td>
    </tr>
    <tr>
        <td><b>Started At</b></td>
        <td>${details.startedAt ?: '-'}</td>
    </tr>
    <tr>
        <td><b>Total Job Duration</b></td>
        <td>${totalJobDuration(details)}</td>
    </tr>
    ${artifactReferenceRow(details)}
</table>

<h3>Services</h3>

<table border="1" cellpadding="6" cellspacing="0">
    <tr>
        <th>Service</th>
        ${serviceHeaders}
    </tr>

    ${serviceRows}

</table>

${buildDetailsTable(buildDetails)}

<h3>Deployment Flow</h3>

<p>
    ${deploymentFlow}
</p>

<p>
    ${statusMessage(deploymentStatus, details)}
</p>

${jenkinsBuildLink(details)}

<hr>

<p>
    <i>This is an automated message from Jenkins.</i>
</p>

</body>
</html>
"""
    }

    private List serviceColumns(Map details) {
        if (details.environmentType == 'on-prem') {
            def columns = ['Build Job', 'Deploy Job']
            if (details.configurationDeploy) {
                columns << 'Configuration Update'
            }
            return columns
        }

        def columns = ['Build Job']

        if (details.imagePush) {
            columns << 'Image Push'
        }

        columns.addAll([
                'Helm Update',
                'ArgoCD Sync',
                'Tag Check',
                'Health Check'
        ])

        if (details.configurationDeploy) {
            columns << 'Configuration Update'
        }

        return columns
    }

    private String serviceStatus(
            String column,
            String service,
            Map healthResults,
            Map stepStatuses,
            Map buildDetails,
            Map deployDetails,
            List configurationServices,
            List applicationServices,
            String environmentType
    ) {

        def buildResult = buildDetails.values().find {
            it.service == service
        }?.result

        if (isCommonBuild(service) && column != 'Build Job') {
            return 'NOT APPLICABLE'
        }

        // A failed Common MS build is a hard gate: no selected application
        // service is allowed to start, including on-prem frontend builds.
        if (buildResult == 'NOT RUN') {
            return 'NOT RUN'
        }

        if (column in ['Deploy Job', 'Helm Update', 'ArgoCD Sync', 'Health Check'] &&
                !(service in applicationServices) &&
                !(service in configurationServices)) {
            return 'SKIPPED'
        }

        if (column == 'Health Check') {
            def status = healthResults[service]?.status

            if (status == 'HEALTHY') {
                return 'SUCCESS'
            }

            if (status == 'DRY RUN') {
                return 'DRY RUN'
            }

            return status == 'FAILED' ? 'FAILED' : 'NOT RUN'
        }

        if (column == 'Build Job') {
            if (buildResult) {
                return buildResult
            }

            if (!(service in applicationServices) && !isCommonBuild(service)) {
                return 'SKIPPED'
            }

            return stepStatuses.build ?: 'NOT COMPLETED'
        }

        if (column == 'Image Push') {
            if (!(service in applicationServices) && !isCommonBuild(service)) {
                return 'SKIPPED'
            }
            return stepStatuses.imagePush ?: 'NOT RUN'
        }

        if (column == 'Configuration Update') {
            return service in configurationServices ?
                    (stepStatuses.configuration ?: 'NOT COMPLETED') : 'SKIPPED'
        }

        if (column in ['Deploy Job', 'Helm Update', 'ArgoCD Sync']) {
            def deployResult = deployDetails[service]?.result

            if (deployResult) {
                return deployResult
            }

            return stepStatuses.deploy ?: 'NOT COMPLETED'
        }

        if (column == 'Tag Check' &&
                !(service in applicationServices) && !isCommonBuild(service)) {
            return 'SKIPPED'
        }

        return stepStatuses.tagCheck ?: 'NOT COMPLETED'
    }

    private Map healthResults(Map details) {

        if (!details.healthResultsJson?.trim()) {
            return [:]
        }

        return new JsonSlurperClassic()
                .parseText(details.healthResultsJson) as Map
    }

    private Map parseBuildDetails(Map details) {

        if (!details.buildDetailsJson?.trim()) {
            return [:]
        }

        return new JsonSlurperClassic()
                .parseText(details.buildDetailsJson) as Map
    }

    private Map parseDeployDetails(Map details) {

        if (!details.deployDetailsJson?.trim()) {
            return [:]
        }

        return new JsonSlurperClassic()
                .parseText(details.deployDetailsJson) as Map
    }

    private List servicesWithCommonBuild(Map details) {

        def services = details.services ?: []

        return details.commonBuild ?
                [details.commonBuild] + services : services
    }

    private boolean isCommonBuild(String service) {
        return service?.startsWith('COMMON_MS')
    }

    private String buildDetailsTable(Map buildDetails) {

        if (!buildDetails) {
            return ''
        }

        def rows = buildDetails.values().collect { build ->
            """
            <tr>
                <td>${build.service ?: '-'}</td>
                <td>${formatDuration(build.queueDurationSeconds)}</td>
                <td>${formatDuration(build.buildDurationSeconds)}</td>
                <td>#${build.buildNumber ?: '-'}</td>
                <td>${build.result ?: '-'}</td>
            </tr>
            """
        }.join("\n")

        return """
<h3>Build Details</h3>

<table border="1" cellpadding="6" cellspacing="0">
    <tr>
        <th>Service</th>
        <th>Queue Time</th>
        <th>Build Time</th>
        <th>Remote Build</th>
        <th>Result</th>
    </tr>
    ${rows}
</table>
"""
    }

    private String formatDuration(def seconds) {

        if (seconds == null) {
            return '-'
        }

        def totalSeconds = seconds as Long
        def minutes = (totalSeconds / 60) as Long
        def remainingSeconds = totalSeconds % 60

        return minutes > 0 ?
                "${minutes}m ${remainingSeconds}s" :
                "${remainingSeconds}s"
    }

    private String totalJobDuration(Map details) {

        long elapsedSeconds = elapsedJobDurationSeconds(details)
        long buildPhaseSeconds = durationSeconds(
                details.buildPhaseDurationSeconds
        )

        if (buildPhaseSeconds == 0L) {
            return formatDuration(elapsedSeconds)
        }

        def buildDurations = parseBuildDetails(details)
                .values()
                .collect { build ->
                    durationSeconds(build.buildDurationSeconds)
                }

        long cumulativeBuildSeconds = buildDurations ?
                (buildDurations.sum() as Long) : 0L

        long nonBuildPipelineSeconds = Math.max(
                0L,
                elapsedSeconds - buildPhaseSeconds
        )

        return formatDuration(
                cumulativeBuildSeconds + nonBuildPipelineSeconds
        )
    }

    private long elapsedJobDurationSeconds(Map details) {

        if (!details.jobStartedAtMillis) {
            return 0L
        }

        return Math.max(
                0L,
                Math.round(
                        (System.currentTimeMillis() -
                                (details.jobStartedAtMillis as Long)) / 1000.0d
                ) as Long
        )

    }

    private long durationSeconds(def duration) {

        if (duration == null || duration.toString().trim().isEmpty()) {
            return 0L
        }

        return duration as Long
    }

    private String deploymentStatus(Map details) {

        if (details.pipelineStatus && details.pipelineStatus != 'SUCCESS') {
            return details.pipelineStatus
        }

        if (parseBuildDetails(details).values().any {
            it.result != 'SUCCESS'
        }) {
            return 'COMPLETED WITH BUILD FAILURE'
        }

        return healthResults(details).values().any {
            it.status == 'FAILED'
        } ? 'COMPLETED WITH HEALTH CHECK FAILURE' : 'SUCCESS'
    }

    private String headline(String deploymentStatus, boolean dryRun) {

        if (dryRun && deploymentStatus == 'SUCCESS') {
            return 'Dry run completed successfully.'
        }

        if (deploymentStatus == 'SUCCESS') {
            return 'Deployment completed successfully.'
        }

        if (deploymentStatus == 'COMPLETED WITH HEALTH CHECK FAILURE') {
            return 'Deployment completed with health-check failures.'
        }

        if (deploymentStatus == 'COMPLETED WITH BUILD FAILURE') {
            return 'Deployment completed for successfully built services.'
        }

        return "Deployment ${deploymentStatus.toLowerCase()}."
    }

    private String statusMessage(String deploymentStatus, Map details) {

        boolean dryRun = details.dryRun == true

        if (dryRun && deploymentStatus == 'SUCCESS') {
            return 'Build and image-push steps completed. No deployment changes were applied.'
        }

        if (deploymentStatus == 'SUCCESS') {
            return 'All deployment steps completed successfully.'
        }

        if (deploymentStatus == 'COMPLETED WITH HEALTH CHECK FAILURE') {
            return 'Build and deployment completed, but one or more health checks failed.'
        }

        if (deploymentStatus == 'COMPLETED WITH BUILD FAILURE') {
            def failedServices = parseBuildDetails(details)
                    .values()
                    .findAll { it.result != 'SUCCESS' }
                    .collect { it.service }

            return 'Deployment completed for successfully built services. ' +
                    "Build failed for: ${failedServices.join(', ')}."
        }

        if (details.environmentType == 'on-prem') {
            def failedServices = parseDeployDetails(details).findAll {
                service, deployment -> deployment.result != 'SUCCESS'
            }.keySet()

            if (failedServices) {
                return 'Deployment completed for the remaining services. ' +
                        "Failed services: ${failedServices.join(', ')}."
            }
        }

        return 'The pipeline ended before all deployment steps could be confirmed.'
    }

    private String subjectPrefix(Map details) {

        return details.dryRun == true ? '[DRY RUN]' : '[DEPLOYMENT]'
    }

    private String executionModeRow(Map details) {

        if (details.dryRun != true) {
            return ''
        }

        return """
    <tr>
        <td><b>Execution Mode</b></td>
        <td><b>DRY RUN</b> — No deployment changes were applied.</td>
    </tr>
"""
    }

    private String environmentDnsLink(def environmentDns) {

        def dns = environmentDns?.toString()?.trim()

        if (!dns) {
            return '-'
        }

        def url = dns ==~ /https?:\/\/.+/ ? dns : "https://${dns}"

        return "<a href=\"${url}\">${dns}</a>"
    }

    private String dryRunSuffix(Map details) {

        return details.dryRun == true ? ' (simulated)' : ''
    }

    private String jenkinsBuildLink(Map details) {

        if (!details.jenkinsBuildUrl?.trim()) {
            return ''
        }

        return """
<p>
    <a href="${details.jenkinsBuildUrl}"
       style="display:inline-block; padding:10px 14px; background:#2563eb; color:#ffffff; text-decoration:none; border-radius:4px; font-weight:bold">
        View Jenkins Build
    </a>
</p>
"""
    }

    private String formatImageTags(Map imageTags) {

        if (!imageTags) {
            return '-'
        }

        def tagsByValue = imageTags.groupBy { service, imageTag -> imageTag }

        if (tagsByValue.size() == 1) {
            return tagsByValue.keySet().first()
        }

        return tagsByValue.collect { imageTag, services ->
            "${services.keySet().join(', ')}: ${imageTag}"
        }.join('<br/>')
    }

    private String artifactReferenceRow(Map details) {

        if (details.environmentType != 'on-prem') {
            return """
    <tr>
        <td><b>Image Tags</b></td>
        <td>${formatImageTags(details.imageTags)}</td>
    </tr>
"""
        }

        def branches = [
                'BE': details.backendBranch,
                'FE': details.frontendBranch
        ].findAll { label, branch -> branch?.toString()?.trim() }

        if (!branches) {
            return ''
        }

        return """
    <tr>
        <td><b>Branches</b></td>
        <td>${branches.collect { label, branch -> "${label}: ${branch}" }.join('<br/>')}</td>
    </tr>
"""
    }
}
