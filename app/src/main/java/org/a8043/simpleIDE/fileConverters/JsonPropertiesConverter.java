package org.a8043.simpleIDE.fileConverters;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
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

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.Properties;

public class JsonPropertiesConverter implements FileConvertModal.Converter {
    @Override
    public String getOriginalSuffix() {
        return "json";
    }

    @Override
    public String getTargetSuffix() {
        return "properties";
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
        String str = new String(originalContent);
        Properties properties = new Properties();
        boolean hierarchicalStructure = (boolean) configMap.get("hierarchicalStructure");
        String hierarchicalDelimiter = (String) configMap.get("hierarchicalDelimiter");
        if (JSONUtil.isTypeJSONObject(str)) {
            convert("", new JSONObject(str), properties, hierarchicalStructure, hierarchicalDelimiter);
        } else {
            convert("", new JSONArray(str), properties, hierarchicalStructure, hierarchicalDelimiter);
        }
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        properties.store(outputStream, "Json to properties");
        return outputStream.toByteArray();
    }

    private void convert(String prefix, JSONObject jsonObject, Properties properties,
                         boolean hierarchical, String delimiter) {
        jsonObject.forEach((key, value) -> {
            String fullKey = hierarchical ? (prefix.isEmpty() ? key : prefix + delimiter + key) : key;
            if (value instanceof JSONObject json) {
                if (hierarchical) {
                    convert(fullKey, json, properties, true, delimiter);
                } else {
                    convert("", json, properties, false, delimiter);
                }
            } else if (value instanceof JSONArray array) {
                if (hierarchical) {
                    convert(fullKey, array, properties, true, delimiter);
                } else {
                    properties.setProperty(key, value.toString());
                }
            } else {
                String stringValue = value != null ? value.toString() : "";
                properties.setProperty(fullKey, stringValue);
            }
        });
    }

    private void flattenRootArray(String prefix, JSONArray array, Properties properties,
                                  boolean hierarchical, String delimiter) {
        for (int i = 0; i < array.size(); i++) {
            Object value = array.get(i);
            String arrayPrefix = prefix.isEmpty() ? String.valueOf(i) : prefix + delimiter + i;
            if (value instanceof JSONObject json) {
                convert(arrayPrefix, json, properties, hierarchical, delimiter);
            } else if (value instanceof JSONArray array1) {
                convert(arrayPrefix, array1, properties, hierarchical, delimiter);
            } else {
                String stringValue = value != null ? value.toString() : "";
                properties.setProperty(arrayPrefix, stringValue);
            }
        }
    }

    private void convert(String prefix, JSONArray array, Properties properties,
                         boolean hierarchical, String delimiter) {
        for (int i = 0; i < array.size(); i++) {
            Object value = array.get(i);
            String arrayKey = prefix + delimiter + i;
            if (value instanceof JSONObject) {
                convert(arrayKey, (JSONObject) value, properties, hierarchical, delimiter);
            } else if (value instanceof JSONArray) {
                convert(arrayKey, (JSONArray) value, properties, hierarchical, delimiter);
            } else {
                String stringValue = value != null ? value.toString() : "";
                properties.setProperty(arrayKey, stringValue);
            }
        }
    }
}
