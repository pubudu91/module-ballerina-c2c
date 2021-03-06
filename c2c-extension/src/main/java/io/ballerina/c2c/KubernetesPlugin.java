/*
 * Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.c2c;

import io.ballerina.c2c.diagnostics.TomlDiagnosticChecker;
import io.ballerina.c2c.exceptions.KubernetesPluginException;
import io.ballerina.c2c.models.KubernetesContext;
import io.ballerina.c2c.models.KubernetesDataHolder;
import io.ballerina.c2c.utils.KubernetesUtils;
import io.ballerina.c2c.utils.TomlHelper;
import io.ballerina.projects.CloudToml;
import io.ballerina.projects.JBallerinaBackend;
import io.ballerina.projects.JvmTarget;
import io.ballerina.projects.PackageCompilation;
import io.ballerina.projects.Project;
import io.ballerina.projects.internal.model.Target;
import io.ballerina.toml.api.Toml;
import io.ballerina.toml.validator.TomlValidator;
import io.ballerina.toml.validator.schema.Schema;
import io.ballerina.tools.diagnostics.Diagnostic;
import org.apache.commons.io.IOUtils;
import org.ballerinalang.compiler.plugins.AbstractCompilerPlugin;
import org.ballerinalang.compiler.plugins.SupportedAnnotationPackages;
import org.ballerinalang.model.elements.PackageID;
import org.ballerinalang.util.diagnostic.DiagnosticLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.MissingResourceException;
import java.util.Optional;

import static io.ballerina.c2c.KubernetesConstants.DOCKER;
import static io.ballerina.c2c.KubernetesConstants.KUBERNETES;
import static io.ballerina.c2c.utils.KubernetesUtils.printError;
import static org.ballerinax.docker.generator.utils.DockerGenUtils.extractJarName;

/**
 * Compiler plugin to generate kubernetes artifacts.
 */
@SupportedAnnotationPackages(
        value = {"ballerina/cloud"}
)
public class KubernetesPlugin extends AbstractCompilerPlugin {

    private static final Logger pluginLog = LoggerFactory.getLogger(KubernetesPlugin.class);
    private DiagnosticLog dlog;

    @Override
    public void init(DiagnosticLog diagnosticLog) {
        this.dlog = diagnosticLog;
    }

    @Override
    public List<Diagnostic> codeAnalyze(Project project) {
        String cloud = project.buildOptions().cloud();
        if (cloud == null || !isSupportedBuildOption(cloud)) {
            return Collections.emptyList();
        }
        TomlDiagnosticChecker tomlDiagnosticChecker = new TomlDiagnosticChecker(project);
        Optional<CloudToml> cloudToml = project.currentPackage().cloudToml();
        if (cloudToml.isEmpty()) {
            return Collections.emptyList();
        }
        Toml toml = TomlHelper.createK8sTomlFromProject(cloudToml.get().tomlDocument());
        TomlValidator validator = new TomlValidator(Schema.from(getValidationSchema()));
        validator.validate(toml);
        List<Diagnostic> diagnostics = toml.diagnostics();

        diagnostics.addAll(tomlDiagnosticChecker.validateTomlWithSource(toml));
        return diagnostics;
    }

    public void codeGeneratedInternal(PackageID packageId, Path executableJarFile, Optional<CloudToml> cloudToml,
                                      String buildType) {
        KubernetesContext.getInstance().setCurrentPackage(packageId);
        KubernetesDataHolder dataHolder = KubernetesContext.getInstance().getDataHolder();
        dataHolder.setPackageID(packageId);
        executableJarFile = executableJarFile.toAbsolutePath();
        if (null != executableJarFile.getParent() && Files.exists(executableJarFile.getParent())) {
            // artifacts location for a single bal file.
            Path kubernetesOutputPath = executableJarFile.getParent().resolve(KUBERNETES);
            Path dockerOutputPath = executableJarFile.getParent().resolve(DOCKER);
            if (null != executableJarFile.getParent().getParent().getParent() &&
                    Files.exists(executableJarFile.getParent().getParent().getParent())) {
                // if executable came from a ballerina project
                Path projectRoot = executableJarFile.getParent().getParent().getParent();
                if (Files.exists(projectRoot.resolve("Ballerina.toml"))) {
                    kubernetesOutputPath = projectRoot.resolve("target")
                            .resolve(KUBERNETES)
                            .resolve(extractJarName(executableJarFile));
                    dockerOutputPath = projectRoot.resolve("target")
                            .resolve(DOCKER)
                            .resolve(extractJarName(executableJarFile));
                    //Read and parse ballerina cloud
                    cloudToml.ifPresent(
                            kubernetesToml -> dataHolder.setBallerinaCloud(new Toml(kubernetesToml.tomlAstNode())));
                }
            }
            dataHolder.setJarPath(executableJarFile);
            dataHolder.setK8sArtifactOutputPath(kubernetesOutputPath);
            dataHolder.setDockerArtifactOutputPath(dockerOutputPath);
            ArtifactManager artifactManager = new ArtifactManager();
            try {
                KubernetesUtils.deleteDirectory(kubernetesOutputPath);
                artifactManager.populateDeploymentModel();
                artifactManager.createArtifacts(buildType);
            } catch (KubernetesPluginException e) {
                String errorMessage = "module [" + packageId + "] " + e.getMessage();
                printError(errorMessage);
                pluginLog.error(errorMessage, e);
                try {
                    KubernetesUtils.deleteDirectory(kubernetesOutputPath);
                } catch (KubernetesPluginException ignored) {
                    //ignored
                }
            }
        } else {
            printError("error in resolving docker generation location.");
            pluginLog.error("error in resolving docker generation location.");
        }
    }

    @Override
    public void codeGenerated(Project project, Target target) {
        if (project.buildOptions().cloud() == null || !isSupportedBuildOption(project.buildOptions().cloud())) {
            return;
        }
        PackageCompilation packageCompilation = project.currentPackage().getCompilation();
        JBallerinaBackend jBallerinaBackend = JBallerinaBackend.from(packageCompilation, JvmTarget.JAVA_11);
        io.ballerina.projects.JarResolver jarResolver = jBallerinaBackend.jarResolver();

        KubernetesDataExtractor dataExtractor = new KubernetesDataExtractor(project);
        try {
            dataExtractor.packageAnalysis();
        } catch (KubernetesPluginException e) {
            printError(e.getMessage());
            return;
        }

        KubernetesContext.getInstance().getDataHolder().getDockerModel()
                .addDependencyJarPaths(new HashSet<>(jarResolver.getJarFilePathsRequiredForExecution()));
        try {
            final Path executablePath = target.getExecutablePath(project.currentPackage());
            KubernetesContext.getInstance().getDataHolder().setSourceRoot(executablePath.getParent()
                    .getParent().getParent());
            codeGeneratedInternal(KubernetesContext.getInstance().getCurrentPackage(),
                    executablePath, project.currentPackage().cloudToml(), project.buildOptions().cloud());
        } catch (IOException e) {
            String errorMessage = "error while accessing executable path " + e.getMessage();
            printError(errorMessage);
            pluginLog.error(errorMessage, e);
        }
    }

    private boolean isSupportedBuildOption(String buildOption) {
        switch (buildOption) {
            case "k8s":
            case "docker":
                return true;
        }
        return false;
    }

    private String getValidationSchema() {
        try {
            InputStream inputStream =
                    getClass().getClassLoader().getResourceAsStream("c2c-schema.json");
            if (inputStream == null) {
                throw new MissingResourceException("Schema Not found", "c2c-schema.json", "");
            }
            StringWriter writer = new StringWriter();
            IOUtils.copy(inputStream, writer, StandardCharsets.UTF_8.name());
            return writer.toString();
        } catch (IOException e) {
            throw new MissingResourceException("Schema Not found", "c2c-schema.json", "");
        }
    }

}
