package org.a8043.simpleIDE.util;

import java.util.ArrayList;

public class FixedList<E> extends ArrayList<E> {
    private final int max;

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
