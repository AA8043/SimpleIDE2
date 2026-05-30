package org.a8043.simpleIDE.util;

import java.util.ArrayList;

/**
 * 固定长度的列表, 超过最大长度时会删除最早添加的元素
 * @param <E>
 */
public class FixedList<E> extends ArrayList<E> {
    private final int max;

    /**
     * 构造一个固定长度的列表
     * @param max 最大长度
     */
    public FixedList(int max) {
        this.max = max;
    }

    @Override
    public boolean add(E e) {
        boolean result = super.add(e);
        if (size() > max) {
            remove(0);
        }
        return result;
    }
}
