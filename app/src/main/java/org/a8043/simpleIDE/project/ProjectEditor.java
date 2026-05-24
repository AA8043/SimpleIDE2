package org.a8043.simpleIDE.project;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.a8043.simpleIDE.Main;
import org.a8043.simpleIDE.fileEditor.ControllableFile;
import org.a8043.simpleIDE.project.buildTool.BuildTool;
import org.a8043.simpleIDE.project.buildTool.BuildToolType;
import org.a8043.simpleIDE.project.index.Index;
import org.a8043.simpleIDE.project.index.IndexPoint;
import org.a8043.simpleIDE.project.index.Module;
import org.a8043.simpleIDE.project.runnables.RunnableTask;
import org.a8043.simpleIDE.project.runnables.RunnableType;
import org.a8043.simpleIDE.util.GitUtil;
import org.a8043.simpleIDE.util.JavaUtil;

import java.io.Closeable;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;

@Slf4j
@Getter
public class ProjectEditor implements Closeable {
    public static final List<ProjectEditor> OPENED_LIST = new ArrayList<>();
    private final Project project;
    private final Jdk jdk;
    private final Index index;
    private final File indexCacheFile;
    private ProjectModel projectModel;
    private final ThreadLocal<JavaParser> javaParserThreadLocal;
    private final File configDir;
    private final File configFile;
    private final File recordFile;
    private final File modelFile;
    private final BuildTool buildTool;
    private final ProjectConfig config;
    private final JSONObject record;
    private final ObservableList<RunnableTask> runnableList = FXCollections.observableArrayList();
    private final List<ControllableFile> openedFileList = new ArrayList<>();
    private final Thread autoSaveThread;
    private final ObservableList<Task<?>> taskList = FXCollections.observableArrayList();
    private final ExecutorService taskThreadPool = new ThreadPoolExecutor(5, 5,
        60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(10), Executors.defaultThreadFactory(),
        new ThreadPoolExecutor.AbortPolicy());

    public ProjectEditor(Project project) {
        this.project = project;
        OPENED_LIST.add(this);

        configDir = new File(project.getProjectDir(), ".simpleIDE");
        if (!configDir.exists() && !configDir.mkdir()) {
            Main.instance.showTipModal("打开项目失败: 创建配置目录失败");
            throw new RuntimeException();
        }

        BuildToolType buildToolType = BuildToolType.recognition(project);
        buildTool = buildToolType.newBuildTool(this);

        configFile = new File(configDir, "editor.json");
        if (!configFile.exists()) {
            if (BuildToolType.MAVEN.isUseThis(project)) {
                config = ProjectConfig.fromMaven(this);
            } else if (BuildToolType.GRADLE.isUseThis(project)) {
                config = ProjectConfig.fromGradle(this);
            } else {
                config = ProjectConfig.fromDefault(this);
            }
        } else {
            config = new JSONObject(FileUtil.readUtf8String(configFile)).toBean(ProjectConfig.class);
        }

        recordFile = new File(configDir, "record.json");
        if (recordFile.exists()) {
            record = new JSONObject(FileUtil.readUtf8String(recordFile));
        } else {
            record = new JSONObject();
        }

        config.getRunnableJsonList().forEach(json -> runnableList.add(RunnableType.TYPE_LIST.stream()
            .filter(t -> Objects.equals(t.getName(), json.getStr("type"))).findFirst()
            .orElseThrow(() -> new RuntimeException("不正确的可运行类型: " + json.getStr("type")))
            .createTask(this, json)));

        jdk = Jdk.getJdk(config.getJdkPath());
        javaParserThreadLocal = ThreadLocal.withInitial(() -> new JavaParser(new ParserConfiguration()
            .setLanguageLevel(jdk.getLanguageLevel()).setCharacterEncoding(StandardCharsets.UTF_8)));

        indexCacheFile = new File(configDir, "indexCache.json");
        index = new Index(this);

        modelFile = new File(configDir, "model.json");
        if (modelFile.exists()) {
            projectModel = JSONUtil.toBean(FileUtil.readUtf8String(modelFile), buildToolType.getModelType());
        } else {
            projectModel = buildTool.sync(this);
        }

        if (new File(project.getProjectDir(), ".git").exists()) {
            GitUtil.open(project.getProjectDir());
        }

        taskList.addListener((ListChangeListener<Task<?>>) c -> {
            while (c.next()) {
                if (c.wasAdded()) {
                    c.getAddedSubList().forEach(taskThreadPool::submit);
                }
            }
        });

        autoSaveThread = new Thread(() -> {
            while (ThreadUtil.sleep(Main.instance.getSettings().getAutoSaveInterval())) {
                saveFiles();
            }
        });
        autoSaveThread.start();
    }

    public JavaParser getJavaParser() {
        return javaParserThreadLocal.get();
    }

    public ControllableFile openFile(File file, String content, boolean readOnly) {
        ControllableFile controllableFile = new ControllableFile(file, content, readOnly);
        controllableFile.read();
        openedFileList.add(controllableFile);
        return controllableFile;
    }

    public boolean isReadOnlyFile(File file) {
        if (file == null) {
            return false;
        }
        try {
            File sourceCacheDir = new File(configDir, "source-cache").getCanonicalFile();
            File targetFile = file.getCanonicalFile();
            return targetFile.toPath().startsWith(sourceCacheDir.toPath());
        } catch (Exception e) {
            return false;
        }
    }

    public IndexPoint resolveIndexPointByFile(File file) {
        if (file == null) {
            return null;
        }

        try {
            File canonicalFile = file.getCanonicalFile();
            File sourceCacheDir = new File(configDir, "source-cache").getCanonicalFile();
            if (canonicalFile.toPath().startsWith(sourceCacheDir.toPath())) {
                String relativePath = sourceCacheDir.toPath().relativize(canonicalFile.toPath()).toString()
                    .replace("\\", "/");
                String[] path = relativePath.split("/");
                if (path.length < 2) {
                    return null;
                }
                String moduleName = path[0];
                Module module = index.getModuleByCacheName(moduleName);
                if (module == null) {
                    return null;
                }
                String[] sourcePath = Arrays.copyOfRange(path, 1, path.length);
                if (sourcePath.length > 1 && module.getProjectModule() != null &&
                    module.getProjectModule().getName().equals(sourcePath[0])) {
                    sourcePath = Arrays.copyOfRange(sourcePath, 1, sourcePath.length);
                }
                if (sourcePath.length == 0 || !sourcePath[sourcePath.length - 1].endsWith(".java")) {
                    return null;
                }
                sourcePath[sourcePath.length - 1] = sourcePath[sourcePath.length - 1]
                    .substring(0, sourcePath[sourcePath.length - 1].length() - ".java".length());
                return module.getPoint(sourcePath);
            }

            for (File srcDir : projectModel.getSrcDirList()) {
                File canonicalSrcDir = srcDir.getCanonicalFile();
                if (!canonicalFile.toPath().startsWith(canonicalSrcDir.toPath())) {
                    continue;
                }
                String relativePath = canonicalSrcDir.toPath().relativize(canonicalFile.toPath()).toString()
                    .replace("\\", "/");
                if (!relativePath.endsWith(".java")) {
                    continue;
                }
                String[] path = relativePath.substring(0, relativePath.length() - ".java".length()).split("/");
                Module module = JavaUtil.resolveModuleByPath(index, path);
                if (module == null) {
                    continue;
                }
                return module.getPoint(path);
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    public void closeFile(ControllableFile controllableFile) {
        controllableFile.write();
        openedFileList.remove(controllableFile);
    }

    public void runTask(Task<?> task) {
        taskList.add(task);
        task.runningProperty().addListener((obs, oldRunning, newRunning) -> {
            if (!newRunning) {
                taskList.remove(task);
            }
        });
    }

    @SneakyThrows
    @Override
    public void close() {
        index.close();
        javaParserThreadLocal.remove();
        taskThreadPool.shutdownNow();
        autoSaveThread.interrupt();
        saveFiles();
        saveConfig();
        OPENED_LIST.remove(this);
    }

    public synchronized void saveFiles() {
        openedFileList.stream().filter(file -> !file.isReadOnly()).forEach(ControllableFile::write);
    }

    private void saveConfig() {
        config.getRunnableJsonList().clear();
        runnableList.forEach(runnable -> config.getRunnableJsonList().add(runnable.getJson()));
        FileUtil.writeUtf8String(new JSONObject(projectModel).toString(), modelFile);
        FileUtil.writeUtf8String(index.toJSONString(), indexCacheFile);
        FileUtil.writeUtf8String(new JSONObject(config).toString(), configFile);
        FileUtil.writeUtf8String(record.toString(), recordFile);
    }
}
