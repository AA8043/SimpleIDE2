package org.a8043.simpleIDE.util;

import cn.hutool.core.io.FileUtil;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeView;
import javafx.scene.image.ImageView;
import javafx.util.Callback;
import lombok.SneakyThrows;
import org.a8043.simpleIDE.resource.ResourceManager;

import javax.swing.filechooser.FileSystemView;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

public class Util {
    public static String getRelativePath(File base, File full) {
        String basePath = base.getAbsolutePath();
        String fullPath = full.getAbsolutePath();
        if (!fullPath.startsWith(basePath)) {
            return fullPath;
        }
        return fullPath.substring(basePath.length() + 1);
    }

    public static File findFileDirInFolders(List<File> srcDirList, String name) {
        AtomicReference<File> result = new AtomicReference<>();
        srcDirList.forEach(dir -> FileUtil.walkFiles(dir, file -> {
            if (Objects.equals(file.getName(), name)) {
                result.set(dir);
            }
        }));
        return result.get();
    }

    private static final Map<String, javafx.scene.image.Image> FILE_IMAGE_CACHE = new HashMap<>();

    public static javafx.scene.image.Image getFileImage(File file) {
        String suffix = FileUtil.getSuffix(file);
        if (FILE_IMAGE_CACHE.containsKey(suffix)) {
            return FILE_IMAGE_CACHE.get(suffix);
        } else {
            javafx.scene.image.Image image = switch (suffix) {
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

    public static ImageView getFileImageView(File file, int width, int height) {
        ImageView imageView = new ImageView(getFileImage(file));
        imageView.setFitWidth(width);
        imageView.setFitHeight(height);
        return imageView;
    }

    @SneakyThrows
    public static <T> void parallelForEach(List<T> list, Consumer<T> consumer, int threadCount) {
        ExecutorService executor = new ThreadPoolExecutor(threadCount, threadCount,
            60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        CountDownLatch latch = new CountDownLatch(list.size());
        list.forEach(obj -> executor.submit(() -> {
            try {
                consumer.accept(obj);
            } finally {
                latch.countDown();
            }
        }));
        latch.await();
        executor.close();
    }

    public static <T> Callback<ListView<T>, ListCell<T>> createListCell(Function<T, Node> func) {
        return param -> new ListCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                if (item != null && !empty) {
                    setGraphic(func.apply(item));
                } else {
                    setText(null);
                    setGraphic(null);
                }
            }
        };
    }

    public static <T> Callback<TreeView<T>, TreeCell<T>> createTreeCell(Function<T, Node> func) {
        return param -> new TreeCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                if (item != null && !empty) {
                    setGraphic(func.apply(item));
                } else {
                    setText(null);
                    setGraphic(null);
                }
            }
        };
    }
}
