package org.a8043.simpleIDE.views;

import cn.hutool.core.io.resource.ResourceUtil;
import com.sun.jdi.*;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.util.Callback;
import org.a8043.simpleIDE.resource.ResourceManager;
import org.a8043.simpleIDE.util.Util;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class VarModal {
    public static final URL FXML_URL = ResourceUtil.getResource("VarModal.fxml", VarModal.class);
    private static final List<Viewer> VIEWER_LIST = new ArrayList<>();

    static {
        registerViewer(new StringViewer());
    }

    public static void registerViewer(Viewer viewer) {
        VIEWER_LIST.add(viewer);
    }

    private final String name;
    private final Value value;
    private final String info;
    private final StackFrame frame;
    private final Object content;
    @FXML
    private Label nameLabel;
    @FXML
    private Label typeLabel;
    @FXML
    private Label otherInfoLabel;
    @FXML
    private Label accessLabel;
    @FXML
    private ComboBox<Viewer> viewerBox;
    @FXML
    private BorderPane contentPane;

    public VarModal(String name, Value value, String info, StackFrame frame, Object content) {
        this.name = name;
        this.value = value;
        this.info = info;
        this.frame = frame;
        this.content = content;
    }

    @FXML
    private void initialize() {
        nameLabel.setText(name);
        typeLabel.setText(value.type().name());
        otherInfoLabel.setText(info);
        if (content instanceof LocalVariable) {
            accessLabel.setText("none");
        } else if (info.contains("public")) {
            accessLabel.setText(ResourceManager.getText("access.public"));
        } else if (info.contains("private")) {
            accessLabel.setText(ResourceManager.getText("access.private"));
        } else if (info.contains("protected")) {
            accessLabel.setText(ResourceManager.getText("access.protected"));
        } else {
            accessLabel.setText(ResourceManager.getText("access.packagePrivate"));
        }

        Callback<ListView<Viewer>, ListCell<Viewer>> viewerCall = Util.createListCell(viewer ->
            new Label(ResourceManager.getText(viewer.getName())));
        viewerBox.setCellFactory(viewerCall);
        viewerBox.setButtonCell(viewerCall.call(null));
        VIEWER_LIST.forEach(viewer -> {
            if (viewer.canView(value, frame, content)) {
                viewerBox.getItems().add(viewer);
            }
        });
        viewerBox.valueProperty().addListener((obs, old, viewer) -> {
            if (viewer != null) {
                contentPane.setCenter(viewer.create(value, frame, content));
            }
        });
        viewerBox.getSelectionModel().selectFirst();
    }

    public interface Viewer {
        String getName();

        boolean canView(Value value, StackFrame frame, Object content);

        Node create(Value value, StackFrame frame, Object content);

        default void setValue(Object newValue, StackFrame frame, Object content) {
            VirtualMachine vm = frame.virtualMachine();
            Value value = switch (newValue) {
                case String v -> vm.mirrorOf(v);
                case Boolean v -> vm.mirrorOf(v);
                case Byte v -> vm.mirrorOf(v);
                case Short v -> vm.mirrorOf(v);
                case Integer v -> vm.mirrorOf(v);
                case Long v -> vm.mirrorOf(v);
                case Float v -> vm.mirrorOf(v);
                case Double v -> vm.mirrorOf(v);
                default -> throw new IllegalArgumentException("不支持的类型: " + newValue.getClass().getName());
            };

            if (content instanceof LocalVariable var) {
                try {
                    frame.setValue(var, value);
                } catch (InvalidTypeException | ClassNotLoadedException e) {
                    throw new RuntimeException(e);
                }
            } else if (content instanceof Field field) {
                try {
                    ObjectReference thisObject = frame.thisObject();
                    if (thisObject != null) {
                        thisObject.setValue(field, value);
                    } else if (frame.location().declaringType() instanceof ClassType classType) {
                        classType.setValue(field, value);
                    }
                } catch (InvalidTypeException | ClassNotLoadedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    public static class StringViewer implements Viewer {
        @Override
        public String getName() {
            return "runner.debugger.varModal.viewer.string";
        }

        @Override
        public boolean canView(Value value, StackFrame frame, Object content) {
            return value.type().name().equals("java.lang.String");
        }

        @Override
        public Node create(Value value, StackFrame frame, Object content) {
            return new TextArea(((StringReference) value).value()) {{
                textProperty().addListener((obs, old, newValue) -> setValue(newValue, frame, content));
            }};
        }
    }
}
