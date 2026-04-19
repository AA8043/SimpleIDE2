package org.a8043.simpleIDE.util;

import javafx.scene.Node;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeView;
import javafx.util.Callback;
import lombok.SneakyThrows;

import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;

public class Util {
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
