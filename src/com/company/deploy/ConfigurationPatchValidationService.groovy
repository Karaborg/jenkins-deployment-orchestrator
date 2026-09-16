package com.company.deploy

import java.util.regex.Pattern

class ConfigurationPatchValidationService implements Serializable {

    private static final Pattern TARGET_PATTERN =
            ~/^#\s*([A-Za-z0-9_-]+)\s*$/

    private static final Pattern PROPERTY_PATTERN =
            ~/^\s*([^\s#=]+)\s*=\s*(.*)$/

    private final def script
    private final Set validTargets

    ConfigurationPatchValidationService(def script, Map services) {
        this.script = script
        this.validTargets = (['common'] + services.values()
                .findAll { it.type != 'frontend' }
                .collect { it.configurationTarget }
                .findAll { it }) as Set
    }

    void validate(def params) {

        if (!params.CONFIG_DEPLOY) {
            return
        }

        String patch = params.CONFIG_PATCH?.toString() ?: ''
        List<String> errors = []

        if (!patch.trim()) {
            errors << 'CONFIG_PATCH boş olamaz.'
            fail(errors)
            return
        }

        String activeTarget = null
        Set<String> declaredTargets = [] as Set
        Map<String, Set<String>> keysByTarget = [:].withDefault {
            [] as Set
        }
        Map<String, Integer> propertiesByTarget = [:].withDefault { 0 }

        patch.readLines().eachWithIndex { String line, int index ->
            int lineNumber = index + 1
            String trimmedLine = line.trim()

            if (!trimmedLine) {
                return
            }

            if (trimmedLine.startsWith('#')) {
                def targetMatcher = trimmedLine =~ TARGET_PATTERN

                if (!targetMatcher.matches()) {
                    errors << "Satır ${lineNumber}: Bölüm '# servis-adi' formatında olmalı."
                    return
                }

                String target = targetMatcher.group(1).toLowerCase(Locale.ROOT)

                if (!(target in validTargets)) {
                    errors << "Satır ${lineNumber}: Geçersiz hedef '# ${target}'. " +
                            "Geçerli değerler: ${validTargets.join(', ')}."
                    return
                }

                if (!declaredTargets.add(target)) {
                    errors << "Satır ${lineNumber}: '# ${target}' bölümü ikinci kez tanımlanmış."
                    return
                }

                activeTarget = target
                return
            }

            def propertyMatcher = trimmedLine =~ PROPERTY_PATTERN

            if (!propertyMatcher.matches()) {
                errors << "Satır ${lineNumber}: Property 'key=value' formatında olmalı."
                return
            }

            if (!activeTarget) {
                errors << "Satır ${lineNumber}: Property bir '# servis-adi' bölümünün altında olmalı."
                return
            }

            String key = propertyMatcher.group(1)

            if (!keysByTarget[activeTarget].add(key)) {
                errors << "Satır ${lineNumber}: '${key}' '# ${activeTarget}' bölümünde ikinci kez tanımlanmış."
                return
            }

            propertiesByTarget[activeTarget]++
        }

        declaredTargets.each { String target ->
            if (propertiesByTarget[target] == 0) {
                errors << "'# ${target}' bölümü en az bir property içermeli."
            }
        }

        fail(errors)
        script.echo(
                "Configuration patch validation passed for: " +
                        declaredTargets.join(', ')
        )
    }

    private void fail(List<String> errors) {

        if (errors) {
            script.error(
                    'Configuration patch validation failed:\n - ' +
                            errors.join('\n - ')
            )
        }
    }
}
