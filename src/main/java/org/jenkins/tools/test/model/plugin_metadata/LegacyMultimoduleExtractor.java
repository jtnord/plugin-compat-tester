package org.jenkins.tools.test.model.plugin_metadata;

import java.util.Optional;
import java.util.Set;
import java.util.jar.Manifest;
import org.apache.maven.model.Model;
import org.jenkins.tools.test.exception.PluginSourcesUnavailableException;
import org.jenkins.tools.test.model.hook.HookOrder;
import org.kohsuke.MetaInfServices;

@MetaInfServices(PluginMetadataExtractor.class)
@HookOrder(order = -500)
// delete once all non standard multi module plugins are using https://github.com/jenkinsci/maven-hpi-plugin/pull/436
public class LegacyMultimoduleExtractor extends PluginMetadataExtractor {

    // delete once all non standard multi module plugins are using https://github.com/jenkinsci/maven-hpi-plugin/pull/436
    private final Set<String> groupIdsWithNameAsModule = Set.of("io.jenkins.blueocean", "io.jenkins.plugins.mina-sshd-api");

    @Override
    public Optional<PluginMetadata> extractMetadata(String pluginId, Manifest manifest, Model model)
            throws PluginSourcesUnavailableException {

        String scm = model.getScm().getConnection();
        if (scm.startsWith("scm:git:")) {
            scm = scm.substring(8);
        } else {
            throw new PluginSourcesUnavailableException(
                    "SCM URL " + scm + " is not supported by the pct - only git urls are allowed");
        }

        PluginMetadata.Builder builder = new PluginMetadata.Builder().withPluginId(model.getArtifactId()).withName(model.getName()).withScmUrl(scm).withGitCommit(model.getScm().getTag());

        String groupId = manifest.getMainAttributes().getValue("Group-Id");
        
        if (groupIdsWithNameAsModule.contains(groupId)) {
            return Optional.of(builder.withModulePath(pluginId).build());
        }
        // handle non standard aggregator projects.

        // https://github.com/jenkinsci/pipeline-model-definition-plugin
        if (Set.of("pipeline-model-api",
                "pipeline-model-definition",
                "pipeline-model-extensions",
                "pipeline-stage-tags-metadata").contains(pluginId)) {
            return Optional.of(builder.withModulePath(pluginId).build());
        }
        
        // https://github.com/jenkinsci/declarative-pipeline-migration-assistant-plugin
        if (Set.of("declarative-pipeline-migration-assistant",
                "declarative-pipeline-migration-assistant-api").contains(pluginId)) {
            return Optional.of(builder.withModulePath(pluginId).build());
        }

        // https://github.com/jenkinsci/pipeline-stage-view-plugin
        if ("pipeline-rest-api".equals(pluginId)) {
            return Optional.of(builder.withModulePath("rest-api").build());
        }
        if ("pipeline-stage-view".equals(pluginId)) {
            return Optional.of(builder.withModulePath("ui").build());
        }

        // https://github.com/jenkinsci/swarm-plugin
        if ("swarm".equals(pluginId)) {
            return Optional.of(builder.withModulePath("plugin").build());
        }

        // https://github.com/jenkinsci/warnings-ng-plugin
        if ("warnings-ng".equals(pluginId)) {
            return Optional.of(builder.withModulePath("plugin").build());
        }
        // https://github.com/jenkinsci/workflow-cps-plugin/
        if ("workflow-cps".equals(pluginId)) {
            return Optional.of(builder.withModulePath("plugin").build());
        }

        return Optional.empty();
    }
}
