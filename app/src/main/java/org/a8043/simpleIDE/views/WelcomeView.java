package org.a8043.simpleIDE.views;

import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.json.JSONObject;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.a8043.simpleIDE.Main;
import org.a8043.simpleIDE.project.Project;
import org.a8043.simpleIDE.project.ProjectEditor;
import org.a8043.simpleIDE.resource.ResourceManager;
import org.a8043.simpleIDE.util.SearchUtil;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class WelcomeView {
    public static final URL FXML_URL = ResourceUtil.getResource("WelcomeView.fxml", WelcomeView.class);
    @FXML
    private TextField searchField;
    @FXML
    private VBox projectsBox;
    private final List<ProjectBox> projectBoxList = new ArrayList<>();

    @FXML
    private void initialize() {
        projectsBox.setAlignment(Pos.CENTER);
        projectsBox.getChildren().add(ResourceManager.createImageView("loading", 64, 64));

        new Thread(() -> {
            List<Project> projectList = new ArrayList<>();
            Main.instance.getRecordJson().getJSONArray("projects").forEach(projectJsonObject -> {
                JSONObject projectJson = (JSONObject) projectJsonObject;
                projectList.add(new Project(projectJson.getStr("name"),
                    new File(projectJson.getStr("path")), new File(projectJson.getStr("jdkPath"))));
            });
            Platform.runLater(() -> {
                projectsBox.getChildren().clear();
                projectsBox.setAlignment(Pos.TOP_LEFT);
                projectBoxList.addAll(projectList.stream().map(ProjectBox::new).toList());
                projectsBox.getChildren().addAll(projectBoxList);
            });
        }).start();
    }

    @FXML
    private void search() {
        String keyword = searchField.getText();
        if (keyword.isEmpty()) {
            projectsBox.getChildren().clear();
            projectsBox.getChildren().addAll(projectBoxList);
            return;
        }

        new Thread(() -> {
            List<ProjectBox> result = SearchUtil.search(projectBoxList, box -> box.getProject().getName(), keyword);
            Platform.runLater(() -> {
                projectsBox.getChildren().clear();
                projectsBox.getChildren().addAll(result);
            });
        }).start();
    }

    @SneakyThrows
    @FXML
    private void openSettingsModal() {
        FXMLLoader loader = new FXMLLoader(SettingsModal.FXML_URL);
        loader.setControllerFactory(clazz -> new SettingsModal(Main.instance.getSettings()));
        Main.instance.showModal(loader.load(), 980, 570);
    }

    private static class ProjectBox extends HBox {
        @Getter
        private final Project project;

        private ProjectBox(Project project) {
            this.project = project;

            getChildren().add(ResourceManager.createImageView("project", 64, 64));
            Label nameLabel = new Label(project.getName());
            nameLabel.setFont(new Font(20));
            getChildren().add(new VBox(nameLabel, new Label(project.getProjectDir().getAbsolutePath())));

            setOnMouseClicked(e -> {
                Label tipLabel = new Label();
                tipLabel.setFont(new Font(12));
                VBox modalBox = new VBox(ResourceManager.createImageView("loading", 32, 32), tipLabel);
                modalBox.setAlignment(Pos.CENTER);
                Main.ModalController<VBox> modalController = Main.instance.showModal(modalBox, 120, 70);

                new Thread(() -> {
                    Platform.runLater(() -> tipLabel.setText("正在打开项目..."));
                    ProjectEditor editor = project.open();

                    Platform.runLater(() -> tipLabel.setText("正在索引..."));
                    ProgressBar indexProgressBar = new ProgressBar(-1);
                    Platform.runLater(() -> modalBox.getChildren().add(indexProgressBar));
                    AtomicInteger indexCount = new AtomicInteger();
                    AtomicInteger indexedCount = new AtomicInteger(0);
                    editor.getIndex().indexAll(count -> {
                            Platform.runLater(() -> indexProgressBar.setProgress(0));
                            indexCount.set(count);
                        }, () ->
                            indexProgressBar.setProgress((double) indexedCount.incrementAndGet() / indexCount.get()),
                        () -> Platform.runLater(() -> indexProgressBar.setProgress(-1)),
                        exception -> {
                            String message = ExceptionUtil.getMessage(exception);
                            Platform.runLater(() ->
                                Main.instance.showTipModal("索引失败: " + message, modalController::close));
                            log.error(message, exception);
                            throw new RuntimeException(exception);
                        });

                    Platform.runLater(() -> tipLabel.setText("正在准备编辑器..."));
                    ProjectView projectView = new ProjectView(editor);
                    FXMLLoader fxmlLoader = new FXMLLoader(ProjectView.FXML_URL);
                    fxmlLoader.setControllerFactory(param -> projectView);
                    Parent root;
                    try {
                        root = fxmlLoader.load();
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }

                    Platform.runLater(() -> {
                        modalController.close();
                        Main.instance.display(root);
                    });
                }).start();
            });
        }
    }
}
