/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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

package org.ballerinax.docker.generator;

import com.google.common.base.Optional;
import com.spotify.docker.client.DefaultDockerClient;
import com.spotify.docker.client.DefaultDockerClient.Builder;
import com.spotify.docker.client.DockerCertificates;
import com.spotify.docker.client.DockerCertificatesStore;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.DockerHost;
import com.spotify.docker.client.exceptions.DockerCertificateException;
import com.spotify.docker.client.exceptions.DockerException;
import com.spotify.docker.client.messages.RegistryAuth;
import org.apache.commons.io.FilenameUtils;
import org.ballerinax.docker.generator.exceptions.DockerGenException;
import org.ballerinax.docker.generator.models.CopyFileModel;
import org.ballerinax.docker.generator.models.DockerModel;
import org.ballerinax.docker.generator.utils.DockerGenUtils;
import org.glassfish.jersey.internal.RuntimeDelegateImpl;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import javax.ws.rs.ext.RuntimeDelegate;

import static org.ballerinax.docker.generator.DockerGenConstants.EXECUTABLE_JAR;
import static org.ballerinax.docker.generator.DockerGenConstants.REGISTRY_SEPARATOR;
import static org.ballerinax.docker.generator.DockerGenConstants.TAG_SEPARATOR;
import static org.ballerinax.docker.generator.utils.DockerGenUtils.cleanErrorMessage;
import static org.ballerinax.docker.generator.utils.DockerGenUtils.copyFileOrDirectory;
import static org.ballerinax.docker.generator.utils.DockerGenUtils.isBlank;
import static org.ballerinax.docker.generator.utils.DockerGenUtils.printDebug;

/**
 * Generates Docker artifacts from annotations.
 */
public class DockerArtifactHandler {
    
    private static final boolean WINDOWS_BUILD = "true".equals(System.getenv(DockerGenConstants.ENABLE_WINDOWS_BUILD));
    private final CountDownLatch pushDone = new CountDownLatch(1);
    private final CountDownLatch buildDone = new CountDownLatch(1);
    private DockerModel dockerModel;
    
    public DockerArtifactHandler(DockerModel dockerModel) {
        RuntimeDelegate.setInstance(new RuntimeDelegateImpl());
    
        String registry = dockerModel.getRegistry();
        String imageName = dockerModel.getName();
        imageName = !isBlank(registry) ? registry + REGISTRY_SEPARATOR + imageName + TAG_SEPARATOR
                                         + dockerModel.getTag() :
                    imageName + TAG_SEPARATOR + dockerModel.getTag();
        dockerModel.setName(imageName);
        
        this.dockerModel = dockerModel;
    }
    
    public void createArtifacts(PrintStream outStream, String logAppender, Path uberJarFilePath, Path outputDir)
            throws DockerGenException {
        String dockerContent;
        if (!WINDOWS_BUILD) {
            dockerContent = generateDockerfile();
        } else {
            dockerContent = generateDockerfileForWindows();
        }
        try {
            String logStepCount = dockerModel.isPush() ? "3" : "2";
            outStream.print(logAppender + " - complete 0/" + logStepCount + " \r");
            DockerGenUtils.writeToFile(dockerContent, outputDir.resolve("Dockerfile"));
            outStream.print(logAppender + " - complete 1/" + logStepCount + " \r");
            Path uberJarLocation = outputDir.resolve(DockerGenUtils.extractUberJarName(uberJarFilePath) +
                                                     EXECUTABLE_JAR);
            copyFileOrDirectory(uberJarFilePath, uberJarLocation);
            for (CopyFileModel copyFileModel : dockerModel.getCopyFiles()) {
                // Copy external files to docker folder
                Path target = outputDir.resolve(Paths.get(copyFileModel.getSource()).getFileName());
                Path sourcePath = Paths.get(copyFileModel.getSource());
                if (!sourcePath.isAbsolute()) {
                    sourcePath = sourcePath.toAbsolutePath();
                }
                copyFileOrDirectory(sourcePath, target);
                
            }
            //check image build is enabled.
            if (dockerModel.isBuildImage()) {
                buildImage(dockerModel, outputDir);
                outStream.print(logAppender + " - complete 2/" + logStepCount + " \r");
                Files.delete(uberJarLocation);
                //push only if image push is enabled.
                if (dockerModel.isPush()) {
                    pushImage(dockerModel);
                    outStream.print(logAppender + " - complete 3/" + logStepCount + " \r");
                }
            }
        } catch (IOException e) {
            throw new DockerGenException("unable to write content to " + outputDir);
        } catch (InterruptedException e) {
            throw new DockerGenException("unable to create Docker images " + e.getMessage());
        }
    }
    
    private DockerClient createClient() throws DockerGenException {
        printDebug("docker client host: " + dockerModel.getDockerHost());
        printDebug("docker client certs: " + dockerModel.getDockerCertPath());
        printDebug("docker API version: " + dockerModel.getDockerAPIVersion());
        Builder builder;
        
        try {
            Optional<DockerCertificatesStore> certsOptional = Optional.absent();
            if (null != dockerModel.getDockerCertPath() && Files.exists(Paths.get(dockerModel.getDockerCertPath()))) {
                certsOptional = DockerCertificates.builder()
                        .dockerCertPath(Paths.get(dockerModel.getDockerCertPath()))
                        .build();
            }
    
            builder = DefaultDockerClient.builder()
                    .uri(DockerHost.from(dockerModel.getDockerHost(), dockerModel.getDockerCertPath()).uri())
                    .dockerCertificates(certsOptional.orNull());
        
        } catch (DockerCertificateException e) {
            throw new DockerGenException("unable to create Docker images " + e.getMessage());
        }
    
        if (dockerModel.getDockerAPIVersion() != null) {
            builder = builder.apiVersion(dockerModel.getDockerAPIVersion());
        }
        return builder.build();
    }
    
    /**
     * Create docker image.
     *
     * @param dockerModel dockerModel object
     * @param dockerDir   dockerfile directory
     * @throws InterruptedException When error with docker build process
     */
    public void buildImage(DockerModel dockerModel, Path dockerDir) throws InterruptedException, DockerGenException {
        final DockerError dockerError = new DockerError();
        try {
            DockerClient client = this.createClient();
    
            printDebug("creating docker image `" + dockerModel.getName() + "` from directory `" + dockerDir + "`.");
            client.build(dockerDir, dockerModel.getName(), message -> {
                String buildImageId = message.buildImageId();
                String error = message.error();
                if (null != message.stream()) {
                    printDebug("[stream] " + message.stream());
                }
    
                if (null != message.progress()) {
                    printDebug("[progress] " + message.progress());
                }
    
                // when an image is built successfully.
                if (null != buildImageId) {
                    printDebug("build ID: " + buildImageId);
                    buildDone.countDown();
                }
    
                // when there is an error.
                if (null != error) {
                    printDebug("[error]: " + error);
                    dockerError.setErrorMsg("unable to build docker image: " + cleanErrorMessage(error));
                    buildDone.countDown();
                }
            }, DockerClient.BuildParam.noCache(), DockerClient.BuildParam.forceRm());
        } catch (DockerException e) {
            dockerError.setErrorMsg("unable to connect to server: " + cleanErrorMessage(e.getMessage()));
            buildDone.countDown();
        } catch (IOException ioEx) {
            dockerError.setErrorMsg("unknown I/O error occurred with docker: " + cleanErrorMessage(ioEx.getMessage()));
            buildDone.countDown();
        } catch (RuntimeException e) {
            // ignore, as this error would already be set to the dockerError variable.
        }
        buildDone.await();
        handleError(dockerError);
    }

    private void handleError(DockerError dockerError) throws DockerGenException {
        if (dockerError.isError()) {
            throw new DockerGenException(dockerError.getErrorMsg());
        }
    }
    
    /**
     * Push docker image.
     *
     * @param dockerModel DockerModel
     * @throws InterruptedException When error with docker build process
     */
    public void pushImage(DockerModel dockerModel) throws InterruptedException, DockerGenException {
        final DockerError dockerError = new DockerError();
    
        RegistryAuth auth = RegistryAuth.builder()
                .username(dockerModel.getUsername())
                .password(dockerModel.getPassword())
                .build();
        
        try {
            DockerClient client = this.createClient();
            
            client.push(dockerModel.getName(), message -> {
                String digest = message.digest();
                String error = message.error();
    
                if (null != message.progress()) {
                    printDebug("[progress] " + message.progress());
                }
                
                // When image is successfully built.
                if (null != digest) {
                    printDebug("digest: " + digest);
                    pushDone.countDown();
                }
                
                // When error occurs.
                if (null != error) {
                    printDebug("[error]: " + error);
                    dockerError.setErrorMsg("unable to push Docker image: " + error);
                    pushDone.countDown();
                }
            }, auth);
        } catch (DockerException e) {
            dockerError.setErrorMsg("unable to connect to server: " + cleanErrorMessage(e.getMessage()));
            pushDone.countDown();
        }
        pushDone.await();
        handleError(dockerError);
    }
    
    /**
     * Generate Dockerfile content.
     *
     * @return Dockerfile content as a string
     */
    private String generateDockerfile() {
        StringBuilder dockerfileContent = new StringBuilder();
        dockerfileContent.append("# Auto Generated Dockerfile\n");
        dockerfileContent.append("FROM ").append(dockerModel.getBaseImage()).append("\n");
        dockerfileContent.append("\n");
        dockerfileContent.append("LABEL maintainer=\"dev@ballerina.io\"").append("\n");
        dockerfileContent.append("\n");
        
        if (this.dockerModel.getBaseImage().equals(DockerGenConstants.OPENJDK_8_JRE_ALPINE_BASE_IMAGE)) {
            dockerfileContent.append("RUN addgroup troupe \\").append("\n");
            dockerfileContent.append("    && adduser -S -s /bin/bash -g 'ballerina' -G troupe -D ballerina \\")
                    .append("\n");
            dockerfileContent.append("    && apk add --update --no-cache bash \\").append("\n");
            dockerfileContent.append("    && chown -R ballerina:troupe /usr/bin/java \\").append("\n");
            dockerfileContent.append("    && rm -rf /var/cache/apk/*").append("\n");
            dockerfileContent.append("\n");
        }
        
        dockerfileContent.append("WORKDIR /home/ballerina").append("\n");
        dockerfileContent.append("\n");
        dockerfileContent.append("COPY ").append(dockerModel.getUberJarFileName()).append(" /home/ballerina")
                                                                                                        .append("\n");
        
        dockerModel.getCopyFiles().forEach(file -> {
            // Extract the source filename relative to docker folder.
            String sourceFileName = String.valueOf(Paths.get(file.getSource()).getFileName());
            dockerfileContent.append("COPY ")
                    .append(sourceFileName)
                    .append(" ")
                    .append(file.getTarget())
                    .append("\n");
        });
        
        dockerfileContent.append("\n");
        
        if (dockerModel.isService() && dockerModel.getPorts().size() > 0) {
            dockerfileContent.append("EXPOSE ");
            dockerModel.getPorts().forEach(port -> dockerfileContent.append(" ").append(port));
        }
        dockerfileContent.append("\n");
        if (this.dockerModel.getBaseImage().equals(DockerGenConstants.OPENJDK_8_JRE_ALPINE_BASE_IMAGE)) {
            dockerfileContent.append("USER ballerina").append("\n");
            dockerfileContent.append("\n");
        }
        
        if (null == this.dockerModel.getCmd() || "".equals(this.dockerModel.getCmd())) {
            dockerfileContent.append("CMD java -jar ").append(dockerModel.getUberJarFileName());
    
            if (!DockerGenUtils.isBlank(dockerModel.getCommandArg())) {
                dockerfileContent.append(dockerModel.getCommandArg());
            }
    
            if (dockerModel.isEnableDebug()) {
                dockerfileContent.append(" --debug ").append(dockerModel.getDebugPort());
            }
        } else {
            dockerfileContent.append(this.dockerModel.getCmd());
        }
        
        dockerfileContent.append("\n");
        
        return dockerfileContent.toString();
    }

    private String generateDockerfileForWindows() {
        String dockerBase = "# Auto Generated Dockerfile\n" +
                            "\n" +
                            "FROM " + dockerModel.getBaseImage() + "\n" +
                            "LABEL maintainer=\"dev@ballerina.io\"\n" +
                            "\n" +
                            "COPY " + dockerModel.getUberJarFileName() + " C:\\\\ballerina\\\\home \n\n";

        StringBuilder stringBuilder = new StringBuilder(dockerBase);
        dockerModel.getCopyFiles().forEach(file -> {
            // Extract the source filename relative to docker folder.
            String sourceFileName = String.valueOf(Paths.get(file.getSource()).getFileName());
            stringBuilder.append("COPY ")
                    .append(FilenameUtils.separatorsToWindows(sourceFileName))
                    .append(" ")
                    .append(FilenameUtils.separatorsToWindows(file.getTarget()))
                    .append("\n");
        });

        if (dockerModel.isService() && dockerModel.getPorts().size() > 0) {
            stringBuilder.append("EXPOSE ");
            dockerModel.getPorts().forEach(port -> stringBuilder.append(" ").append(port));
        }
    
        stringBuilder.append("\n");
    
        if (null == this.dockerModel.getCmd() || "".equals(this.dockerModel.getCmd())) {
            stringBuilder.append("\nCMD java -jar ").append(dockerModel.getUberJarFileName());
    
            if (!DockerGenUtils.isBlank(dockerModel.getCommandArg())) {
                stringBuilder.append(dockerModel.getCommandArg());
            }
    
            if (dockerModel.isEnableDebug()) {
                stringBuilder.append(" --debug ").append(dockerModel.getDebugPort());
            }
        } else {
            stringBuilder.append(this.dockerModel.getCmd());
        }
        
        stringBuilder.append("\n");

        return stringBuilder.toString();
    }

    /**
     * Class to hold docker errors.
     */
    private static class DockerError {
        private boolean error;
        private String errorMsg;

        DockerError() {
            this.error = false;
        }

        boolean isError() {
            return error;
        }

        String getErrorMsg() {
            return errorMsg;
        }

        void setErrorMsg(String errorMsg) {
            this.error = true;
            this.errorMsg = errorMsg;
        }
    }
}
