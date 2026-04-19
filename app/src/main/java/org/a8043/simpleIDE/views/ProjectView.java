package org.a8043.simpleIDE.views;

import cn.hutool.core.io.resource.ResourceUtil;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.*;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Callback;
import lombok.SneakyThrows;
import org.a8043.simpleIDE.Main;
import org.a8043.simpleIDE.project.ProjectEditor;
import org.a8043.simpleIDE.project.runnables.RunnableTask;
import org.a8043.simpleIDE.project.runnables.Runner;
import org.a8043.simpleIDE.resource.ResourceManager;
import org.a8043.simpleIDE.util.FileUtil;
import org.a8043.simpleIDE.util.GitUtil;
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
    private final List<Tab> tabHistory = new ArrayList<>();
    @FXML
    private AnchorPane pane;
    @FXML
    private TabPane editorTabPane;
    @FXML
    private TreeView<File> fileTreeView;
    @FXML
    private Tab fileManagerTab;
    @FXML
    private ComboBox<RunnableTask> runnableBox;
    @FXML
    private Button runnableManageButton;
    @FXML
    private Button runnableRunButton;

    public ProjectView(ProjectEditor editor) {
        this.editor = editor;
    }

    @FXML
    private void initialize() {
        Main.register(new Main.KeyBinding("runnable.run", "run",
            () -> runRunnable(null), new KeyCodeCombination(KeyCode.F5)));
        Main.register(new Main.KeyBinding("git.commit", "git.commit",
            () -> {
                if (new File(editor.getProject().getProjectDir(), ".git").exists()) {
                    GitCommitModal.show(editor);
                } else {
                    Main.instance.showConfirmModal("git.noGit", () ->
                        GitUtil.init(editor.getProject().getProjectDir()));
                }
            }, new KeyCodeCombination(KeyCode.C,
            KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN, KeyCombination.ALT_DOWN)));

        editorTabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            tabHistory.remove(newTab);
            tabHistory.add(newTab);
        });

        fileManagerTab.setGraphic(ResourceManager.createImageView("file", 16, 16));
        runnableRunButton.setGraphic(ResourceManager.createImageView("run", 16, 16));
        runnableManageButton.setGraphic(ResourceManager.createImageView("class", 16, 16));

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

        fileTreeView.setCellFactory(Util.createTreeCell(FileUtil::getDisplayItem));
        File projectDir = editor.getProject().getProjectDir();
        TreeItem<File> root = new TreeItem<>(projectDir);
        addFileToTreeItem(projectDir, root);
        fileTreeView.setRoot(root);

        Callback<ListView<RunnableTask>, ListCell<RunnableTask>> runnableBoxCellFactory =
            Util.createListCell(RunnableTask::createListItem);
        runnableBox.setCellFactory(runnableBoxCellFactory);
        runnableBox.setButtonCell(runnableBoxCellFactory.call(null));
        runnableBox.setItems(editor.getRunnableList());
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

    @SneakyThrows
    @FXML
    private void openRunnableManager() {
        FXMLLoader loader = new FXMLLoader(RunnableManager.FXML_URL);
        loader.setControllerFactory(clazz -> new RunnableManager(editor));
        Main.instance.showModal("runnable.manager", loader.load(), 600, 400);
    }

    @FXML
    private void runRunnable(MouseEvent event) {
        RunnableTask runnable = runnableBox.getSelectionModel().getSelectedItem();
        if (runnable == null) {
            Main.instance.showTipModal("没有选择可运行");
            return;
        }

        editor.saveFiles();

        Tab tab = new Tab();
        tab.setGraphic(new Label("运行: " + runnable.getName()));
        Runner runner = runnable.createRunner();
        tab.setContent(runner.createContent());

        Runnable run = () -> {
            editorTabPane.getTabs().add(tab);
            new Thread(runner::run).start();
        };
        if (event != null && event.isShiftDown()) {
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
        private final ListView<Tab> listView = new ListView<>(FXCollections.observableList(tabHistory));

        public Switcher() {
            listView.setCellFactory(Util.createListCell(tab -> {
                Node node = tab.getGraphic();
                SnapshotParameters params = new SnapshotParameters();
                params.setFill(Color.TRANSPARENT);
                WritableImage snapshot = node.snapshot(params, null);
                ImageView imageView = new ImageView(snapshot);
                imageView.setFitWidth(node.getBoundsInLocal().getWidth());
                imageView.setFitHeight(node.getBoundsInLocal().getHeight());
                return imageView;
            }));
            listView.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
                if (newTab instanceof FileTab.FileTabTab fileTab) {
                    pathLabel.setText(fileTab.getFile().getAbsolutePath());
                } else {
                    pathLabel.setText("");
                }
            });

            listView.getSelectionModel().select(1);
            if (listView.getSelectionModel().getSelectedItem().equals(editorTabPane.getSelectionModel().getSelectedItem())) {
                listView.getSelectionModel().select(0);
            }

            getChildren().addAll(listView, pathLabel);
        }

        public void next() {
            MultipleSelectionModel<Tab> selectionModel = listView.getSelectionModel();
            if (selectionModel.getSelectedIndex() == listView.getItems().size() - 1) {
                selectionModel.selectFirst();
            } else {
                selectionModel.selectNext();
            }
        }

        public void switchTab() {
            Tab selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                editorTabPane.getSelectionModel().select(selected);
            }
        }
    }
}
