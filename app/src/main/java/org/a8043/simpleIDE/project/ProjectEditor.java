package org.a8043.simpleIDE.project;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.a8043.simpleIDE.Main;
import org.a8043.simpleIDE.fileEditor.ControllableFile;
import org.a8043.simpleIDE.project.buildTool.BuildTool;
import org.a8043.simpleIDE.project.buildTool.BuildToolType;
import org.a8043.simpleIDE.project.index.Index;
import org.a8043.simpleIDE.project.runnables.RunnableTask;
import org.a8043.simpleIDE.project.runnables.RunnableType;
import org.a8043.simpleIDE.util.GitUtil;
import org.a8043.simpleIDE.util.config.ConfigUtil;

import java.io.Closeable;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Slf4j
@Getter
public class ProjectEditor implements Closeable {
    public static final List<ProjectEditor> OPENED_LIST = new ArrayList<>();
    private final Project project;
    private final Jdk jdk;
    private final Index index;
    private ProjectModel projectModel;
    private final ThreadLocal<JavaParser> javaParserThreadLocal;
    private final File configDir;
    private final File configFile;
    private final File modelFile;
    private final BuildTool buildTool;
    private final ProjectConfig config;
    private final ObservableList<RunnableTask> runnableList = FXCollections.observableArrayList();
    private final List<ControllableFile> openedFileList = new ArrayList<>();

    public ProjectEditor(Project project) {
        this.project = project;
        OPENED_LIST.add(this);

        configDir = new File(project.getProjectDir(), ".simpleIDE");
        if (!configDir.exists() && !configDir.mkdir()) {
            Main.instance.showTipModal("打开项目失败: 创建配置目录失败");
            throw new RuntimeException();
        }

        buildTool = BuildToolType.recognition(project).newBuildTool(this);

        configFile = new File(configDir, "editor.json");
        if (!configFile.exists()) {
            if (BuildToolType.MAVEN.isUseThis(project)) {
                config = ProjectConfig.fromMaven(this);
            } else if (BuildToolType.GRADLE.isUseThis(project)) {
                config = ProjectConfig.fromGradle(this);
            } else {
                config = ProjectConfig.fromDefault(this);
            }
            saveConfig();
        } else {
            config = ConfigUtil.toObject(new JSONObject(FileUtil.readUtf8String(configFile)), ProjectConfig.class);
        }

        config.getRunnableJsonList().forEach(json -> runnableList.add(RunnableType.TYPE_LIST.stream()
            .filter(t -> Objects.equals(t.getName(), json.getStr("type"))).findFirst()
            .orElseThrow(() -> new RuntimeException("不正确的可运行类型: " + json.getStr("type")))
            .createTask(this, json)));

        jdk = Jdk.getJdk(config.getJdkPath());
        index = new Index(this);
        javaParserThreadLocal = ThreadLocal.withInitial(() -> new JavaParser(new ParserConfiguration()
            .setLanguageLevel(jdk.getLanguageLevel()).setCharacterEncoding(StandardCharsets.UTF_8)));

        modelFile = new File(configDir, "model.json");
        if (modelFile.exists()) {
            projectModel = JSONUtil.toBean(FileUtil.readUtf8String(modelFile), ProjectModel.class);
        } else {
            projectModel = buildTool.sync(this);
        }

        if (new File(project.getProjectDir(), ".git").exists()) {
            GitUtil.open(project.getProjectDir());
        }

        new Thread(() -> {
            while (true) {
                ThreadUtil.sleep(Main.instance.getSettings().getAutoSaveInterval());
                saveFiles();
            }
        }).start();
    }

    public JavaParser getJavaParser() {
        return javaParserThreadLocal.get();
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
        javaParserThreadLocal.remove();
        saveFiles();
        saveConfig();
        OPENED_LIST.remove(this);
    }

    public synchronized void saveFiles() {
        openedFileList.forEach(ControllableFile::write);
    }

    private void saveConfig() {
        config.getRunnableJsonList().clear();
        runnableList.forEach(runnable -> config.getRunnableJsonList().add(runnable.getJson()));
        FileUtil.writeUtf8String(new JSONObject(projectModel).toString(), modelFile);
        FileUtil.writeUtf8String(ConfigUtil.toJson(config).toString(), configFile);
    }
}
