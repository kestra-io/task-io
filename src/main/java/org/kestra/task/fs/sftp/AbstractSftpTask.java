package org.kestra.task.fs.sftp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.provider.sftp.IdentityInfo;
import org.apache.commons.vfs2.provider.sftp.SftpFileSystemConfigBuilder;
import org.kestra.core.exceptions.IllegalVariableEvaluationException;
import org.kestra.core.models.annotations.PluginProperty;
import org.kestra.core.runners.RunContext;
import org.kestra.task.fs.AbstractVfsTask;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractSftpTask extends AbstractVfsTask implements AbstractSftpInterface {
    @Schema(
        title = "Username on the remote server"
    )
    @PluginProperty(dynamic = true)
    protected String username;

    @Schema(
        title = "Password on the remote server"
    )
    @PluginProperty(dynamic = true)
    protected String password;

    @Schema(
        title = "Private keyfile to login on the source server with ssh",
        description = "Must be the ssh key content, not a path"
    )
    @PluginProperty(dynamic = true)
    protected String keyfile;

    @Schema(
        title = "Passphrase of the ssh key"
    )
    @PluginProperty(dynamic = true)
    protected String passphrase;

    @Schema(
        title = "Sftp proxy host"
    )
    @PluginProperty(dynamic = true)
    protected String proxyHost;

    @Schema(
        title = "Sftp proxy port"
    )
    @PluginProperty(dynamic = true)
    protected Integer proxyPort;

    @Schema(
        title = "Sftp proxy user"
    )
    @PluginProperty(dynamic = true)
    protected String proxyUser;

    @Schema(
        title = "Sftp proxy password"
    )
    @PluginProperty(dynamic = true)
    protected String proxyPassword;

    @Schema(
        title = "Sftp proxy type"
    )
    @PluginProperty(dynamic = true)
    protected String proxyType;

    @Schema(
        title = "Is path is relative to root dir"
    )
    @PluginProperty(dynamic = true)
    @Builder.Default
    protected Boolean rootDir = true;

    static String basicAuth(RunContext runContext, String username, String password) throws IllegalVariableEvaluationException {
        username = runContext.render(username);
        password = runContext.render(password);

        if (username != null && password != null) {
            return username + ":" + password;
        }

        if (username != null) {
            return username;

        }

        return "";
    }

    protected URI sftpUri(RunContext runContext, String filepath) throws IllegalVariableEvaluationException, URISyntaxException {
        return sftpUri(runContext, this.host, this.port, this.username , this.password, filepath);
    }

    static URI sftpUri(RunContext runContext, String host, String port, String username, String password, String filepath) throws IllegalVariableEvaluationException, URISyntaxException {
        return new URI(
            "sftp",
            basicAuth(runContext, username, password),
            runContext.render(host),
            port == null ? 22 : Integer.parseInt(runContext.render(port)),
            "/" + StringUtils.stripStart(runContext.render(filepath), "/"),
            null,
            null
        );
    }

    protected FsOptionWithCleanUp fsOptions(RunContext runContext) throws IOException, IllegalVariableEvaluationException {
        return fsOptions(
            runContext,
            this.keyfile,
            this.passphrase,
            this.proxyHost,
            this.proxyPassword,
            this.proxyPort,
            this.proxyUser,
            this.proxyType,
            this.rootDir
        );
    }

    static FsOptionWithCleanUp fsOptions(
        RunContext runContext,
        String keyfile,
        String passphrase,
        String proxyHost,
        String proxyPassword,
        Integer proxyPort,
        String proxyUser,
        String proxyType,
        Boolean rootDir
    ) throws IOException, IllegalVariableEvaluationException {
        SftpFileSystemConfigBuilder instance = SftpFileSystemConfigBuilder.getInstance();

        FileSystemOptions options = new FileSystemOptions();

        instance.setStrictHostKeyChecking(options, "no");
        instance.setUserDirIsRoot(options, rootDir);
        instance.setSessionTimeoutMillis(options, 10000);
        // see https://issues.apache.org/jira/browse/VFS-766
        instance.setDisableDetectExecChannel(options, true);

        if (proxyHost != null) {
            instance.setProxyHost(options, proxyHost);
        }
        if (proxyPassword != null) {
            instance.setProxyPassword(options, proxyPassword);
        }
        if (proxyPort != null) {
            instance.setProxyPort(options, proxyPort);
        }
        if (proxyUser != null) {
            instance.setProxyUser(options, proxyUser);
        }
        if (proxyHost != null && proxyType == null) {
            throw new IllegalArgumentException("Invalid proxy type");
        } else {
            if (proxyType != null) {
                switch (proxyType) {
                    case "SOCKS5":
                        instance.setProxyType(options, SftpFileSystemConfigBuilder.PROXY_SOCKS5);
                        break;
                    case "STREAM":
                        instance.setProxyType(options, SftpFileSystemConfigBuilder.PROXY_STREAM);
                        break;
                    case "HTTP":
                        instance.setProxyType(options, SftpFileSystemConfigBuilder.PROXY_HTTP);
                        break;
                }
            }
        }


        String keyContent = runContext.render(keyfile);
        if (keyContent != null) {
            File sftpKey = File.createTempFile("sftp-key-", "");
            try (FileWriter myWriter = new FileWriter(sftpKey)) {
                myWriter.write(keyContent);
            }

            IdentityInfo identityInfo;
            if (passphrase != null) {
                identityInfo = new IdentityInfo(sftpKey, runContext.render(passphrase).getBytes());
            } else {
                identityInfo = new IdentityInfo(sftpKey);
            }

            instance.setIdentityProvider(options, identityInfo);

            return new FsOptionWithCleanUp(options, sftpKey::delete);
        } else {
            return new FsOptionWithCleanUp(options, () -> {});
        }
    }

    protected static URI output(URI uri) throws URISyntaxException {
        return new URI(
            uri.getScheme(),
            uri.getHost(),
            uri.getPath(),
            uri.getFragment()
        );
    }

    protected static boolean isDirectory(URI uri) {
        return ("/" + FilenameUtils.getPath(uri.getPath())).equals(uri.getPath());
    }


    @AllArgsConstructor
    @Getter
    public static class FsOptionWithCleanUp {
        private FileSystemOptions options;
        private Runnable cleanup;
    }
}
