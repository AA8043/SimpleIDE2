package org.a8043.simpleIDE.views;

import cn.hutool.core.io.resource.ResourceUtil;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Callback;
import org.a8043.simpleIDE.Main;
import org.a8043.simpleIDE.project.ProjectEditor;
import org.a8043.simpleIDE.project.runnables.RunnableTask;
import org.a8043.simpleIDE.project.runnables.Runner;
import org.a8043.simpleIDE.resource.ResourceManager;
import org.a8043.simpleIDE.util.Util;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public class ProjectView {
    public static final URL FXML_URL = ResourceUtil.getResource("ProjectView.fxml", ProjectView.class);
    private final ProjectEditor editor;
    private final List<FileTab.FileTabTab> tabHistory = new ArrayList<>();
    @FXML
    private AnchorPane pane;
    @FXML
    private TabPane editorTabPane;
    @FXML
    private TreeView<File> fileTreeView;
    @FXML
    private Tab fileManagerTab;
    @FXML
    private ComboBox<Object> runnableBox;
    @FXML
    private Button runnableRunButton;

    public ProjectView(ProjectEditor editor) {
        this.editor = editor;
    }

    @FXML
    private void initialize() {
        editorTabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab instanceof FileTab.FileTabTab fileTab) {
                tabHistory.remove(fileTab);
                tabHistory.add(fileTab);
            }
        });

        fileManagerTab.setGraphic(ResourceManager.createImageView("file", 16, 16));
        runnableRunButton.setGraphic(ResourceManager.createImageView("run", 16, 16));

        editorTabPane.getTabs().addListener((ListChangeListener<? super Tab>) observable -> {
            if (observable.next() && observable.wasAdded()) {
                editorTabPane.getSelectionModel().select(observable.getAddedSubList().getFirst());
            }
        });

        AtomicReference<Main.ModalController<Switcher>> switcherModal = new AtomicReference<>();
        editorTabPane.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isControlDown() && event.getCode() == KeyCode.TAB) {
                event.consume();
                if (switcherModal.get() == null) {
                    if (tabHistory.size() > 1) {
                        Switcher switcher = new Switcher();
                        Main.ModalController<Switcher> modalController =
                            Main.instance.showModal(switcher, 400, 300);
                        switcherModal.set(modalController);
                        modalController.setOnClose(() -> switcherModal.set(null));
                    }
                } else {
                    switcherModal.get().getNode().next();
                }
            }
        });
        editorTabPane.addEventFilter(KeyEvent.KEY_RELEASED, event -> {
            if (event.getCode() == KeyCode.CONTROL) {
                event.consume();
                if (switcherModal.get() != null) {
                    switcherModal.get().getNode().switchTab();
                    switcherModal.get().close();
                }
            }
        });

        fileTreeView.setCellFactory(Util.createTreeCell(file -> {
            Label label = new Label(file.getName());
            ImageView imageView = new ImageView(Util.getFileImage(file));
            imageView.setFitWidth(16);
            imageView.setFitHeight(16);
            label.setGraphic(imageView);
            return label;
        }));
        File projectDir = editor.getProject().getProjectDir();
        TreeItem<File> root = new TreeItem<>(projectDir);
        addFileToTreeItem(projectDir, root);
        fileTreeView.setRoot(root);

        Callback<ListView<Object>, ListCell<Object>> runnableBoxCellFactory = Util.createListCell(item -> {
            if (item instanceof RunnableTask runnable) {
                return new Label(runnable.getName());
            } else {
                return new Label(item.toString());
            }
        });
        runnableBox.setCellFactory(runnableBoxCellFactory);
        runnableBox.setButtonCell(runnableBoxCellFactory.call(null));
        runnableBox.getItems().addAll(editor.getRunnableList());
    }

    private static void addFileToTreeItem(File lastFile, TreeItem<File> treeItem) {
        for (File file : Objects.requireNonNull(lastFile.listFiles())) {
            if (file.isFile()) {
                treeItem.getChildren().add(new TreeItem<>(file));
            } else {
                TreeItem<File> treeItem1 = new TreeItem<>(file);
                treeItem1.setExpanded(false);
                addFileToTreeItem(file, treeItem1);
                treeItem.getChildren().add(treeItem1);
            }
        }
    }

    @FXML
    private void onFileClicked(MouseEvent event) {
        if (event.getClickCount() == 2) {
            TreeItem<File> selectedItem = fileTreeView.getSelectionModel().getSelectedItem();
            if (selectedItem != null) {
                File file = selectedItem.getValue();
                if (file.isFile() &&
                    editorTabPane.getTabs().stream().noneMatch(tab ->
                        tab instanceof FileTab.FileTabTab fileTab && file.equals(fileTab.getFile()))) {
                    editorTabPane.getTabs().add(FileTab.createTab(editor, file));
                }
            }
        }
    }

    @FXML
    private void runRunnable(MouseEvent event) {
        RunnableTask runnable = (RunnableTask) runnableBox.getSelectionModel().getSelectedItem();
        if (runnable == null) {
            Main.instance.showTipModal("没有选择可运行");
            return;
        }

        editor.saveFiles();

        Tab tab = new Tab("运行: " + runnable.getName());
        Runner runner = runnable.createRunner(tab);

        Runnable run = () -> {
            editorTabPane.getTabs().add(tab);
            new Thread(runner::run).start();
        };
        if (event.isShiftDown()) {
            Main.ModalController<VBox> modal = Main.instance.showModal(new VBox() {{
                runner.getOptionMap().forEach((k, v) -> getChildren().add(new CheckBox("runnable." + k) {{
                    setSelected(v);
                    selectedProperty().addListener((obs, old, newValue) -> runner.getOptionMap().put(k, newValue));
                }}));
            }}, 400, 300);
            modal.getNode().getChildren().add(new Button("run") {{
                setOnAction(e -> modal.close());
            }});
            modal.setOnClose(run);
        } else {
            run.run();
        }
    }

    private class Switcher extends VBox {
        private final Label pathLabel = new Label();
        private final ListView<FileTab.FileTabTab> listView = new ListView<>(FXCollections.observableList(tabHistory));

        public Switcher() {
            listView.setCellFactory(Util.createListCell(fileTab ->
                new HBox(Util.getFileImageView(fileTab.getFile(), 16, 16),
                    new Label(fileTab.getFile().getName()))));
            listView.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) ->
                pathLabel.setText(newTab.getFile().getAbsolutePath()));
            listView.getSelectionModel().select(1);

            getChildren().addAll(listView, pathLabel);
        }

        public void next() {
            MultipleSelectionModel<FileTab.FileTabTab> selectionModel = listView.getSelectionModel();
            selectionModel.selectNext();
            if (selectionModel.getSelectedIndex() >= listView.getItems().size()) {
                selectionModel.selectFirst();
            }
        }

        public void switchTab() {
            FileTab.FileTabTab selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                editorTabPane.getSelectionModel().select(selected);
            }
        }
    }
}
