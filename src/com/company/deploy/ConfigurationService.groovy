package com.company.deploy

class ConfigurationService implements Serializable {

    private final def script
    private final PipelineConfig config
    private final DeployService deployService
    private final List backendTargets
    private final Map targetToService

    ConfigurationService(def script, PipelineConfig config, DeployService deployService) {
        this.script = script
        this.config = config
        this.deployService = deployService
        this.targetToService = config.services.collectEntries { service, serviceConfig ->
            serviceConfig.type != 'frontend' && serviceConfig.configurationTarget ?
                    [(serviceConfig.configurationTarget): service] : [:]
        }
        this.backendTargets = targetToService.keySet() as List
    }

    Map execute(def params, Map environmentConfig, boolean activate = true) {
        if (!params.CONFIG_DEPLOY) {
            return [:]
        }

        Map<String, Map<String, String>> patch = parsePatch(params.CONFIG_PATCH?.toString())
        List<String> requestedTargets = patch.containsKey('common') ?
                backendTargets : patch.keySet() as List
        List<String> changedTargets

        if (environmentConfig.type == 'on-prem') {
            changedTargets = updateOnPrem(patch, environmentConfig.name, activate)
        } else {
            changedTargets = updateCloud(patch, environmentConfig.name, activate)
        }

        return [
                targets         : changedTargets.collect { targetToService[it] },
                requestedTargets: requestedTargets.collect { targetToService[it] }
        ]
    }

    void activateOnPrem(Map environmentConfig, List<String> services) {
        if (environmentConfig.type != 'on-prem' || !services) {
            return
        }

        Map onPrem = config.configuration.onPrem ?: [:]
        Map environment = onPrem.environments?.get(environmentConfig.name) ?: [:]
        Map<String, String> serviceToTarget = targetToService.collectEntries {
            target, service -> [(service): target]
        }
        List<String> targets = services.collect { serviceToTarget[it] }.findAll { it }

        restartOnPremServices(onPrem, environment, targets.unique())
    }

    private List<String> updateOnPrem(Map patch, String environment, boolean activate) {
        Map onPrem = config.configuration.onPrem ?: [:]
        Map repository = onPrem.repository ?: [:]
        Map environmentConfig = onPrem.environments?.get(environment) ?: [:]

        require(repository.repo, 'On-prem configuration repository URL')
        require(repository.credentialId, 'On-prem configuration repository credentialId')
        require(repository.branch, 'On-prem configuration repository branch')
        require(environmentConfig.path, "${environment} configuration repository path")
        require(environmentConfig.host, "${environment} host")
        require(environmentConfig.user, "${environment} SSH user")

        String workspace = "onprem-configuration-${environment}"
        List<String> targets = expandedTargets(patch.keySet() as Set)
        List<String> changedTargets = []

        script.dir(workspace) {
            script.deleteDir()
            script.git(
                    url: repository.repo,
                    branch: repository.branch,
                    credentialsId: repository.credentialId
            )

            targets.each { target ->
                Map targetConfig = target == 'common' ? onPrem.common : onPrem.services?.get(target)
                String fileName = targetConfig?.file
                require(fileName, "${target} on-prem configuration file")

                String filePath = "${environmentConfig.path}/${fileName}"
                if (!script.fileExists(filePath)) {
                    script.error("Configuration file bulunamadı: ${filePath}")
                }

                if (upsertProperties(filePath, patch[target] ?: [:])) {
                    changedTargets << target
                }
            }

            boolean changed = commitAndPush(repository, environment)

            if (!changed) {
                script.echo('Configuration repository already contains the requested values.')
            }

            deployOnPremFiles(
                    onPrem,
                    environmentConfig,
                    changedTargets,
                    activate
            )
        }

        return changedTargets.contains('common') ?
                backendTargets : changedTargets
    }

    private List<String> updateCloud(Map patch, String environment, boolean activate) {
        Map helmEnvironment = config.helm.environments?.get(environment) ?: [:]
        String repositoryName = helmEnvironment.repository
        Map repository = config.helm.repositories?.get(repositoryName) ?: [:]

        require(repository.repo, "${environment} Helm repository URL")
        require(repository.credentialId, "${environment} Helm credentialId")
        require(helmEnvironment.branch, "${environment} Helm branch")

        List<String> targets = expandedTargets(patch.keySet() as Set)
                .findAll { it != 'common' }
        if (patch.containsKey('common')) {
            targets = backendTargets
        }

        String workspace = "helm-configuration-${environment}"
        boolean changed = false
        List<String> changedTargets = []
        script.dir(workspace) {
            script.deleteDir()
            script.git(
                    url: repository.repo,
                    branch: helmEnvironment.branch,
                    credentialsId: repository.credentialId
            )

            targets.each { target ->
                String service = targetToService[target]
                Map serviceConfig = helmEnvironment.services?.get(service) ?: [:]
                require(serviceConfig.chartPath, "${target} Helm chartPath")
                require(serviceConfig.valuesFile, "${target} Helm valuesFile")

                String valuesPath = "${serviceConfig.chartPath}/${serviceConfig.valuesFile}"
                if (!script.fileExists(valuesPath)) {
                    script.error("Helm values file bulunamadı: ${valuesPath}")
                }

                Map properties = [:]
                properties.putAll(patch.common ?: [:])
                properties.putAll(patch[target] ?: [:])
                if (upsertApplicationProperties(valuesPath, properties)) {
                    changedTargets << target
                }
            }

            changed = commitAndPush(
                    repository + [branch: helmEnvironment.branch],
                    environment
            )
            if (!changed) {
                script.echo('Helm repository already contains the requested values.')
            }
        }

        if (activate) {
            deployService.syncConfiguration(
                    environment,
                    changedTargets.collect { targetToService[it] }
            )
        }

        return changedTargets
    }

    private Map<String, Map<String, String>> parsePatch(String patch) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>()
        String target = null

        patch.readLines().each { line ->
            String value = line.trim()
            if (!value) {
                return
            }
            if (value.startsWith('#')) {
                target = value.replaceFirst(/^#\s*/, '').trim().toLowerCase(Locale.ROOT)
                result[target] = new LinkedHashMap<>()
                return
            }
            int separator = value.indexOf('=')
            result[target][value.substring(0, separator).trim()] =
                    value.substring(separator + 1).trim()
        }

        return result
    }

    private boolean upsertProperties(String filePath, Map<String, String> updates) {
        if (!updates) {
            return false
        }

        String original = script.readFile(filePath)
        List<String> lines = original.readLines()
        Set<String> applied = [] as Set
        List<String> result = lines.collect { line ->
            def matcher = line =~ /^\s*([^\s#!][^=]*)\s*=.*$/
            if (!matcher.matches()) {
                return line
            }
            String key = matcher.group(1).trim()
            if (!updates.containsKey(key)) {
                return line
            }
            applied << key
            return "${key}=${updates[key]}"
        }

        updates.each { key, value ->
            if (!applied.contains(key)) {
                result << "${key}=${value}"
            }
        }

        String updated = result.join('\n') + '\n'
        if (original == updated) {
            return false
        }
        script.writeFile(file: filePath, text: updated)
        return true
    }

    private boolean upsertApplicationProperties(String filePath, Map<String, String> updates) {
        if (!updates) {
            return false
        }

        String content = script.readFile(filePath)
        String propertyKey = config.configuration.cloud?.applicationPropertiesKey ?: 'applicationProperties'
        List<String> lines = content.readLines()
        int start = lines.findIndexOf { line ->
            line ==~ /^\s*${java.util.regex.Pattern.quote(propertyKey)}:\s*[|>].*$/
        }
        if (start < 0) {
            script.error("${filePath}: '${propertyKey}: |' bloğu bulunamadı.")
        }

        int baseIndent = indentation(lines[start])
        int end = start + 1
        while (end < lines.size()) {
            String line = lines[end]
            if (line.trim() && indentation(line) <= baseIndent) {
                break
            }
            end++
        }

        List<String> propertyLines = new ArrayList<>(
                lines.subList(start + 1, end)
        )
        int propertyIndent = propertyLines.findAll { it.trim() }
                .collect { indentation(it) }
                .min() ?: baseIndent + 2
        Map<String, Integer> indexes = [:]
        propertyLines.eachWithIndex { line, index ->
            def matcher = line.trim() =~ /^([^\s#!][^=]*)\s*=.*$/
            if (matcher.matches()) {
                indexes[matcher.group(1).trim()] = index
            }
        }
        updates.each { key, value ->
            String updated = (' ' * propertyIndent) + "${key}=${value}"
            if (indexes.containsKey(key)) {
                propertyLines[indexes[key]] = updated
            } else {
                propertyLines << updated
            }
        }

        List<String> result = []
        result.addAll(lines.subList(0, start + 1))
        result.addAll(propertyLines)
        result.addAll(lines.subList(end, lines.size()))
        String updatedContent = result.join('\n') + '\n'
        if (content == updatedContent) {
            return false
        }
        script.writeFile(file: filePath, text: updatedContent)
        return true
    }

    private void deployOnPremFiles(
            Map onPrem,
            Map environment,
            List<String> targets,
            boolean activate
    ) {
        String host = environment.host
        String user = environment.user
        String remotePath = onPrem.remotePath
        String scriptsPath = onPrem.scriptsPath
        int port = (onPrem.port ?: 22) as Integer
        String buildToken = script.env.BUILD_NUMBER ?: System.currentTimeMillis().toString()

        targets.each { target ->
            Map targetConfig = target == 'common' ? onPrem.common : onPrem.services[target]
            String fileName = targetConfig.file
            String source = "${environment.path}/${fileName}"
            String temporary = "${remotePath}/.${fileName}.${buildToken}.tmp"
            String destination = "${remotePath}/${fileName}"

            script.sh """
                scp -P ${port} "${source}" "${user}@${host}:${temporary}"
                ssh -p ${port} "${user}@${host}" '
                    mv "${temporary}" "${destination}"
                '
            """
        }

        if (activate) {
            List<String> restartTargets = targets.contains('common') ? backendTargets : []
            restartTargets.addAll(targets.findAll { it != 'common' })
            restartOnPremServices(onPrem, environment, restartTargets.unique())
        }
    }

    private void restartOnPremServices(Map onPrem, Map environment, List<String> targets) {
        String host = environment.host
        String user = environment.user
        String scriptsPath = onPrem.scriptsPath
        int port = (onPrem.port ?: 22) as Integer

        targets.each { target ->
            Map service = onPrem.services[target]
            String pattern = service.processPattern
            String startScript = service.startScript
            script.sh """
                ssh -p ${port} "${user}@${host}" /bin/bash <<'REMOTE_SCRIPT'
PIDS=\$(ps -ef | grep -v grep | grep "${pattern}" | awk '{print \$2}' || true)
[ -z "\$PIDS" ] || kill -9 \$PIDS
"${scriptsPath}/${startScript}"
REMOTE_SCRIPT
            """
        }
    }

    private boolean commitAndPush(Map repository, String environment) {
        int changed = script.sh(script: 'git diff --quiet', returnStatus: true)
        if (changed == 0) {
            return false
        }
        script.withCredentials([
                script.gitUsernamePassword(
                        credentialsId: repository.credentialId,
                        gitToolName: 'git'
                )
        ]) {
            script.sh """
                git add -A
                git commit -m "config: update ${environment} via deployment orchestrator #${script.env.BUILD_NUMBER}"
                git push origin ${repository.branch}
            """
        }
        return true
    }

    private static List<String> expandedTargets(Set<String> targets) {
        return targets as List
    }

    private static int indentation(String value) {
        return value.length() - value.stripLeading().length()
    }

    private void require(def value, String label) {
        if (!value?.toString()?.trim()) {
            script.error("Eksik configuration tanımı: ${label}")
        }
    }
}
