/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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

package org.ballerinax.docker.generator.models;

import com.spotify.docker.client.DockerHost;
import lombok.Data;
import org.ballerinax.docker.generator.DockerGenConstants;
import org.ballerinax.docker.generator.exceptions.DockerGenException;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

import static org.ballerinax.docker.generator.DockerGenConstants.DOCKER_API_VERSION;


/**
 * Docker annotations model class.
 */
@Data
public class DockerModel {
    private static final boolean WINDOWS_BUILD = "true".equals(System.getenv(DockerGenConstants.ENABLE_WINDOWS_BUILD));
    private String name;
    private String registry;
    private String tag;
    private boolean push;
    private String username;
    private String password;
    private boolean buildImage;
    private String baseImage;
    private Set<Integer> ports;
    private boolean enableDebug;
    private int debugPort;
    private String dockerAPIVersion;
    private String dockerHost;
    private String dockerCertPath;
    private boolean isService;
    private String uberJarFileName;
    private Set<CopyFileModel> externalFiles;
    private String commandArg;
    private String cmd;

    public DockerModel() {
        // Initialize with default values except for image name
        this.tag = "latest";
        this.push = false;
        this.buildImage = true;
        this.baseImage = WINDOWS_BUILD ? DockerGenConstants.OPENJDK_8_JRE_WINDOWS_BASE_IMAGE :
                DockerGenConstants.OPENJDK_8_JRE_ALPINE_BASE_IMAGE;
        this.enableDebug = false;
        this.debugPort = 5005;
        this.setDockerAPIVersion(System.getenv(DOCKER_API_VERSION));
        this.setDockerHost(DockerHost.fromEnv().host());
        this.setDockerCertPath(DockerHost.fromEnv().dockerCertPath());

        externalFiles = new HashSet<>();
        commandArg = "";
    }

    public void setDockerAPIVersion(String dockerAPIVersion) {
        if (null != dockerAPIVersion && !dockerAPIVersion.startsWith("v")) {
            dockerAPIVersion = "v" + dockerAPIVersion;
        }

        this.dockerAPIVersion = dockerAPIVersion;
    }

    public Set<CopyFileModel> getCopyFiles() {
        return externalFiles;
    }

    public void setCopyFiles(Set<CopyFileModel> externalFiles) throws DockerGenException {
        this.externalFiles = externalFiles;
        for (CopyFileModel externalFile : externalFiles) {
            if (!externalFile.isBallerinaConf()) {
                continue;
            }

            if (Files.isDirectory(Paths.get(externalFile.getSource()))) {
                throw new DockerGenException("invalid config file given: " + externalFile.getSource());
            }
            addCommandArg(" --b7a.config.file=" + externalFile.getTarget());
        }
    }

    public void addCommandArg(String commandArg) {
        this.commandArg += commandArg;
    }

    public String getCmd() {
        if (this.cmd == null) {
            return null;
        }

        String configFile = "";
        for (CopyFileModel externalFile : externalFiles) {
            if (!externalFile.isBallerinaConf()) {
                continue;
            }
            configFile = externalFile.getTarget();
        }

        return this.cmd
                .replace("${APP}", this.uberJarFileName)
                .replace("${CONFIG_FILE}", configFile);
    }


    public void setCmd(String cmd) {
        this.cmd = cmd;
    }

    @Override
    public String toString() {
        return "DockerModel{" +
                "name='" + name + '\'' +
                ", registry='" + registry + '\'' +
                ", tag='" + tag + '\'' +
                ", push=" + push +
                ", username='" + username + '\'' +
                ", buildImage" + "=" + buildImage +
                ", baseImage='" + baseImage + '\'' +
                ", ports=" + ports +
                ", enableDebug=" + enableDebug +
                ", debugPort=" + debugPort +
                ", dockerAPIVersion='" + dockerAPIVersion + '\'' +
                ", dockerHost='" + dockerHost + '\'' +
                ", dockerCertPath='" + dockerCertPath + '\'' +
                ", isService=" + isService +
                ", uberJarFileName='" + uberJarFileName + '\'' +
                ", externalFiles=" + externalFiles +
                ", commandArg='" + commandArg + '\'' +
                ", cmd='" + cmd + '\'' +
                '}';
    }

}
