package org.a8043.simpleIDE.resource;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.json.JSONArray;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import lombok.Getter;
import lombok.Setter;
import org.a8043.simpleIDE.Main;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class ResourceManager {
    @Getter
    @Setter
    private static Language currentLanguage;
    private static final List<Language> LANGUAGE_LIST = new ArrayList<>();
    private static final Map<String, Image> IMAGE_MAP = new HashMap<>();

    public static void initI18n() {
        for (String lang : new String[]{"zh_cn"}) {
            LANGUAGE_LIST.add(new Language(lang, ResourceUtil.readUtf8Str("languages/" + lang + ".json")));
        }
        ResourceManager.setCurrentLanguage(LANGUAGE_LIST.stream().filter(lang ->
            lang.getName().equals(Main.instance.getSettings().getLanguageName())).findFirst().orElse(null));
        currentLanguage.init();
    }

    public static void loadFirstImage() {
        IMAGE_MAP.put("icon", new Image(ResourceUtil.getStream("images/icon.png")));
        IMAGE_MAP.put("loading", new Image(ResourceUtil.getStream("images/loading.gif")));
    }

    public static void loadAllImage() {
        new JSONArray(ResourceUtil.readUtf8Str("images.json")).forEach(name -> {
            if (name.equals("icon.png") || name.equals("loading.gif")) {
                return;
            }
            String simpleName = ((String) name).split("\\.")[0];
            IMAGE_MAP.put(simpleName, new Image(ResourceUtil.getStream("images/" + name)));
        });
    }

    public static void setup(Node node) {
        Function<ObservableList<Node>, Void> setupAndListen = list -> {
            list.forEach(ResourceManager::setup);
            list.addListener((ListChangeListener<Node>) change -> {
                while (change.next()) {
                    if (change.wasAdded()) {
                        change.getAddedSubList().forEach(ResourceManager::setup);
                    }
                }
            });
            return null;
        };

        Function<TabPane, Void> setupTabPane = pane -> {
            pane.getTabs().forEach(tab -> setup(tab.getContent()));
            pane.getTabs().addListener((ListChangeListener<Tab>) change -> {
                while (change.next()) {
                    if (change.wasAdded()) {
                        change.getAddedSubList().forEach(tab -> setup(tab.getContent()));
                    }
                }
            });
            return null;
        };

        if (node instanceof Labeled labeled && !node.getStyleClass().contains("no-i18n")) {
            labeled.setText(getText(labeled.getText()));
            labeled.textProperty().addListener((obs, old, newText) -> labeled.setText(getText(newText)));
        } else if (node instanceof TextArea textArea && textArea.getStyleClass().contains("i18n")) {
            textArea.setText(getText(textArea.getText()));
            textArea.textProperty().addListener((obs, old, newText) -> textArea.setText(getText(newText)));
        } else if (node instanceof TabPane pane) {
            setupTabPane.apply(pane);
        } else if (node instanceof SplitPane pane) {
            setupAndListen.apply(pane.getItems());
        } else if (node instanceof Pane pane) {
            setupAndListen.apply(pane.getChildren());
        }
    }

    public static String getText(String key, Map<String, Object> args) {
        return currentLanguage.getText(key, args);
    }

    public static String getText(String key, Object arg) {
        return getText(key, Map.of("", arg));
    }

    public static String getText(String key) {
        return getText(key, Map.of());
    }

    public static Image getImage(String name) {
        return IMAGE_MAP.get(name);
    }

    public static ImageView createImageView(String name, int width, int height) {
        ImageView imageView = new ImageView(getImage(name));
        imageView.setFitWidth(width);
        imageView.setFitHeight(height);
        return imageView;
    }
}
