package org.a8043.simpleIDE.views;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.ReflectUtil;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.Value;
import org.a8043.simpleIDE.Main;
import org.a8043.simpleIDE.resource.ResourceManager;
import org.a8043.simpleIDE.util.Util;
import org.a8043.simpleIDE.util.config.ConfigClass;
import org.a8043.simpleIDE.util.config.ConfigUtil;
import org.a8043.simpleIDE.util.config.Item;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class SettingsModal {
    public static final URL FXML_URL = ResourceUtil.getResource("SettingsModal.fxml", SettingsModal.class);
    private final Object settings;
    private final Map<List<String>, NodeInfo> nodeInfoMap = new HashMap<>();
    @Getter
    @FXML
    private SplitPane pane;
    @FXML
    private TreeView<String[]> classificationTree;

    @SneakyThrows
    public SettingsModal(Object settings) {
        this.settings = settings;
    }

    private NodeInfo getNodeInfo(String[] path) {
        return nodeInfoMap.get(List.of(path));
    }

    @SneakyThrows
    @FXML
    private void initialize() {
        ConfigClass annotation = settings.getClass().getAnnotation(ConfigClass.class);
        Properties nodesNameProperties = new Properties();
        nodesNameProperties.load(new ByteArrayInputStream(annotation.nodesName().getBytes(StandardCharsets.UTF_8)));
        nodesNameProperties.forEach((k, v) -> {
            String[] split = v.toString().split("\\+");
            nodeInfoMap.put(List.of(k.toString().split("\\.")), new NodeInfo(split[0], split[1]));
        });

        TreeItem<String[]> root = new TreeItem<>();
        Map<TreeItem<String[]>, SortedBox> contentMap = new HashMap<>();

        classificationTree.setCellFactory(Util.createTreeCell(item ->
            new Label(ResourceManager.getText(getNodeInfo(item).getTitle()))));

        classificationTree.setRoot(root);
        classificationTree.setShowRoot(false);
        classificationTree.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                VBox content = contentMap.get(newValue);
                if (content != null) {
                    ObservableList<Node> itemList = pane.getItems();
                    if (itemList.size() > 1) {
                        itemList.set(1, content);
                    } else {
                        itemList.add(content);
                    }
                }
            }
        });

        Map<List<String>, TreeItem<String[]>> classificationMap = new HashMap<>();
        ConfigUtil.eachItem(settings.getClass(), (path, field, item) -> {
            TreeItem<String[]> last = root;
            for (String pathPoint : ArrayUtil.sub(path, 0, path.length - 1)) {
                TreeItem<String[]> finalLast = last;
                last = classificationMap.computeIfAbsent(new ArrayList<>() {{
                    if (finalLast != root) {
                        addAll(List.of(finalLast.getValue()));
                    }
                    add(pathPoint);
                }}, k -> new TreeItem<>(k.toArray(new String[0])));
            }
        });
        classificationMap.forEach((path, treeItem) -> {
            String[] pathArray = path.toArray(new String[0]);
            contentMap.put(treeItem, new SortedBox(treeItem, getNodeInfo(pathArray)));
        });
        ConfigUtil.eachValue(settings, (path, value, item) -> {
            TreeItem<String[]> treeItem = classificationMap.get(new ArrayList<>(List.of(path)) {{
                remove(size() - 1);
            }});
            SortedBox sortedBox = contentMap.get(treeItem);
            sortedBox.add(new ItemBox(value, item));
        });
        addToTreeItem(contentMap, root);
        contentMap.values().forEach(SortedBox::init);
    }

    private void addToTreeItem(Map<TreeItem<String[]>, SortedBox> contentMap, TreeItem<String[]> root) {
        Map<TreeItem<String[]>, List<TreeItem<String[]>>> childrenMap = new HashMap<>();
        for (TreeItem<String[]> treeItem : contentMap.keySet()) {
            TreeItem<String[]> parent = findParentTreeItem(contentMap.keySet(), treeItem, root);
            if (parent != null) {
                childrenMap.computeIfAbsent(parent, k -> new ArrayList<>()).add(treeItem);
            } else {
                childrenMap.computeIfAbsent(root, k -> new ArrayList<>()).add(treeItem);
            }
        }
        addChildren(root, childrenMap);
    }

    private void addChildren(TreeItem<String[]> parent,
                             Map<TreeItem<String[]>, List<TreeItem<String[]>>> childrenMap) {
        List<TreeItem<String[]>> children = childrenMap.get(parent);
        if (children != null) {
            children.sort(Comparator.comparing(a -> String.join(".", a.getValue())));
            for (TreeItem<String[]> child : children) {
                parent.getChildren().add(child);
                addChildren(child, childrenMap);
            }
        }
    }

    private TreeItem<String[]> findParentTreeItem(Set<TreeItem<String[]>> allItems,
                                                  TreeItem<String[]> currentItem,
                                                  TreeItem<String[]> root) {
        String[] currentPath = currentItem.getValue();
        if (currentPath.length == 1) {
            return root;
        }
        String[] parentPath = Arrays.copyOf(currentPath, currentPath.length - 1);
        for (TreeItem<String[]> item : allItems) {
            if (Arrays.equals(item.getValue(), parentPath)) {
                return item;
            }
        }

        return root;
    }

    private class ItemBox extends VBox {
        private Object value;
        private final Item item;

        public ItemBox(Object value, Item item) {
            this.value = value;
            this.item = item;

            Node modifiable = switch (value) {
                case Boolean bool -> new CheckBox() {{
                    setSelected(bool);
                    selectedProperty().addListener((observable, oldValue, newValue) -> {
                        ItemBox.this.value = newValue;
                        save();
                    });
                }};
                case Integer i -> new TextField(i.toString()) {{
                    textProperty().addListener((observable, oldValue, newValue) -> {
                        try {
                            ItemBox.this.value = Integer.parseInt(newValue);
                            save();
                        } catch (NumberFormatException e) {
                            Main.instance.showTipModal(ResourceManager.getText("notNumber", newValue));
                        }
                    });
                }};
                case String string -> new TextField(string) {{
                    textProperty().addListener((observable, oldValue, newValue) -> {
                        ItemBox.this.value = newValue;
                        save();
                    });
                }};
                case Enum<?> anEnum -> new ComboBox<Enum<?>>() {{
                    getItems().addAll(anEnum.getClass().getEnumConstants());
                    setValue(anEnum);
                    valueProperty().addListener((observable, oldValue, newValue) -> {
                        ItemBox.this.value = newValue;
                        save();
                    });
                }};
                case File file -> new HBox() {{
                    TextField textField = new TextField(file.getAbsolutePath());
                    Button button = new Button(ResourceManager.getText("select")) {{
                        setOnAction(event -> {
                            File selectedFile = new DirectoryChooser().showDialog(new Stage());
                            if (selectedFile != null) {
                                textField.setText(selectedFile.getAbsolutePath());
                                ItemBox.this.value = selectedFile;
                                save();
                            }
                        });
                    }};
                    getChildren().addAll(textField, button);
                }};
                default -> throw new RuntimeException();
            };
            getChildren().addAll(new Label(ResourceManager.getText(item.title())) {{
                                     setFont(new Font(16));
                                 }},
                new Label(ResourceManager.getText(item.description())) {{
                    setWrapText(true);
                }}, modifiable);
        }

        public void save() {
            Field field = ConfigUtil.getFieldByAnnotation(settings.getClass(), item);
            if (field != null) {
                ReflectUtil.setFieldValue(settings, field, value);
            }
        }
    }

    private class SortedBox extends VBox {
        private final TreeItem<String[]> treeItem;
        private final String title;
        private final String description;

        public SortedBox(TreeItem<String[]> treeItem, NodeInfo nodeInfo) {
            this.treeItem = treeItem;
            title = nodeInfo.getTitle();
            description = nodeInfo.getDescription();
        }

        public void init() {
            setPadding(new Insets(20));
            getChildren().addFirst(new VBox(new Label(ResourceManager.getText(title)) {{
                setFont(new Font(18));
            }}, new Label(ResourceManager.getText(description)) {{
                setWrapText(true);
            }}, new Separator() {{
                setOpacity(0);
                setPrefHeight(15);
            }}, new Label() {{
                int size = treeItem.getChildren().size();
                if (size > 0) {
                    setText(ResourceManager.getText("settings.subnodes", size));
                }
            }}, new HBox(treeItem.getChildren().stream().map(item -> new Hyperlink() {{
                setText(ResourceManager.getText(getNodeInfo(item.getValue()).getTitle()));
                setOnAction(e -> classificationTree.getSelectionModel().select(item));
            }}).toList().toArray(new Hyperlink[0])) {{
                setSpacing(10);
            }}) {{
                getStyleClass().add("bezel");
            }});
            getChildren().add(1, new Separator() {{
                setOpacity(0);
                setPrefHeight(20);
            }});
        }

        public void add(ItemBox itemBox) {
            getChildren().addAll(itemBox, new Separator() {{
                setOpacity(0);
                setPrefHeight(15);
            }});
        }
    }

    @Value
    private static class NodeInfo {
        String title;
        String description;
    }
}
