package org.a8043.simpleIDE.util.config;

import cn.hutool.core.lang.func.Consumer3;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.ReflectUtil;
import cn.hutool.json.JSONObject;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

public class ConfigUtil {
    public static <T> T toObject(JSONObject json, Class<T> clazz) {
        if (clazz.getAnnotation(ConfigClass.class) == null) {
            throw new RuntimeException();
        }

        T object = ReflectUtil.newInstance(clazz);
        eachItem(object.getClass(), (path, field, item) -> {
            JSONObject lastJson = json;
            for (String aPath : ArrayUtil.sub(path, 0, path.length - 1)) {
                lastJson = lastJson.getJSONObject(aPath);
            }

            Object value = lastJson.get(path[path.length - 1]);
            if (value != null) {
                Class<?> type = field.getType();
                if (type == File.class) {
                    value = new File(value.toString());
                } else if (type.isEnum()) {
                    value = Enum.valueOf((Class<Enum>) type, value.toString());
                } else {
                    try {
                        Method method = type.getMethod("getByName", String.class);
                        value = method.invoke(null, value.toString());
                    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored) {
                    }
                }
            }

            ReflectUtil.setFieldValue(object, field, value);
        });
        return object;
    }

    public static <T> JSONObject toJson(T object) {
        if (object.getClass().getAnnotation(ConfigClass.class) == null) {
            throw new RuntimeException();
        }

        JSONObject json = new JSONObject();
        eachValue(object, (path, value, item) -> {
            JSONObject lastJson = json;
            for (String pathPoint : ArrayUtil.sub(path, 0, path.length - 1)) {
                if (!lastJson.containsKey(pathPoint)) {
                    lastJson.set(pathPoint, new JSONObject());
                }
                lastJson = lastJson.getJSONObject(pathPoint);
            }

            if (value instanceof File file) {
                value = file.getAbsolutePath();
            } else if (value instanceof Enum<?> anEnum) {
                value = anEnum.name();
            } else if (value != null) {
                try {
                    Method method = value.getClass().getMethod("name");
                    value = method.invoke(value);
                } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored) {
                }
            }

            lastJson.set(path[path.length - 1], value);
        });
        return json;
    }

    public static void eachItem(Class<?> clazz, Consumer3<String[], Field, Item> consumer) {
        for (Field field : ReflectUtil.getFields(clazz)) {
            Item item = field.getAnnotation(Item.class);
            if (item != null) {
                consumer.accept(item.value().replace("#", field.getName()).split("\\."), field, item);
            }
        }
    }

    public static void eachValue(Object object, Consumer3<String[], Object, Item> consumer) {
        eachItem(object.getClass(), (path, field, item) ->
            consumer.accept(path, ReflectUtil.getFieldValue(object, field), item));
    }

    public static Field getFieldByAnnotation(Class<?> clazz, Item item) {
        for (Field field : ReflectUtil.getFields(clazz)) {
            Item fieldItem = field.getAnnotation(Item.class);
            if (fieldItem != null && fieldItem.equals(item)) {
                return field;
            }
        }
        return null;
    }

    public static Item getAnnotationByPath(Class<?> clazz, String[] path) {
        AtomicReference<Item> result = new AtomicReference<>();
        eachItem(clazz, (aPath, field, item) -> {
            System.out.println(Arrays.toString(path));
            if (ArrayUtil.equals(aPath, path)) {
                result.set(item);
            }
        });
        return result.get();
    }
}
