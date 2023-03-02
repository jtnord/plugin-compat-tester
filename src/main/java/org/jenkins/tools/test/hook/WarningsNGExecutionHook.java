package org.jenkins.tools.test.hook;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.maven.model.Model;
import org.jenkins.tools.test.model.hook.BeforeExecutionContext;

/** Workaround for Warnings NG plugin since it needs execute integration tests. */
public class WarningsNGExecutionHook extends PluginWithFailsafeIntegrationTestsHook {

    @Override
    public boolean check(@NonNull BeforeExecutionContext context) {
        Model model = context.getModel();
        return "warnings-ng-parent".equals(model.getArtifactId()) // localCheckoutDir
                || "warnings-ng".equals(model.getArtifactId()); // checkout
    }
}
