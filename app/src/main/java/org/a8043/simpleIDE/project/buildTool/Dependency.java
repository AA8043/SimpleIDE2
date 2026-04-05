package org.a8043.simpleIDE.project.buildTool;

import cn.hutool.core.util.ZipUtil;
import lombok.SneakyThrows;
import lombok.Value;
import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.OutputSinkFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipFile;

@Value
public class Dependency {
    private static final File MAVEN_LOCAL_REPOSITORY = new File(System.getProperty("user.home"), ".m2/repository");
    String groupId;
    String artifactId;
    String version;
    String moduleName;
    File jarFile;
    File sourceJarFile;

    public SourceZipGetter getSourceZip() {
        return new SourceZipGetter();
    }

    public static Dependency fromMaven(org.apache.maven.model.Dependency dependency) {
        String groupId = dependency.getGroupId();
        String artifactId = dependency.getArtifactId();
        String version = dependency.getVersion();

        File dir = new File(MAVEN_LOCAL_REPOSITORY,
            groupId.replace(".", "/") + "/" +
            artifactId + "/" + version);
        String baseFileName = artifactId + "-" + version;
        String classifier = dependency.getClassifier();
        if (classifier != null && !classifier.isEmpty()) {
            baseFileName += "-" + classifier;
        }

        String type = dependency.getType() != null ? dependency.getType() : "jar";
        return new Dependency(groupId, artifactId, groupId + ":" + artifactId, version,
            new File(dir, baseFileName + "." + type), new File(dir, baseFileName + "-sources.jar"));
    }

    public class SourceZipGetter {
        private final Object lock = new Object();
        private ZipFile zipFile;

        public SourceZipGetter() {
            new Thread(() -> {
                if (sourceJarFile.exists()) {
                    zipFile = ZipUtil.toZipFile(sourceJarFile, StandardCharsets.UTF_8);
                } else {
                    // TODO: 反编译
                    OutputSinkFactory mySink = new OutputSinkFactory() {
                        @Override
                        public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> available) {
                            return Arrays.asList(SinkClass.STRING, SinkClass.DECOMPILED);
                        }

                        @Override
                        public <T> Sink<T> getSink(SinkType sinkType, SinkClass sinkClass) {
                            return value -> {
                                if (sinkType == SinkType.JAVA && value instanceof String) {
                                } else if (sinkType == SinkType.PROGRESS && value instanceof String) {
                                } else if (value instanceof Exception) {
                                } else if (value instanceof String) {
                                }
                            };
                        }
                    };

                    CfrDriver driver = new CfrDriver.Builder().withOutputSink(mySink).build();
                    driver.analyse(Collections.singletonList(jarFile.getAbsolutePath()));
                }

                synchronized (lock) {
                    lock.notifyAll();
                }
            }).start();
        }

        @SneakyThrows
        public ZipFile waitFor() {
            synchronized (lock) {
                lock.wait();
            }
            return zipFile;
        }
    }
}
