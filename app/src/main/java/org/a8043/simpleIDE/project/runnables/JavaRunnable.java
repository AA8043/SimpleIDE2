package org.a8043.simpleIDE.project.runnables;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.RuntimeUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import lombok.Getter;
import lombok.SneakyThrows;
import org.a8043.simpleIDE.project.ProjectEditor;
import org.a8043.simpleIDE.project.buildTool.Dependency;
import org.a8043.simpleIDE.resource.ResourceManager;
import org.a8043.simpleIDE.views.JavaRunTab;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class JavaRunnable extends RunnableTask {
    private String runClass;

    public JavaRunnable(ProjectEditor editor, JSONObject json) {
        super(editor, json);
        runClass = json.getStr("runClass");
    }

    @Override
    public Runner createRunner() {
        return new JavaRunner();
    }

    public class JavaRunner extends Runner {
        private JavaRunTab tab;
        @Getter
        private int debugPort = -1;
        private Process process;

        public JavaRunner() {
            getOptionMap().put("debug", false);
        }

        public String getRunClass() {
            return runClass;
        }

        @SneakyThrows
        @Override
        public Node createContent() {
            FXMLLoader fxmlLoader = new FXMLLoader(JavaRunTab.FXML_URL);
            fxmlLoader.setControllerFactory(clazz -> tab = new JavaRunTab(this));
            return fxmlLoader.load();
        }

        @SneakyThrows
        @Override
        public void run() {
            tab.waitForLoad();

            tab.writelnToTerminal("==编译==\n\n");
            int exitCode = getEditor().getBuildTool().compile(string ->
                Platform.runLater(() -> tab.writeToTerminal(string + "\n"))).get();
            if (exitCode != 0) {
                tab.writelnToTerminal("\n\n==编译失败==\n");
                return;
            }
            tab.clearTerminal();

            tab.writelnToTerminal("==准备运行==\n\n");
            List<String> classpathList = new ArrayList<>();
            classpathList.add(new File(getEditor().getProject().getProjectDir(),
                "target/classes").getAbsolutePath());
            getEditor().getProjectModel().getDependencyList().stream().map(Dependency::getJarFile)
                .toList().forEach(jar -> classpathList.add(jar.getAbsolutePath()));
            tab.writelnToTerminal("classpathList: " + classpathList);
            if (getOptionMap().get("debug")) {
                debugPort = RandomUtil.randomInt(10000, 60000);
            }
            tab.clearTerminal();

            tab.writelnToTerminal("==运行==\n\n");
            List<String> argList = new ArrayList<>();
            argList.add(getEditor().getJdk().getJavaFile().getAbsolutePath());
            if (debugPort != -1) {
                argList.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=" + debugPort);
            }
            argList.add("-classpath");
            argList.add(StrUtil.join(";", classpathList));
            argList.add(runClass);
            process = RuntimeUtil.exec(argList.toArray(new String[0]));
            outToTerminal(process.getInputStream());
            outToTerminal(process.getErrorStream());
            if (debugPort != -1) {
                tab.runDebugger();
            }
            int runExitCode = process.waitFor();
            tab.writelnToTerminal("\n\n==运行结束, 退出码: " + runExitCode + " ==\n");
        }

        private void outToTerminal(InputStream inputStream) {
            new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        String finalLine = line;
                        Platform.runLater(() -> tab.writeToTerminal(finalLine + "\n"));
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }).start();
        }

        @Override
        public void close() {
            process.destroy();
        }
    }

    @Override
    public Node createManager() {
        return new GridPane(2, 2) {{
            addRow(0, new Label("name"), new TextField(getName()) {{
                textProperty().addListener((obs, old, newValue) -> getJson().set("name", newValue));
            }});
            addRow(1, new Label("class"), new TextField(runClass) {{
                textProperty().addListener((obs, old, newValue) -> getJson().set("runClass", runClass = newValue));
            }});
        }};
    }

    @Override
    public Node createListItem() {
        return new HBox(ResourceManager.createImageView("class", 16, 16), new Label(getName()));
    }

    public static class Type extends RunnableType {
        @Override
        public String getName() {
            return "java";
        }

        @Override
        public String getDisplayName() {
            return ResourceManager.getText("runnable.java");
        }

        @Override
        public RunnableTask createTask(ProjectEditor editor, JSONObject json) {
            return new JavaRunnable(editor, json);
        }
    }
}
