package org.a8043.simpleIDE.views;

import cn.hutool.core.io.resource.ResourceUtil;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import org.a8043.simpleIDE.Main;
import org.a8043.simpleIDE.project.ProjectEditor;
import org.a8043.simpleIDE.resource.ResourceManager;
import org.a8043.simpleIDE.util.FileUtil;
import org.a8043.simpleIDE.util.GitUtil;
import org.a8043.simpleIDE.util.Util;

import java.io.File;
import java.net.URL;

public class GitCommitModal {
    public static final URL FXML_URL = ResourceUtil.getResource("GitCommitModal.fxml", GitCommitModal.class);
    private final ProjectEditor editor;
    private Main.ModalController<Node> modal;
    @FXML
    private ListView<Change> changeList;
    @FXML
    private Button historyButton;
    @FXML
    private TextArea messageArea;
    @FXML
    private CheckBox amendBox;

    @SneakyThrows
    public static void show(ProjectEditor editor) {
        FXMLLoader loader = new FXMLLoader(FXML_URL);
        GitCommitModal gitCommitModal = new GitCommitModal(editor);
        loader.setControllerFactory(clazz -> gitCommitModal);
        gitCommitModal.modal = Main.instance.showModal("git.commit", loader.load(), 600, 400);
    }

    public GitCommitModal(ProjectEditor editor) {
        this.editor = editor;
    }

    @FXML
    private void initialize() {
        historyButton.setGraphic(ResourceManager.createImageView("clock", 16, 16));

        changeList.setCellFactory(Util.createListCell(change -> new HBox(new CheckBox() {{
            selectedProperty().bindBidirectional(change.staged);
        }}, FileUtil.getDisplayItem(change.file))));
        changeList.getItems().addAll(GitUtil.getChangedFiles(editor.getProject().getProjectDir()).stream()
            .map(Change::new).toList());
    }

    private TextArea outputArea;

    private void showGitOutput() {
        outputArea = new TextArea();
        outputArea.setEditable(false);
        modal.close();
        Main.instance.showModal("git.commit", outputArea, 600, 400);
    }

    @FXML
    private void commit() {
        showGitOutput();
        GitUtil.commit(editor.getProject().getProjectDir(), changeList.getItems().stream()
                .filter(change -> change.staged.get()).map(change -> change.file).toList(),
            messageArea.getText(), amendBox.isSelected(), str -> outputArea.appendText(str + "\n"));
    }

    @FXML
    private void commitAndPush() {
        showGitOutput();
        // TODO: push
    }

    @FXML
    private void selectAll() {
        if (changeList.getItems().stream().allMatch(change -> change.staged.get())) {
            changeList.getItems().forEach(change -> change.staged.set(false));
        } else {
            changeList.getItems().forEach(change -> change.staged.set(true));
        }
    }

    @AllArgsConstructor
    private static class Change {
        private final File file;
        private final BooleanProperty staged = new SimpleBooleanProperty(false);
    }
}
