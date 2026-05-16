package org.a8043.simpleIDE.resource;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.core.util.ZipUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.a8043.simpleIDE.Main;
import org.a8043.simpleIDE.plugin.Plugin;
import org.a8043.simpleIDE.plugin.PluginManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.zip.ZipFile;

@Slf4j
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

    public static void loadResourcePackages() {
        File packagesDir = new File("./resourcePackages");
        if (!packagesDir.exists() && !packagesDir.mkdirs()) {
            throw new RuntimeException();
        }

        List<File> packageList = new ArrayList<>();
        packageList.addAll(PluginManager.PLUGIN_LIST.stream().map(Plugin::getClassLoader)
            .map(classLoader -> classLoader.getResource("resourcePackage.zip"))
            .filter(Objects::nonNull)
            .map(url -> FileUtil.writeFromStream(URLUtil.getStream(url), FileUtil.createTempFile())).toList());
        packageList.addAll(List.of(Objects.requireNonNull(packagesDir.listFiles(file ->
            file.isFile() && file.getName().endsWith(".zip")))));

        packageList.forEach(file -> {
            log.info("加载资源包: {}", file.getName());
            try (ZipFile zip = ZipUtil.toZipFile(file, StandardCharsets.UTF_8)) {
                ZipUtil.listFileNames(zip, "images").forEach(fileName -> {
                    try (InputStream stream = ZipUtil.getStream(zip, zip.getEntry("images/" + fileName))) {
                        IMAGE_MAP.put(fileName.split("\\.")[0], new Image(stream));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

                ZipUtil.listFileNames(zip, "languages").forEach(fileName -> {
                    String languageName = fileName.split("\\.")[0];
                    String content;
                    try (InputStream stream = ZipUtil.getStream(zip, zip.getEntry("languages/" + fileName))) {
                        content = IoUtil.readUtf8(stream);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    LANGUAGE_LIST.stream().filter(lang -> lang.getName().equals(languageName))
                        .findFirst().ifPresentOrElse(language -> {
                            JSONObject json = new JSONObject(content).getJSONObject("texts");
                            json.forEach((k, v) -> language.getTextMap().put(k, (String) v));
                        }, () -> LANGUAGE_LIST.add(new Language(languageName, content)));
                });
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
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
