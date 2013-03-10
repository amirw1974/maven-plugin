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

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Exclusion;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.sonatype.aether.util.StringUtils;
import org.whitesource.agent.api.ChecksumUtils;
import org.whitesource.agent.api.dispatch.UpdateInventoryResult;
import org.whitesource.agent.api.model.AgentProjectInfo;
import org.whitesource.agent.api.model.Coordinates;
import org.whitesource.agent.api.model.DependencyInfo;
import org.whitesource.agent.api.model.ExclusionInfo;
import org.whitesource.agent.client.WssServiceException;

import java.io.File;
import java.io.IOException;
import java.util.*;

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
        aggregator = true
)
public class UpdateMojo extends WhitesourceMojo {

    /**
     * Unique identifier of the organization to update.
     */
    @Parameter(alias = "orgToken",
            property = Constants.ORG_TOKEN,
            required = true)
    private String orgToken;

    /**
     * Optional. Unique identifier of the White Source project to update.
     * If omitted, default naming convention will apply.
     */
    @Parameter(alias = "projectToken",
            property = Constants.PROJECT_TOKEN,
            required = false)
    private String projectToken;

    /**
     * Optional. Map of module artifactId to White Source project token.
     */
    @Parameter(alias = "moduleTokens",
            property = Constants.MODULE_TOKENS,
            required = false)
    private Map<String, String> moduleTokens = new HashMap<String, String>();

    /**
     * Optional. Map of module artifactId to White Source project token.
     */
    @Parameter(alias = "specialModuleTokens",
            property = Constants.SPECIAL_MODULE_TOKENS,
            required = false)
    private Properties specialModuleTokens = new Properties();

    /**
     * Optional. Set to true to ignore this maven project. Overrides any include patterns.
     */
    @Parameter(alias = "ignore",
            property = Constants.IGNORE,
            required = false,
            defaultValue = "false")
    private boolean ignore;

    /**
     * Optional. Only modules with an artifactId matching one of these patterns will be processed by the plugin.
     */
    @Parameter(alias = "includes",
            property = Constants.INCLUDES,
            required = false,
            defaultValue = "")
    private String[] includes;

    /**
     * Optional. Modules with an artifactId matching any of these patterns will not be processed by the plugin.
     */
    @Parameter(alias = "excludes",
            property = Constants.EXCLUDES,
            required = false,
            defaultValue = "")
    private String[] excludes;

    /**
     * Optional. Set to true to ignore this maven modules of type pom.
     */
    @Parameter(alias = "ignorePomModules",
            property = Constants.IGNORE_POM_MODULES,
            required = false,
            defaultValue = "true")
    private boolean ignorePomModules;

    @Parameter(defaultValue = "${reactorProjects}",
            required = true,
            readonly = true)
    private Collection<MavenProject> reactorProjects;

    /* --- Concrete implementation methods --- */

    @Override
    public void doExecute() throws MojoExecutionException, MojoFailureException {
        if (reactorProjects == null) {
            info("No projects found. Skipping update");
            return;
        }

        init();

        // Collect OSS usage information
        Collection<AgentProjectInfo> projectInfos = new ArrayList<AgentProjectInfo>();
        for (MavenProject project : reactorProjects) {
            if (shouldProcess(project)) {
                projectInfos.add(processProject(project));
            } else {
                info("skipping " + project.getId());
            }
        }
        debugProjectInfos(projectInfos);

        // send to white source
        try {
            final UpdateInventoryResult result = service.update(orgToken, projectInfos);
            logResult(result);
        } catch (WssServiceException e) {
            throw new MojoExecutionException(Constants.ERROR_SERVICE_CONNECTION + e.getMessage(), e);
        }
    }

    private void init() {
        // copy token for modules with special names into moduleTokens.
        for (Map.Entry<Object, Object> entry : specialModuleTokens.entrySet()) {
            moduleTokens.put(entry.getKey().toString(), entry.getValue().toString());
        }
    }

    private boolean shouldProcess(MavenProject project) {
        if (project == null) {
            return false;
        }

        boolean process = true;

        if (ignorePomModules && "pom".equals(project.getPackaging())) {
            process = false;
        } else if (project.equals(mavenProject)) {
            process = !ignore;
        } else if (excludes.length > 0 && matchAny(project.getArtifactId(), excludes)) {
            process = false;
        } else if (includes.length > 0 && matchAny(project.getArtifactId(), includes)) {
            process = true;
        }

        return process;
    }

    private boolean matchAny(String value, String[] patterns) {
        boolean match = false;

        if (!StringUtils.isEmpty(value)) {
            for (String pattern : patterns) {
                if (!StringUtils.isEmpty(pattern)) {
                    String regex = pattern.replace(".", "\\.").replace("*", ".*");
                    if (value.matches(regex)) {
                        match = true;
                        break;
                    }
                }
            }
        }

        return match;
    }

    private AgentProjectInfo processProject(MavenProject project) {
        long startTime = System.currentTimeMillis();

        info("processing " + project.getId());

        AgentProjectInfo projectInfo = new AgentProjectInfo();

        // project token
        if (project.equals(mavenProject)) {
            projectInfo.setProjectToken(projectToken);
        } else {
            projectInfo.setProjectToken(moduleTokens.get(project.getArtifactId()));
        }

        // project coordinates
        projectInfo.setCoordinates(extractCoordinates(project));

        // parent coordinates
        if (project.hasParent()) {
            projectInfo.setParentCoordinates(extractCoordinates(project.getParent()));
        }

        // dependencies
        Map<Dependency, Artifact> lut = createLookupTable(project);
        for (Dependency dependency : project.getDependencies()) {
            DependencyInfo dependencyInfo = getDependencyInfo(dependency);

            Artifact artifact = lut.get(dependency);
            if (artifact != null) {
                File artifactFile = artifact.getFile();
                if (artifactFile != null && artifactFile.exists()) {
                    try {
                        dependencyInfo.setSha1(ChecksumUtils.calculateSHA1(artifactFile));
                    } catch (IOException e) {
                        debug(Constants.ERROR_SHA1 + " for " + artifact.getId());
                    }
                }
            }

            projectInfo.getDependencies().add(dependencyInfo);
        }

        info("Total processing time is " + (System.currentTimeMillis() - startTime) + " [msec]");

        return projectInfo;
    }

    private Coordinates extractCoordinates(MavenProject mavenProject) {
        return new Coordinates(mavenProject.getGroupId(),
                mavenProject.getArtifactId(),
                mavenProject.getVersion());
    }

    private Map<Dependency, Artifact> createLookupTable(MavenProject project) {
        Map<Dependency, Artifact> lut = new HashMap<Dependency, Artifact>();

        for (Dependency dependency : project.getDependencies()) {
            for (Artifact dependencyArtifact : project.getDependencyArtifacts()) {
                if (match(dependency, dependencyArtifact)) {
                    lut.put(dependency, dependencyArtifact);
                }
            }
        }

        return lut;
    }

    private boolean match(Dependency dependency, Artifact artifact) {
        boolean match = dependency.getGroupId().equals(artifact.getGroupId()) &&
                dependency.getArtifactId().equals(artifact.getArtifactId()) &&
                dependency.getVersion().equals(artifact.getVersion());

        if (match) {
            if (dependency.getClassifier() == null) {
                match = artifact.getClassifier() == null;
            } else {
                match = dependency.getClassifier().equals(artifact.getClassifier());
            }
        }

        if (match) {
            String type = artifact.getType();
            if (dependency.getType() == null) {
                match = type == null || type.equals("jar");
            } else {
                match = dependency.getType().equals(type);
            }
        }

        return match;
    }

    private DependencyInfo getDependencyInfo(Dependency dependency) {
        DependencyInfo info = new DependencyInfo();

        // dependency data
        info.setGroupId(dependency.getGroupId());
        info.setArtifactId(dependency.getArtifactId());
        info.setVersion(dependency.getVersion());
        info.setScope(dependency.getScope());
        info.setClassifier(dependency.getClassifier());
        info.setOptional(dependency.isOptional());
        info.setType(dependency.getType());
        info.setSystemPath(dependency.getSystemPath());

        // exclusions
        Collection<ExclusionInfo> exclusions = info.getExclusions();
        for (Exclusion exclusion : dependency.getExclusions()) {
            exclusions.add(new ExclusionInfo(exclusion.getGroupId(), exclusion.getArtifactId()));
        }

        return info;
    }

    private void logResult(UpdateInventoryResult result) {
        info("Inventory update results for " + result.getOrganization());

        // newly created projects
        Collection<String> createdProjects = result.getCreatedProjects();
        if (createdProjects.isEmpty()) {
            info("No new projects found.");
        } else {
            info("Newly created projects:");
            for (String projectName : createdProjects) {
                info(projectName);
            }
        }

        // updated projects
        Collection<String> updatedProjects = result.getUpdatedProjects();
        if (updatedProjects.isEmpty()) {
            info("No projects were updated.");
        } else {
            info("Updated projects:");
            for (String projectName : updatedProjects) {
                info(projectName);
            }
        }
    }

    public void debugProjectInfos(Collection<AgentProjectInfo> projectInfos) {
        debug("----------------- dumping projectInfos -----------------");
        debug("Total number of projects : " + projectInfos.size());

        for (AgentProjectInfo projectInfo : projectInfos) {
            debug("Project coordiantes: " + projectInfo.getCoordinates().toString());
            debug("Project parent coordiantes: " + (projectInfo.getParentCoordinates() == null ? "" : projectInfo.getParentCoordinates().toString()));
            debug("Project token: " + projectInfo.getProjectToken());
            debug("total # of dependencies: " + projectInfo.getDependencies().size());
            for (DependencyInfo info : projectInfo.getDependencies()) {
                debug(info.toString() + " SHA-1: " + info.getSha1());
            }
        }

        debug("----------------- dump finished -----------------");
    }

}
