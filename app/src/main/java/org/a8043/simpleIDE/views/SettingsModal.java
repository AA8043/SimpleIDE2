package org.a8043.simpleIDE.views;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.ArrayUtil;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.SneakyThrows;
import org.a8043.simpleIDE.Main;
import org.a8043.simpleIDE.resource.ResourceManager;
import org.a8043.simpleIDE.util.BeanMap;
import org.a8043.simpleIDE.util.Util;

import java.io.File;
import java.net.URL;
import java.util.*;

@Getter
public class SettingsModal {
    public static final URL FXML_URL = ResourceUtil.getResource("SettingsModal.fxml", SettingsModal.class);
    private static final Map<String, FolderStyleFactory> FOLDER_STYLE_MAP = new HashMap<>();
    private static final Map<String, ItemStyleFactory> ITEM_STYLE_MAP = new HashMap<>();
    private static final List<OnShowListener> ON_SHOW_LISTENER_LIST = new ArrayList<>();

    public interface FolderStyleFactory {
        Node create(Folder folder);
    }

    public interface ItemStyleFactory {
        Node create(Item item);
    }

    public interface OnShowListener {
        void onShow(SettingsModal settingsModal);
    }

    static {
        registerItemStyle("buildTool.maven.defaultPath", item -> new FileItem(item, false));
        registerItemStyle("buildTool.gradle.defaultPath", item -> new FileItem(item, false));
        registerOnShowListener(modal -> {
            if (modal.settings instanceof BeanMap beanMap && beanMap.getBean().equals(Main.instance.getSettings())) {
                modal.root.getChildren().add(new TreeItem<>(new Folder("about", null)));
            }
        });
        registerFolderStyle("about", folder -> new AboutPage().getPane());
    }

    public static void registerFolderStyle(String name, FolderStyleFactory contentSupplier) {
        FOLDER_STYLE_MAP.put(name, contentSupplier);
    }

    public static void registerItemStyle(String name, ItemStyleFactory contentSupplier) {
        ITEM_STYLE_MAP.put(name, contentSupplier);
    }

    public static void registerOnShowListener(OnShowListener listener) {
        ON_SHOW_LISTENER_LIST.add(listener);
    }

    private final Map<String, Object> settings;
    @FXML
    private SplitPane pane;
    @FXML
    private TreeView<Folder> folderTree;
    private final TreeItem<Folder> root = new TreeItem<>();

    @SneakyThrows
    public SettingsModal(Map<String, Object> settings) {
        this.settings = settings;
    }

    @SneakyThrows
    @FXML
    private void initialize() {
        folderTree.setRoot(root);
        folderTree.setShowRoot(false);
        folderTree.setCellFactory(Util.createTreeCell(folder ->
            new Label(ResourceManager.getText("settings." + folder.getPath()))));
        folderTree.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        folderTree.getSelectionModel().selectedItemProperty().addListener((obs, old, newItem) -> {
            if (newItem == null) {
                return;
            }
            pane.getItems().set(1, FOLDER_STYLE_MAP.getOrDefault(newItem.getValue().getPath(), FolderBox::new)
                .create(newItem.getValue()));
        });

        settings.forEach((k, v) -> {
            String[] path = k.split("\\.");
            Folder folder = getOrCreateFolder(ArrayUtil.sub(path, 0, path.length - 1));
            Item item = new Item(path[path.length - 1], v);
            folder.getItemList().add(item);
        });

        ON_SHOW_LISTENER_LIST.forEach(listener -> listener.onShow(this));
    }

    private Folder getOrCreateFolder(String[] path) {
        TreeItem<Folder> current = root;
        for (String name : path) {
            Optional<TreeItem<Folder>> childOpt = current.getChildren().stream()
                .filter(child -> child.getValue().name.equals(name)).findFirst();
            if (childOpt.isPresent()) {
                current = childOpt.get();
            } else {
                Folder newFolder = new Folder(name, current.getValue());
                TreeItem<Folder> newItem = new TreeItem<>(newFolder);
                current.getChildren().add(newItem);
                current = newItem;
            }
        }
        return current.getValue();
    }

    @AllArgsConstructor
    @Getter
    public static class Folder {
        private final String name;
        private final Folder parent;
        private final List<Item> itemList = new ArrayList<>();

        public String getPath() {
            if (parent == null) {
                return name;
            }
            return parent.getPath() + "." + name;
        }

        public List<Folder> getChildren(TreeItem<Folder> root) {
            return getTreeChildren(root).stream()
                .filter(child -> child.getValue() != null && child.getValue().parent == this)
                .map(TreeItem::getValue).toList();
        }
    }

    private static <T> List<TreeItem<T>> getTreeChildren(TreeItem<T> root) {
        List<TreeItem<T>> result = new ArrayList<>();
        Queue<TreeItem<T>> queue = new LinkedList<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            TreeItem<T> current = queue.poll();
            result.add(current);
            queue.addAll(current.getChildren());
        }
        return result;
    }

    @AllArgsConstructor
    @Getter
    public class Item {
        private final String name;
        private Object value;

        public void setValue(Object newValue) {
            value = newValue;
            settings.put(name, value);
        }

        public String getPath() {
            TreeItem<Folder> current = folderTree.getRoot();
            while (current != null) {
                if (current.getValue() != null && current.getValue().getItemList().contains(this)) {
                    return current.getValue().getPath() + "." + name;
                }
                Optional<TreeItem<Folder>> next = getTreeChildren(current).stream()
                    .filter(child -> child.getValue() != null && child.getValue().getItemList().contains(this))
                    .findFirst();
                if (next.isPresent()) {
                    current = next.get();
                } else {
                    break;
                }
            }
            return name;
        }
    }

    private class FolderBox extends VBox {
        public FolderBox(Folder folder) {
            List<Folder> folderChildren = folder.getChildren(root);
            setPadding(new Insets(20));
            getChildren().addFirst(new VBox(new Label(ResourceManager.getText("settings." + folder.getPath())) {{
                setFont(new Font(18));
            }}, new Label("settings." + ResourceManager.getText(folder.getPath() + ".description")) {{
                setWrapText(true);
            }}, new Separator() {{
                setOpacity(0);
                setPrefHeight(15);
            }}, new Label() {{
                int size = folderChildren.size();
                if (size > 0) {
                    setText(ResourceManager.getText("settings.subnodes", size));
                }
            }}, new HBox(folderChildren.stream().map(item -> new Hyperlink() {{
                setText(ResourceManager.getText("settings." + item.getPath()));
                setOnAction(e -> folderTree.getSelectionModel().select(getTreeChildren(root).stream()
                    .filter(child -> child.getValue() == item).findFirst().orElse(null)));
            }}).toList().toArray(new Hyperlink[0])) {{
                setSpacing(10);
            }}) {{
                getStyleClass().add("bezel");
            }});
            getChildren().add(1, new Separator() {{
                setOpacity(0);
                setPrefHeight(20);
            }});
            getChildren().addAll(folder.getItemList().stream().map(ItemBox::new).toList());
        }
    }

    private class ItemBox extends VBox {
        public ItemBox(Item item) {
            Node node = ITEM_STYLE_MAP.getOrDefault(item.getPath(), item1 -> switch (item.value) {
                case Boolean ignored -> new BooleanItem(item);
                case Integer ignored -> new IntItem(item);
                case String ignored -> new StringItem(item);
                case Enum<?> ignored -> new EnumItem(item);
                case File ignored -> new FileItem(item, true);
                default -> throw new RuntimeException();
            }).create(item);
            getChildren().addAll(new Label(ResourceManager.getText("settings." + item.getPath())) {{
                setFont(new Font(16));
            }}, new Label(ResourceManager.getText("settings." + item.getPath() + ".description")) {{
                setWrapText(true);
            }}, node);
        }
    }

    public static class BooleanItem extends CheckBox {
        public BooleanItem(Item item) {
            setSelected((boolean) item.value);
            selectedProperty().addListener((observable, oldValue, newValue) -> item.setValue(newValue));
        }
    }

    public static class IntItem extends TextField {
        public IntItem(Item item) {
            super(String.valueOf((int) item.value));
            textProperty().addListener((observable, oldValue, newValue) -> {
                try {
                    item.setValue(Integer.parseInt(newValue));
                } catch (NumberFormatException e) {
                    Main.instance.showTipModal(ResourceManager.getText("notNumber", newValue));
                }
            });
        }
    }

    public static class StringItem extends TextField {
        public StringItem(Item item) {
            super((String) item.value);
            textProperty().addListener((observable, oldValue, newValue) -> item.setValue(newValue));
        }
    }

    public static class EnumItem extends ComboBox<Enum<?>> {
        public EnumItem(Item item) {
            Enum<?> anEnum = (Enum<?>) item.value;
            getItems().addAll(anEnum.getClass().getEnumConstants());
            setValue(anEnum);
            valueProperty().addListener((observable, oldValue, newValue) -> item.setValue(newValue));
        }
    }

    private static class FileItem extends HBox {
        public FileItem(Item item, boolean isFile) {
            TextField textField = new TextField(((File) item.value).getAbsolutePath());
            Button button = new Button(ResourceManager.getText("select")) {{
                setOnAction(event -> {
                    File selectedFile = isFile ? new FileChooser().showOpenDialog(new Stage()) :
                        new DirectoryChooser().showDialog(new Stage());
                    if (selectedFile != null) {
                        textField.setText(selectedFile.getAbsolutePath());
                        item.setValue(selectedFile);
                    }
                });
            }};
            getChildren().addAll(textField, button);
        }
    }
}
