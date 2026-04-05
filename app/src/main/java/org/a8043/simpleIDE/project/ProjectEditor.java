package org.a8043.simpleIDE.project;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ReflectUtil;
import cn.hutool.json.JSONObject;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.a8043.simpleIDE.Main;
import org.a8043.simpleIDE.fileEditor.ControllableFile;
import org.a8043.simpleIDE.project.buildTool.BuildTool;
import org.a8043.simpleIDE.project.buildTool.BuildToolType;
import org.a8043.simpleIDE.project.index.Index;
import org.a8043.simpleIDE.project.runnables.RunnableTask;
import org.a8043.simpleIDE.util.config.ConfigUtil;
import org.apache.commons.io.monitor.FileAlterationListener;
import org.apache.commons.io.monitor.FileAlterationListenerAdaptor;
import org.apache.commons.io.monitor.FileAlterationMonitor;
import org.apache.commons.io.monitor.FileAlterationObserver;

import java.io.Closeable;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Getter
public class ProjectEditor implements Closeable {
    private final Project project;
    private final Jdk jdk;
    private final Index index;
    private final ThreadLocal<JavaParser> javaParserThreadLocal;
    private final File configDir;
    private final File configFile;
    private final BuildTool buildTool;
    private final ProjectConfig config;
    private final List<RunnableTask> runnableList = new ArrayList<>();
    private final List<FileAlterationListener> fileListenerList = new ArrayList<>();
    private final FileAlterationMonitor fileMonitor;
    private final List<ControllableFile> openedFileList = new ArrayList<>();

    public ProjectEditor(Project project) {
        this.project = project;
        jdk = Jdk.getJdk(project.getJdkPath());
        index = new Index(this);
        javaParserThreadLocal = ThreadLocal.withInitial(() -> new JavaParser(new ParserConfiguration()
            .setLanguageLevel(jdk.getLanguageLevel()).setCharacterEncoding(StandardCharsets.UTF_8)));

        configDir = new File(project.getProjectDir(), ".simpleIDE");
        if (!configDir.exists() && !configDir.mkdir()) {
            Main.instance.showTipModal("打开项目失败: 创建配置目录失败");
            throw new RuntimeException();
        }

        buildTool = BuildToolType.recognition(project).newBuildTool(this);

        configFile = new File(configDir, "editor.json");
        if (!configFile.exists()) {
            if (new File(project.getProjectDir(), "pom.xml").exists()) {
                config = ProjectConfig.fromMaven(this);
            } else if (new File(project.getProjectDir(), "build.gradle").exists() ||
                       new File(project.getProjectDir(), "build.gradle.kts").exists()) {
                config = ProjectConfig.fromGradle(this);
            } else {
                config = ProjectConfig.fromDefault(this);
            }
            saveConfig();
        } else {
            config = ConfigUtil.toObject(new JSONObject(FileUtil.readUtf8String(configFile)), ProjectConfig.class);
        }

        config.getRunnableJsonList().forEach(json -> {
            try {
                Class<?> clazz = Class.forName(json.getStr("type"));
                if (RunnableTask.class.isAssignableFrom(clazz)) {
                    Object object = ReflectUtil.newInstance(clazz, this, json);
                    runnableList.add((RunnableTask) object);
                } else {
                    throw new Exception("不正确的可运行类型: " + clazz.getName());
                }
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        });

        new Thread(() -> {
            while (true) {
                ThreadUtil.sleep(Main.instance.getSettings().getAutoSaveInterval());
                saveFiles();
            }
        }).start();

        FileAlterationObserver fileObserver = new FileAlterationObserver(project.getProjectDir());
        fileObserver.addListener(new FileAlterationListenerAdaptor() {
            @Override
            public void onDirectoryCreate(File directory) {
                fileListenerList.forEach(listener -> listener.onDirectoryCreate(directory));
            }

            @Override
            public void onDirectoryChange(File directory) {
                fileListenerList.forEach(listener -> listener.onDirectoryChange(directory));
            }

            @Override
            public void onDirectoryDelete(File directory) {
                fileListenerList.forEach(listener -> listener.onDirectoryDelete(directory));
            }

            @Override
            public void onFileCreate(File file) {
                fileListenerList.forEach(listener -> listener.onFileCreate(file));
            }

            @Override
            public void onFileChange(File file) {
                fileListenerList.forEach(listener -> listener.onFileChange(file));
            }

            @Override
            public void onFileDelete(File file) {
                fileListenerList.forEach(listener -> listener.onFileDelete(file));
            }
        });
        fileMonitor = new FileAlterationMonitor(1000);
        fileMonitor.addObserver(fileObserver);
        try {
            fileMonitor.start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public JavaParser getJavaParser() {
        return javaParserThreadLocal.get();
    }

    public void addFileListener(FileAlterationListener listener) {
        fileListenerList.add(listener);
    }

    public ControllableFile openFile(File file, String content) {
        ControllableFile controllableFile = new ControllableFile(file, content);
        controllableFile.read();
        openedFileList.add(controllableFile);
        return controllableFile;
    }

    public void closeFile(ControllableFile controllableFile) {
        controllableFile.write();
        openedFileList.remove(controllableFile);
    }

    @SneakyThrows
    @Override
    public void close() {
        index.close();
        fileMonitor.stop();
        javaParserThreadLocal.remove();
        saveFiles();
        saveConfig();
    }

    public synchronized void saveFiles() {
        openedFileList.forEach(ControllableFile::write);
    }

    private void saveConfig() {
        FileUtil.writeUtf8String(ConfigUtil.toJson(config).toString(), configFile);
    }
}
