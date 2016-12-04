/**
 * Copyright (C) 2011 White Source Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.whitesource.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DependencyResolutionException;
import org.whitesource.agent.api.dispatch.CheckPolicyComplianceResult;
import org.whitesource.agent.api.dispatch.UpdateInventoryResult;
import org.whitesource.agent.api.model.AgentProjectInfo;
import org.whitesource.agent.client.WssServiceException;

import java.util.Collection;

/**
 * Send updates of open source software usage information to White Source.
 *
 * <p>
 *     Further documentation for the plugin and its usage can be found in the
 *     <a href="http://docs.whitesourcesoftware.com/display/serviceDocs/Maven+plugin">online documentation</a>.
 * </p>
 *
 * @author Edo.Shor
 */
@Mojo(name = "update",
        defaultPhase = LifecyclePhase.PACKAGE,
        requiresDependencyResolution = ResolutionScope.TEST,
        aggregator = true )
public class UpdateMojo extends AgentMojo {

    /* --- Members --- */

    /**
     * Optional. Set to true to check policies.
     */
    @Parameter( alias = "checkPolicies", property = Constants.CHECK_POLICIES, required = false, defaultValue = "false")
    private boolean checkPolicies;

    /**
     * Optional. Set to true to force check policies for all dependencies.
     * If set to false policies will be checked only for new dependencies introduced to the WhiteSource projects.
     *
     * Important: Only used if {@link UpdateMojo#checkPolicies} is set to true.
     */
    @Parameter( alias = "forceCheckAllDependencies", property = Constants.FORCE_CHECK_ALL_DEPENDENCIES, required = false, defaultValue = "false")
    private boolean forceCheckAllDependencies;

    /* --- Constructors --- */

    public UpdateMojo() {
    }

    /* --- Concrete implementation methods --- */

    @Override
    public void doExecute() throws MojoExecutionException, MojoFailureException, DependencyResolutionException {
        if (reactorProjects == null) {
            info("No Projects Found. Skipping Update");
            return;
        }

        // initialize
        init();

        // Collect OSS usage information
        Collection<AgentProjectInfo> projectInfos = extractProjectInfos();

        // send to white source
        if (projectInfos == null || projectInfos.isEmpty()) {
            info("No open source information found.");
        } else {
            sendUpdate(projectInfos);
        }
    }

    /* --- Private methods --- */

    protected void init() {
        super.init();
        checkPolicies = Boolean.parseBoolean(session.getSystemProperties().getProperty(
                Constants.CHECK_POLICIES, Boolean.toString(checkPolicies)));
        forceCheckAllDependencies = Boolean.parseBoolean(session.getSystemProperties().getProperty(
                Constants.FORCE_CHECK_ALL_DEPENDENCIES, Boolean.toString(forceCheckAllDependencies)));
    }

    private void sendUpdate(Collection<AgentProjectInfo> projectInfos) throws MojoFailureException, MojoExecutionException {
        try {
            UpdateInventoryResult updateResult;
            if (checkPolicies) {
                info("Checking Policies");
                CheckPolicyComplianceResult result = service.checkPolicyCompliance(
                        orgToken, product, productVersion, projectInfos, forceCheckAllDependencies);

                if (outputDirectory == null ||
                        (!outputDirectory.exists() && !outputDirectory.mkdirs())) {
                    warn("Output directory doesn't exist. Skipping policies check report.");
                } else {
                    generateReport(result);
                }

                if (result.hasRejections()) {
                    String msg = "Some dependencies were rejected by the organization's policies.";
                    throw new MojoExecutionException(msg); // this is handled in base class
                } else {
                    info("All dependencies conform with the organization's policies.");
                    info("Sending Update Request to WhiteSource");
                    updateResult = service.update(orgToken, requesterEmail, product, productVersion, projectInfos);
                }
            } else {
                info("Sending Update Request to WhiteSource");
                updateResult = service.update(orgToken, requesterEmail, product, productVersion, projectInfos);
            }
            logResult(updateResult);
        } catch (WssServiceException e) {
            throw new MojoExecutionException(Constants.ERROR_SERVICE_CONNECTION + e.getMessage(), e);
        }
    }

    private void logResult(UpdateInventoryResult result) {
        info("");
        info("------------------------------------------------------------------------");
        info("Inventory Update Result for " + result.getOrganization());
        info("------------------------------------------------------------------------");

        // newly created projects
        Collection<String> createdProjects = result.getCreatedProjects();
        if (createdProjects.isEmpty()) {
//            info("No new projects found.");
        } else {
            info("");
            info("Newly Created Projects:");
            for (String projectName : createdProjects) {
                info("* " + projectName);
            }
        }

        // updated projects
        Collection<String> updatedProjects = result.getUpdatedProjects();
        if (updatedProjects.isEmpty()) {
//            info("No projects were updated.");
        } else {
            info("");
            info("Updated Projects:");
            for (String projectName : updatedProjects) {
                info("* " + projectName);
            }
        }
        info("");
    }

}
