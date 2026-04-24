package org.a8043.simpleIDE.views;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.json.JSONObject;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import org.a8043.simpleIDE.Main;
import org.a8043.simpleIDE.project.ProjectEditor;
import org.a8043.simpleIDE.util.GitUtil;
import org.a8043.simpleIDE.util.Util;

import java.net.URL;
import java.util.function.Consumer;

public class GitPushModal {
    public static final URL FXML_URL = ResourceUtil.getResource("GitPushModal.fxml", GitPushModal.class);
    private final ProjectEditor editor;
    private final JSONObject remoteBranchJson;
    private final Consumer<String> onOutput;
    private Runnable closeModal;
    @FXML
    private ListView<Push> pushList;
    @FXML
    private CheckBox forceBox;

    public GitPushModal(ProjectEditor editor, Consumer<String> onOutput) {
        this.editor = editor;
        JSONObject remoteBranchJson = editor.getRecord().getByPath("git.remoteBranch", JSONObject.class);
        if (remoteBranchJson == null) {
            JSONObject git = editor.getRecord().getJSONObject("git");
            if (git == null) {
                editor.getRecord().set("git", git = new JSONObject());
            }
            git.set("remoteBranch", remoteBranchJson = new JSONObject());
        }
        this.remoteBranchJson = remoteBranchJson;
        this.onOutput = onOutput;
    }

    @FXML
    private void initialize() {
        pushList.setCellFactory(Util.createListCell(push -> {
            VBox box = new VBox();
            Hyperlink remoteBranchHyperlink = new Hyperlink(push.remoteBranch);
            HBox content = new HBox(new Label(push.branch), new Label(" -> "),
                new Label(push.remote.getName()), new Label(":"), remoteBranchHyperlink);
            box.getChildren().add(content);
            remoteBranchHyperlink.setOnAction(e -> box.getChildren().setAll(new TextField(push.remoteBranch) {{
                addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                    if (event.getCode() == KeyCode.ENTER) {
                        push.remoteBranch = getText();
                        remoteBranchJson.set(push.branch, push.remoteBranch);
                        box.getChildren().setAll(content);
                    }
                });
            }}));
            return box;
        }));
        GitUtil.getRemoteList(editor.getProject().getProjectDir()).forEach(remote ->
            GitUtil.getBranchList(editor.getProject().getProjectDir()).forEach(branch -> {
                String remoteBranch = remoteBranchJson.getStr(branch);
                if (remoteBranch == null) {
                    remoteBranchJson.set(branch, remoteBranch = branch);
                }
                pushList.getItems().add(new Push(branch, remote, remoteBranch));
            }));
    }

    @FXML
    private void push() {
        closeModal.run();
        new Thread(() -> pushList.getItems().forEach(push -> GitUtil.push(editor.getProject().getProjectDir(),
            push.remote.getName(), push.remoteBranch, push.branch, forceBox.isSelected(), onOutput))).start();
    }

    @AllArgsConstructor
    private static class Push {
        private final String branch;
        private final GitUtil.Remote remote;
        private String remoteBranch;
    }

    @SneakyThrows
    public static void show(ProjectEditor editor, Consumer<String> onOutput) {
        FXMLLoader loader = new FXMLLoader(GitPushModal.FXML_URL);
        GitPushModal gitPushModal = new GitPushModal(editor, onOutput);
        loader.setControllerFactory(param -> gitPushModal);
        Main.ModalController<Node> modal = Main.instance.showModal("git.push", loader.load(), 400, 300);
        gitPushModal.closeModal = modal::close;
    }
}
