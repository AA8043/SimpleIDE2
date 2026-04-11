package org.a8043.simpleIDE.views;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.json.JSONObject;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.util.Callback;
import org.a8043.simpleIDE.Main;
import org.a8043.simpleIDE.project.Jdk;
import org.a8043.simpleIDE.project.Project;
import org.a8043.simpleIDE.project.buildTool.BuildToolType;
import org.a8043.simpleIDE.project.types.JavaProject;
import org.a8043.simpleIDE.util.Util;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NewProjectModal {
    public static final URL FXML_URL = ResourceUtil.getResource("NewProjectModal.fxml", NewProjectModal.class);
    private static final List<ProjectType> TYPE_LIST = new ArrayList<>();

    static {
        register(new JavaProject());
    }

    public interface ProjectType {
        String name();

        String description();

        void create(Project project, BuildToolType buildToolType, Jdk jdk, String groupId, String artifactId);

        Node createConfigurationPane();
    }

    public static void register(ProjectType type) {
        TYPE_LIST.add(type);
    }

    private final Runnable afterCreate;
    @FXML
    private VBox configurationPane;
    @FXML
    private ListView<ProjectType> typeListView;
    @FXML
    private TextField nameField;
    @FXML
    private TextField locationField;
    @FXML
    private ComboBox<BuildToolType> buildToolBox;
    @FXML
    private ComboBox<Jdk> jdkBox;
    @FXML
    private TextField groupIdField;
    @FXML
    private TextField artifactIdField;

    public NewProjectModal(Runnable afterCreate) {
        this.afterCreate = afterCreate;
    }

    @FXML
    private void initialize() {
        typeListView.setCellFactory(Util.createListCell(type ->
            new VBox(new Label(type.name()), new Label(type.description()) {{
                getStyleClass().add("not-important");
            }})));
        typeListView.getItems().addAll(TYPE_LIST);
        Map<ProjectType, Node> configurationPaneMap = new HashMap<>();
        typeListView.getSelectionModel().selectedItemProperty().addListener((obs, old, newValue) -> {
            if (old != null) {
                configurationPane.getChildren().remove(configurationPaneMap.computeIfAbsent(old,
                    ProjectType::createConfigurationPane));
            }
            if (newValue != null) {
                configurationPane.getChildren().add(configurationPaneMap.computeIfAbsent(newValue,
                    ProjectType::createConfigurationPane));
            }
        });

        Callback<ListView<BuildToolType>, ListCell<BuildToolType>> buildToolCell =
            Util.createListCell(type -> new Label(type.name()));
        buildToolBox.setCellFactory(buildToolCell);
        buildToolBox.setButtonCell(buildToolCell.call(null));
        buildToolBox.getItems().addAll(BuildToolType.TYPE_LIST);

        jdkBox.setCellFactory(Util.createListCell(jdk -> new VBox(new Label(jdk.getVersion()),
            new Label(jdk.getPath().getAbsolutePath()) {{
                getStyleClass().add("not-important");
            }})));
        Callback<ListView<Jdk>, ListCell<Jdk>> jdkButtonCell =
            Util.createListCell(jdk -> new Label(jdk.getVersion()));
        jdkBox.setButtonCell(jdkButtonCell.call(null));
        jdkBox.getItems().addAll(Jdk.JDK_LIST);
    }

    @FXML
    private void create() {
        ProjectType type = typeListView.getSelectionModel().getSelectedItem();
        String name = nameField.getText();
        String location = locationField.getText();
        BuildToolType buildTool = buildToolBox.getValue();
        Jdk jdk = jdkBox.getValue();
        String groupId = groupIdField.getText();
        String artifactId = artifactIdField.getText();
        if (type == null || name.isEmpty() || location.isEmpty() || buildTool == null ||
            jdk == null || groupId.isEmpty() || artifactId.isEmpty()) {
            Main.instance.showTipModal("welcome.newProject.configEmptyTip");
            return;
        }

        File dir = new File(new File(location), name);
        if (!dir.mkdirs()) {
            Main.instance.showTipModal("welcome.newProject.mkdirFailTip");
            return;
        }
        Project project = new Project(name, dir);
        Main.instance.getRecordJson().getJSONArray("projects").add(new JSONObject().set("name", name)
            .set("path", project.getProjectDir().getAbsolutePath()));
        type.create(project, buildTool, jdk, groupId, artifactId);
        afterCreate.run();
    }
}
