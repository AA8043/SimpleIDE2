package org.a8043.simpleIDE.util;

import cn.hutool.core.io.watch.WatchMonitor;
import cn.hutool.core.io.watch.WatchUtil;
import cn.hutool.core.io.watch.Watcher;
import javafx.beans.property.ObjectProperty;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import org.a8043.simpleIDE.resource.ResourceManager;

import javax.swing.filechooser.FileSystemView;
import java.io.File;
import java.nio.file.WatchEvent;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public class FileUtil {
    private static final Map<String, Image> FILE_IMAGE_CACHE = new HashMap<>();

    public static String getRelativePath(File base, File full) {
        return base.toPath().relativize(full.toPath()).toString();
    }

    public static File findFileDirInFolders(List<File> srcDirList, String name) {
        AtomicReference<File> result = new AtomicReference<>();
        srcDirList.forEach(dir -> cn.hutool.core.io.FileUtil.walkFiles(dir, file -> {
            if (Objects.equals(file.getName(), name)) {
                result.set(dir);
            }
        }));
        return result.get();
    }

    public static Image getImage(File file) {
        String suffix = cn.hutool.core.io.FileUtil.getSuffix(file);
        if (FILE_IMAGE_CACHE.containsKey(suffix)) {
            return FILE_IMAGE_CACHE.get(suffix);
        } else {
            Image image = switch (suffix) {
                case "java" -> ResourceManager.getImage("class");
                case null, default -> {
                    javax.swing.Icon icon = FileSystemView.getFileSystemView().getSystemIcon(file);
                    java.awt.image.BufferedImage awtImage = new java.awt.image.BufferedImage(
                        icon.getIconWidth(),
                        icon.getIconHeight(),
                        java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    java.awt.Graphics graphics = awtImage.getGraphics();
                    icon.paintIcon(null, graphics, 0, 0);
                    graphics.dispose();
                    yield SwingFXUtils.toFXImage(awtImage, null);
                }
            };
            FILE_IMAGE_CACHE.put(suffix, image);
            return image;
        }
    }

    public static ImageView getImageView(File file, int width, int height) {
        ImageView imageView = new ImageView(getImage(file));
        imageView.setFitWidth(width);
        imageView.setFitHeight(height);
        return imageView;
    }

    public static HBox getDisplayItem(File file) {
        return new HBox(getImageView(file, 16, 16), new Label(file.getName()) {{
            if (file.isFile()) {
                ObjectProperty<GitUtil.FileStatus> fileStatus = GitUtil.getFileStatus(file);
                Function<GitUtil.FileStatus, Void> onChange = status -> {
                    setStyle(switch (status) {
                        case NORMAL -> "";
                        case ADDED -> "-fx-text-fill: rgb(114, 164, 77);";
                        case CHANGED -> "-fx-text-fill: rgb(99, 173, 255);";
                        case REMOVED -> "-fx-text-fill: rgb(255, 99, 99);";
                        case UNTRACKED, IGNORED -> "-fx-text-fill: rgb(213, 135, 69);";
                    });
                    return null;
                };
                fileStatus.addListener((observable, oldValue, newValue) -> onChange.apply(newValue));
                onChange.apply(fileStatus.get());
            }
        }});
    }

    private static final List<WatchMonitor> WATCH_MONITOR_LIST = new ArrayList<>();

    public static void watch(File file, Watcher watcher, WatchEvent.Kind<?>... events) {
        WatchMonitor watchMonitor = WatchUtil.create(file, events);
        WATCH_MONITOR_LIST.add(watchMonitor);
        watchMonitor.setWatcher(watcher);
        watchMonitor.start();
    }

    public static void close() {
        WATCH_MONITOR_LIST.forEach(WatchMonitor::close);
    }
}
