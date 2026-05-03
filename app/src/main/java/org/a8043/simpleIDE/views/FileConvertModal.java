package org.a8043.simpleIDE.views;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.Property;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.util.Callback;
import lombok.SneakyThrows;
import org.a8043.simpleIDE.Main;
import org.a8043.simpleIDE.fileConverters.JsonPropertiesConverter;
import org.a8043.simpleIDE.project.ProjectEditor;
import org.a8043.simpleIDE.util.Util;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class FileConvertModal {
    public static final URL FXML_URL = ResourceUtil.getResource("FileConvertModal.fxml", FileConvertModal.class);
    private static final List<Converter> CONVERTER_LIST = new ArrayList<>();

    public interface Converter {
        String getOriginalSuffix();

        String getTargetSuffix();

        Node createConfigNode(Map<String, Property<?>> configMap);

        byte[] convert(byte[] originalContent, Map<String, Object> configMap, DoubleProperty progress) throws Exception;
    }

    static {
        register(new JsonPropertiesConverter());
    }

    public static void register(Converter converter) {
        CONVERTER_LIST.add(converter);
    }

    @SneakyThrows
    public static void show(File file, ProjectEditor editor) {
        AtomicReference<Main.ModalController<?>> modal = new AtomicReference<>();
        FileConvertModal convertModal = new FileConvertModal(file, editor, () -> modal.get().close());
        FXMLLoader loader = new FXMLLoader(FXML_URL);
        loader.setControllerFactory(param -> convertModal);
        modal.set(Main.instance.showModal("fileConvert", loader.load(), 400, 300));
    }

    private final File file;
    private final ProjectEditor editor;
    private final Runnable close;
    @FXML
    private ComboBox<Converter> converterBox;
    @FXML
    private Label originalFileLabel;
    @FXML
    private VBox configBox;
    @FXML
    private CheckBox coverBox;
    private final Map<Converter, Map<String, Property<?>>> configMapMap = new HashMap<>();
    private final Map<Converter, Node> configNodeMap = new HashMap<>();

    public FileConvertModal(File file, ProjectEditor editor, Runnable close) {
        this.file = file;
        this.editor = editor;
        this.close = close;
    }

    @FXML
    private void initialize() {
        String suffix = FileUtil.getSuffix(file);
        originalFileLabel.setText(file.getName());
        Callback<ListView<Converter>, ListCell<Converter>> cell = Util.createListCell(c -> new Label(c.getTargetSuffix()));
        converterBox.setCellFactory(cell);
        converterBox.setButtonCell(cell.call(null));
        converterBox.getItems().addAll(CONVERTER_LIST.stream().filter(c ->
            c.getOriginalSuffix().equals(suffix.toLowerCase())).toList());
    }

    @FXML
    private void convert() {
        Converter converter = converterBox.getValue();
        if (converter == null) {
            return;
        }
        Map<String, Property<?>> configPropertyMap = configMapMap.get(converter);
        Map<String, Object> configMap = new HashMap<>();
        configPropertyMap.forEach((k, v) -> configMap.put(k, v.getValue()));

        editor.runTask(new Task<Void>() {
            private final DoubleProperty progress = (DoubleProperty) progressProperty();

            @Override
            protected Void call() throws Exception {
                progress.set(-1);
                byte[] originalContent = FileUtil.readBytes(file);
                byte[] convertedContent = converter.convert(originalContent, configMap, progress);
                progress.set(-1);
                FileUtil.writeBytes(convertedContent, new File(file.getParent(),
                    FileUtil.mainName(file.getName()) + "." + converter.getTargetSuffix()));
                if (coverBox.isSelected()) {
                    FileUtil.del(file);
                }
                return null;
            }
        });
        close();
    }

    @FXML
    private void switchConverter() {
        Converter converter = converterBox.getValue();
        if (converter == null) {
            return;
        }
        configBox.getChildren().setAll(configNodeMap.computeIfAbsent(converter, c -> converter.createConfigNode(
            configMapMap.computeIfAbsent(c, k -> new HashMap<>()))));
    }

    @FXML
    private void close() {
        close.run();
    }
}
