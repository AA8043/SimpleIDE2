package org.a8043.simpleIDE.fileConverters;

import cn.hutool.json.JSONUtil;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.Property;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import org.a8043.simpleIDE.resource.ResourceManager;
import org.a8043.simpleIDE.views.FileConvertModal;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

public class PropertiesJsonConverter implements FileConvertModal.Converter {
    @Override
    public String getOriginalSuffix() {
        return "properties";
    }

    @Override
    public String getTargetSuffix() {
        return "json";
    }

    @Override
    public Node createConfigNode(Map<String, Property<?>> configMap) {
        return new GridPane(2, 2) {{
            addRow(0, new Label(ResourceManager.getText("fileConvert.hierarchicalStructure")),
                new CheckBox() {{
                    setSelected(true);
                    configMap.put("hierarchicalStructure", selectedProperty());
                }});
            addRow(1, new Label(ResourceManager.getText("fileConvert.hierarchicalDelimiter")),
                new TextField(".") {{
                    configMap.put("hierarchicalDelimiter", textProperty());
                }});
        }};
    }

    @Override
    public byte[] convert(byte[] originalContent, Map<String, Object> configMap, DoubleProperty progress) throws Exception {
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(originalContent));
        return JSONUtil.toJsonPrettyStr((boolean) configMap.get("hierarchicalStructure") ?
            convertHierarchical(properties, (String) configMap.get("hierarchicalDelimiter")) :
            convertFlat(properties)).getBytes(StandardCharsets.UTF_8);
    }

    private Object convertFlat(Properties properties) {
        Map<String, Object> json = new LinkedHashMap<>();
        properties.forEach((key, value) -> json.put(String.valueOf(key), String.valueOf(value)));
        return json;
    }

    private Object convertHierarchical(Properties properties, String delimiter) {
        Object root = new LinkedHashMap<String, Object>();
        for (String key : properties.stringPropertyNames()) {
            String[] path = splitPath(key, delimiter);
            root = insertValue(root, path, 0, properties.getProperty(key));
        }
        return root;
    }

    private String[] splitPath(String key, String delimiter) {
        if (delimiter == null || delimiter.isEmpty()) {
            return new String[]{key};
        }
        return key.split(Pattern.quote(delimiter), -1);
    }

    private Object insertValue(Object container, String[] path, int depth, String value) {
        String current = path[depth];
        boolean last = depth == path.length - 1;
        if (depth > 0 && isArrayIndex(current)) {
            List<Object> list = container instanceof List<?> objects ? (List<Object>) objects : new ArrayList<>();
            int index = Integer.parseInt(current);
            ensureListSize(list, index);
            if (last) {
                list.set(index, value);
            } else {
                Object child = list.get(index);
                if (!isCompatibleContainer(child, path[depth + 1])) {
                    child = createContainer(path[depth + 1]);
                }
                list.set(index, insertValue(child, path, depth + 1, value));
            }
            return list;
        }

        Map<String, Object> map = container instanceof Map<?, ?> entries ?
            (Map<String, Object>) entries : new LinkedHashMap<>();
        if (last) {
            map.put(current, value);
        } else {
            Object child = map.get(current);
            if (!isCompatibleContainer(child, path[depth + 1])) {
                child = createContainer(path[depth + 1]);
            }
            map.put(current, insertValue(child, path, depth + 1, value));
        }
        return map;
    }

    private Object createContainer(String next) {
        return isArrayIndex(next) ? new ArrayList<>() : new LinkedHashMap<String, Object>();
    }

    private boolean isCompatibleContainer(Object value, String next) {
        if (value == null) {
            return false;
        }
        return isArrayIndex(next) ? value instanceof List<?> : value instanceof Map<?, ?>;
    }

    private boolean isArrayIndex(String value) {
        return value != null && value.matches("\\d+");
    }

    private void ensureListSize(List<Object> list, int index) {
        while (list.size() <= index) {
            list.add(null);
        }
    }
}
