package org.a8043.simpleIDE.views;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.json.JSONObject;
import javafx.fxml.FXML;
import javafx.geometry.Side;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.VBox;
import org.a8043.simpleIDE.project.ProjectEditor;
import org.a8043.simpleIDE.project.runnables.RunnableTask;
import org.a8043.simpleIDE.project.runnables.RunnableType;
import org.a8043.simpleIDE.util.Util;

import java.net.URL;

public class RunnableManager {
    public static final URL FXML_URL = ResourceUtil.getResource("RunnableManager.fxml", RunnableManager.class);
    private final ProjectEditor editor;
    @FXML
    private Button addButton;
    @FXML
    private Button removeButton;
    @FXML
    private ListView<RunnableTask> listView;
    @FXML
    private VBox contentBox;

    public RunnableManager(ProjectEditor editor) {
        this.editor = editor;
    }

    @FXML
    private void initialize() {
        listView.setCellFactory(Util.createListCell(RunnableTask::createListItem));
        listView.getSelectionModel().selectedItemProperty().addListener((obs, oldTask, newTask) -> {
            contentBox.getChildren().clear();
            if (newTask != null) {
                contentBox.getChildren().add(newTask.getManager());
                removeButton.setDisable(false);
            } else {
                removeButton.setDisable(true);
            }
        });
        listView.setItems(editor.getRunnableList());
    }

    @FXML
    private void add() {
        ContextMenu menu = new ContextMenu();
        RunnableType.TYPE_LIST.forEach(type -> menu.getItems().add(new MenuItem(type.getDisplayName()) {{
            setOnAction(e -> {
                RunnableTask task = type.createTask(editor, new JSONObject()
                    .set("type", type.getName()).set("name", type.getDisplayName()));
                editor.getRunnableList().add(task);
                listView.getSelectionModel().select(task);
            });
        }}));
        menu.show(addButton, Side.BOTTOM, 0, 0);
    }

    @FXML
    private void remove() {
        RunnableTask selected = listView.getSelectionModel().getSelectedItem();
        if (selected != null) {
            editor.getRunnableList().remove(selected);
        }
    }
}
