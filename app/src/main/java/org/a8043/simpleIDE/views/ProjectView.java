package org.a8043.simpleIDE.views;

import cn.hutool.core.io.resource.ResourceUtil;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.*;
import javafx.scene.control.skin.ComboBoxListViewSkin;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.*;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Callback;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.a8043.simpleIDE.Main;
import org.a8043.simpleIDE.fileEditor.ControllableFile;
import org.a8043.simpleIDE.project.ProjectEditor;
import org.a8043.simpleIDE.project.index.IndexPoint;
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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class ProjectView {
    public static final URL FXML_URL = ResourceUtil.getResource("ProjectView.fxml", ProjectView.class);
    private static volatile ProjectView CURRENT;
    private static final List<FileMenuItemFactory> FILE_MENU_ITEM_LIST = new ArrayList<>();

    public interface FileMenuItemFactory {
        MenuItem create(List<File> fileList, ProjectEditor editor);
    }

    static {
        register((fileList, editor) -> new MenuItem(ResourceManager.getText("copy")) {{
            setOnAction(e -> Clipboard.getSystemClipboard().setContent(Map.of(DataFormat.FILES, fileList)));
        }});
        register((fileList, editor) -> fileList.size() != 1 ? null :
            new MenuItem(ResourceManager.getText("copy.path")) {{
                setOnAction(e -> Clipboard.getSystemClipboard().setContent(Map.of(DataFormat.PLAIN_TEXT,
                    fileList.getFirst().getAbsolutePath())));
            }});
        register((fileList, editor) -> fileList.size() != 1 || fileList.getFirst().isDirectory() ? null :
            new MenuItem(ResourceManager.getText("fileConvert")) {{
                setOnAction(e -> FileConvertModal.show(fileList.getFirst(), editor));
            }});
    }

    public static void register(FileMenuItemFactory factory) {
        FILE_MENU_ITEM_LIST.add(factory);
    }

    private final ProjectEditor editor;
    private final List<Tab> tabHistory = new ArrayList<>();
    @FXML
    private AnchorPane pane;
    @FXML
    private ListView<Task<?>> taskList;
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
    @FXML
    public ComboBox<String> gitBranchBox;

    public ProjectView(ProjectEditor editor) {
        this.editor = editor;
        CURRENT = this;
    }

    public static ProjectView getCurrent() {
        return CURRENT;
    }

    @FXML
    private void initialize() {
        File projectDir = editor.getProject().getProjectDir();

        Main.instance.registerKeyBinding("runnable.run",
            () -> runRunnable(null), new KeyCodeCombination(KeyCode.F5), pane);
        Main.instance.registerKeyBinding("git.commit", () -> {
            if (new File(projectDir, ".git").exists()) {
                GitCommitModal.show(editor);
            } else {
                Main.instance.showConfirmModal("git.noGit", () -> GitUtil.init(projectDir));
            }
        }, new KeyCodeCombination(KeyCode.C,
            KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN, KeyCombination.ALT_DOWN), pane);

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

        fileTreeView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        fileTreeView.setCellFactory(Util.createTreeCell(FileUtil::getDisplayItem));
        refreshFileTree();

        Callback<ListView<RunnableTask>, ListCell<RunnableTask>> runnableBoxCellFactory =
            Util.createListCell(RunnableTask::createListItem);
        runnableBox.setCellFactory(runnableBoxCellFactory);
        runnableBox.setButtonCell(runnableBoxCellFactory.call(null));
        runnableBox.setItems(editor.getRunnableList());

        Runnable refreshBranchBox = () -> {
            gitBranchBox.getItems().setAll(GitUtil.getBranchList(projectDir));
            gitBranchBox.getSelectionModel().select(GitUtil.getCurrentBranch(projectDir));
        };
        gitBranchBox.setSkin(new ComboBoxListViewSkin<>(gitBranchBox) {
            @Override
            public Node getPopupContent() {
                refreshBranchBox.run();
                VBox box = new VBox(new HBox(new Button(ResourceManager.getText("git.createBranch")) {{
                    setOnAction(e -> Main.instance.showInputModal("git.createBranch",
                        name -> editor.runTask(new Task<Void>() {
                            @Override
                            protected Void call() throws Exception {
                                updateTitle(ResourceManager.getText("git.createBranch"));
                                if (GitUtil.createBranch(projectDir, name) != 0) {
                                    throw new Exception();
                                }
                                return null;
                            }

                            @Override
                            protected void succeeded() {
                                hide();
                                refreshBranchBox.run();
                                refreshFileTree();
                            }
                        })));
                }}), new Separator(), new ListView<>(gitBranchBox.getItems()) {{
                    setOnMouseClicked(event -> {
                        String branch = getSelectionModel().getSelectedItem();
                        if (branch != null) {
                            if (event.getButton() == MouseButton.PRIMARY) {
                                hide();
                                editor.runTask(new Task<Void>() {
                                    @Override
                                    protected Void call() throws Exception {
                                        updateTitle(ResourceManager.getText("git.switchBranch"));
                                        if (GitUtil.switchBranch(projectDir, branch) != 0) {
                                            throw new Exception();
                                        }
                                        return null;
                                    }

                                    @Override
                                    protected void succeeded() {
                                        refreshBranchBox.run();
                                        refreshFileTree();
                                    }
                                });
                            } else if (event.getButton() == MouseButton.SECONDARY) {
                                Main.instance.showConfirmModal("git.deleteBranch",
                                    () -> editor.runTask(new Task<Void>() {
                                        @Override
                                        protected Void call() throws Exception {
                                            updateTitle(ResourceManager.getText("git.deleteBranch"));
                                            if (GitUtil.deleteBranch(projectDir, branch) != 0) {
                                                throw new Exception();
                                            }
                                            return null;
                                        }

                                        @Override
                                        protected void succeeded() {
                                            refreshBranchBox.run();
                                            refreshFileTree();
                                        }
                                    }));
                            }
                        }
                    });
                }}) {{
                    getStyleClass().add("combo-box-popup");
                }};
                ResourceManager.setup(box);
                return box;
            }
        });
        refreshBranchBox.run();

        taskList.setCellFactory(Util.createListCell(task -> new HBox(new Label() {{
            textProperty().bind(task.titleProperty());
        }}, new ProgressBar() {{
            progressProperty().bind(task.progressProperty());
        }})));
        taskList.setItems(editor.getTaskList());
        editor.getTaskList().addListener((ListChangeListener<Task<?>>) c -> {
            while (c.next()) {
                if (c.wasAdded()) {
                    c.getAddedSubList().forEach(task -> task.setOnFailed(e -> {
                        log.error(task.getException().getMessage(), task.getException());
                        Main.instance.showTipModal(ResourceManager.getText("task.failed") + ": " +
                                                   task.getTitle() + "\n" + task.getException().getMessage());
                    }));
                }
            }
        });
    }

    private void refreshFileTree() {
        File projectDir = editor.getProject().getProjectDir();
        TreeItem<File> root = new TreeItem<>(projectDir);
        addFileToTreeItem(projectDir, root);
        fileTreeView.setRoot(root);
    }

    public void openFile(ControllableFile file, int caretPosition) {
        if (file == null) {
            return;
        }
        Tab tab = editorTabPane.getTabs().stream()
            .filter(existingTab -> existingTab instanceof FileTab.FileTabTab fileTab &&
                                   Objects.equals(file.getFile(), fileTab.getFile().getFile()))
            .findFirst().orElseGet(() -> {
                Tab newTab = FileTab.createTab(editor, file);
                editorTabPane.getTabs().add(newTab);
                return newTab;
            });
        editorTabPane.getSelectionModel().select(tab);
        if (tab instanceof FileTab.FileTabTab fileTab && fileTab.getController() != null) {
            fileTab.getController().navigateTo(caretPosition);
        }
    }

    public void openCachedSourceFile(IndexPoint point, int caretPosition) {
        if (point == null) {
            return;
        }
        Tab tab = editorTabPane.getTabs().stream()
            .filter(existingTab -> existingTab instanceof FileTab.FileTabTab fileTab && point.equals(fileTab.getPoint()))
            .findFirst().orElseGet(() -> {
                Tab newTab = FileTab.createCachedSourceTab(editor, point);
                editorTabPane.getTabs().add(newTab);
                return newTab;
            });
        editorTabPane.getSelectionModel().select(tab);
        if (tab instanceof FileTab.FileTabTab fileTab && fileTab.getController() != null) {
            fileTab.getController().navigateTo(caretPosition);
        }
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
    private void onFileKeyPressed(KeyEvent event) {
        if (event.getCode() == KeyCode.ENTER) {
            onFileClicked(new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                MouseButton.PRIMARY, 2, false, false, false, false,
                true, false, false, true,
                false, false, null));
        } else if (event.isControlDown() && event.getCode() == KeyCode.C) {
            List<TreeItem<File>> selectedItems = fileTreeView.getSelectionModel().getSelectedItems();
            if (!selectedItems.isEmpty()) {
                Clipboard.getSystemClipboard().setContent(Map.of(DataFormat.FILES,
                    selectedItems.stream().map(TreeItem::getValue).toList()));
            }
        }
    }

    @FXML
    private void openFileContextMenu(ContextMenuEvent event) {
        List<TreeItem<File>> selectedItems = fileTreeView.getSelectionModel().getSelectedItems();
        if (selectedItems.isEmpty()) {
            return;
        }
        ContextMenu contextMenu = new ContextMenu();
        contextMenu.getItems().addAll(FILE_MENU_ITEM_LIST.stream()
            .map(factory -> factory.create(selectedItems.stream().map(TreeItem::getValue).toList(), editor))
            .filter(Objects::nonNull).toList());
        contextMenu.show(fileTreeView, event.getScreenX(), event.getScreenY());
    }

    @FXML
    private void onFileClicked(MouseEvent event) {
        if (event.getClickCount() == 2) {
            TreeItem<File> selectedItem = fileTreeView.getSelectionModel().getSelectedItem();
            if (selectedItem != null) {
                File file = selectedItem.getValue();
                if (file.isFile()) {
                    openFile(editor.openFile(file, null, false), 0);
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
                    pathLabel.setText(fileTab.getFile().getFile() != null ?
                        fileTab.getFile().getFile().getAbsolutePath() : fileTab.getFile().getName());
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
