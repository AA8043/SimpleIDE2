package org.a8043.simpleIDE.project.buildTool;

import cn.hutool.core.util.ZipUtil;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;

@AllArgsConstructor
@Getter
@Setter
@EqualsAndHashCode
public class Dependency {
    private String groupId;
    private String artifactId;
    private String version;
    private String moduleName;
    private File jarFile;
    private File sourceJarFile;

    public ZipFile getSourceZip() {
        if (sourceJarFile.exists()) {
            return ZipUtil.toZipFile(sourceJarFile, StandardCharsets.UTF_8);
        }
        return null;
    }
}
