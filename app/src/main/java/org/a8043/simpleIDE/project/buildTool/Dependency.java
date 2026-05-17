package org.a8043.simpleIDE.project.buildTool;

import cn.hutool.core.util.ZipUtil;
import lombok.*;
import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.OutputSinkFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
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

    public SourceZipGetter getSourceZip() {
        return new SourceZipGetter();
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
            if (zipFile == null) {
                synchronized (lock) {
                    lock.wait();
                }
            }
            return zipFile;
        }
    }
}
