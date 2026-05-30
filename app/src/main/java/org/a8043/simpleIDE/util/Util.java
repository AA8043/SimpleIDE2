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

/**
 * 工具类
 */
public class Util {
    /**
     * 并行foreach的元素
     * @param list 列表
     * @param consumer 消费者函数
     * @param threadCount 线程数量
     * @param <T> 元素类型
     */
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

    /**
     * 创建ListCell的工厂
     * @param func 用于生成Node的函数
     * @return ListCell的工厂
     * @param <T> 元素类型
     */
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

    /**
     * 创建TreeCell的工厂
     * @param func 用于生成Node的函数
     * @return TreeCell的工厂
     * @param <T> 元素类型
     */
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
