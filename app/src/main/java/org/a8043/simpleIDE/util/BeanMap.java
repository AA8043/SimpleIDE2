package org.a8043.simpleIDE.util;

import cn.hutool.core.annotation.Alias;
import lombok.Getter;

import java.lang.reflect.Field;
import java.util.*;

/**
 * 将一个JavaBean包装成一个Map
 */
public class BeanMap extends AbstractMap<String, Object> {
    @Getter
    private final Object bean;
    private final Map<String, Field> fieldMap = new HashMap<>();

    public BeanMap(Object bean) {
        this.bean = bean;
        for (Field field : bean.getClass().getDeclaredFields()) {
            String name;
            Alias alias = field.getAnnotation(Alias.class);
            if (alias != null) {
                name = alias.value();
            } else {
                name = field.getName();
            }
            field.setAccessible(true);
            fieldMap.put(name, field);
        }
    }

    @Override
    public Object get(Object key) {
        Field field = fieldMap.get(key);
        if (field == null) {
            throw new NoSuchElementException((String) key);
        }
        try {
            return field.get(bean);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Object put(String key, Object value) {
        Field field = fieldMap.get(key);
        if (field == null) {
            throw new NoSuchElementException(key);
        }
        try {
            Object oldValue = field.get(bean);
            field.set(bean, value);
            return oldValue;
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean containsKey(Object key) {
        return fieldMap.containsKey(key);
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        Set<Entry<String, Object>> entrySet = new HashSet<>();
        for (String key : fieldMap.keySet()) {
            entrySet.add(new AbstractMap.SimpleEntry<>(key, get(key)));
        }
        return entrySet;
    }
}
