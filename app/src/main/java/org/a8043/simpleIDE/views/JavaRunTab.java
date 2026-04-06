package org.a8043.simpleIDE.views;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.thread.ThreadUtil;
import com.sun.jdi.*;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequestManager;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.concurrent.Worker;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.web.WebView;
import javafx.util.Callback;
import lombok.Getter;
import lombok.SneakyThrows;
import netscape.javascript.JSObject;
import org.a8043.simpleIDE.Main;
import org.a8043.simpleIDE.project.runnables.JavaRunnable;
import org.a8043.simpleIDE.resource.ResourceManager;
import org.a8043.simpleIDE.util.Util;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public class JavaRunTab {
    public static final URL FXML_URL = ResourceUtil.getResource("JavaRunTab.fxml", JavaRunTab.class);
    private static final URL HTML_URL = ResourceUtil.getResource("terminal/terminal.html");
    private final JavaRunnable.JavaRunner runner;
    private JSObject js;
    @FXML
    private WebView terminal;
    @FXML
    private Button stopButton;
    @FXML
    @Getter
    private TabPane infoTabPane;
    private boolean isLoaded;
    private final Object loadFinishedLock = new Object();

    public JavaRunTab(JavaRunnable.JavaRunner runner) {
        this.runner = runner;
    }

    @SneakyThrows
    @FXML
    private void initialize() {
        terminal.getEngine().load(HTML_URL.toExternalForm());
        terminal.getEngine().setUserStyleSheetLocation(ResourceUtil.getResource("styles/Terminal.css").toExternalForm());
        terminal.getEngine().getLoadWorker().stateProperty().addListener((obs, old, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                js = (JSObject) terminal.getEngine().executeScript("window");
                js.setMember("java", this);
                isLoaded = true;
                synchronized (loadFinishedLock) {
                    loadFinishedLock.notifyAll();
                }
            }
        });
    }

    @FXML
    private void stop() {
        runner.close();
    }

    @SneakyThrows
    public void waitForLoad() {
        if (isLoaded) {
            return;
        }
        synchronized (loadFinishedLock) {
            loadFinishedLock.wait();
        }
    }

    public void runDebugger() {
        Debugger debugger = new Debugger();
        ResourceManager.setup(debugger);
        Platform.runLater(() -> infoTabPane.getTabs().add(
            new Tab(ResourceManager.getText("runner.debugger"), debugger)));
    }

    private void runJs(String name, Object... args) {
        Platform.runLater(() -> js.call(name, args));
    }

    public void writeToTerminal(String text) {
        runJs("writeString", text);
    }

    public void clearTerminal() {
        runJs("clearTerminal");
    }

    public void writelnToTerminal(String text) {
        writeToTerminal(text + "\n");
    }

    private class Debugger extends BorderPane {
        private VirtualMachine vm;
        private EventRequestManager erm;

        @SneakyThrows
        public Debugger() {
            new Thread(() -> {
                AttachingConnector connector = getAttachingConnector("dt_socket");
                Map<String, Connector.Argument> arguments = Objects.requireNonNull(connector).defaultArguments();
                arguments.get("hostname").setValue("127.0.0.1");
                arguments.get("port").setValue(String.valueOf(runner.getDebugPort()));
                for (int i = 0; i < 5; i++) {
                    try {
                        vm = connector.attach(arguments);
                        break;
                    } catch (IOException | IllegalConnectorArgumentsException ignored) {
                    }
                    ThreadUtil.sleep(1000);
                }
                writeToTerminal(ResourceManager.getText("runner.debugger.connectedTip",
                    runner.getDebugPort()) + "\n");
                vm.resume();
                erm = vm.eventRequestManager();

                Platform.runLater(() -> {
                    AtomicReference<StackFrame> currentFrame = new AtomicReference<>();
                    ObservableList<BreakpointRequest> breakpointList = FXCollections.observableArrayList();
                    Map<BreakpointRequest, String> breakpointRequestClassMap = new HashMap<>();
                    ObservableList<StackFrame> frameList = FXCollections.observableArrayList();
                    ObservableList<Object> contentList = FXCollections.observableArrayList();

                    breakpointList.addListener((ListChangeListener<BreakpointRequest>) change -> {
                        while (change.next()) {
                            if (change.wasRemoved()) {
                                for (BreakpointRequest bp : change.getRemoved()) {
                                    bp.disable();
                                    erm.deleteEventRequest(bp);
                                    breakpointRequestClassMap.remove(bp);
                                }
                            }
                        }
                    });

                    ComboBox<ThreadReference> threadComboBox = new ComboBox<>();
                    Callback<ListView<ThreadReference>, ListCell<ThreadReference>> threadCell =
                        Util.createListCell(thread -> new Label(thread.name() + " (#" + thread.uniqueID() + ")"));
                    threadComboBox.setCellFactory(threadCell);
                    threadComboBox.setButtonCell(threadCell.call(null));
                    threadComboBox.setPrefWidth(Double.MAX_VALUE);
                    threadComboBox.setOnAction(e -> {
                        ThreadReference thread = threadComboBox.getSelectionModel().getSelectedItem();
                        if (thread != null) {
                            try {
                                frameList.setAll(thread.frames());
                            } catch (IncompatibleThreadStateException ex) {
                                throw new RuntimeException(ex);
                            }
                        }
                    });

                    class SuspendButton extends Button {
                        private boolean isPaused;

                        {
                            setText("runner.debugger.suspend");
                            setOnAction(e -> {
                                if (isPaused = !isPaused) {
                                    vm.suspend();
                                } else {
                                    vm.resume();
                                }
                                refresh();
                            });
                        }

                        public void refresh() {
                            if (isPaused) {
                                setText("runner.debugger.resume");
                                threadComboBox.getItems().setAll(vm.allThreads());
                                threadComboBox.getSelectionModel().selectFirst();
                            } else {
                                setText("runner.debugger.suspend");
                                threadComboBox.getItems().clear();
                                threadComboBox.getSelectionModel().clearSelection();
                                frameList.clear();
                                contentList.clear();
                            }
                        }
                    }
                    SuspendButton suspendButton = new SuspendButton();
                    new Thread(() -> {
                        EventQueue eventQueue = vm.eventQueue();
                        while (true) {
                            try {
                                EventSet eventSet = eventQueue.remove();
                                for (Event event : eventSet) {
                                    if (event instanceof BreakpointEvent breakpointEvent) {
                                        Location location = breakpointEvent.location();
                                        writelnToTerminal(ResourceManager.getText("runner.debugger.breakpoint.hitTip",
                                            location.sourceName() + ":" + location.lineNumber()));
                                        Platform.runLater(() -> {
                                            suspendButton.isPaused = true;
                                            suspendButton.refresh();
                                        });
                                    }
                                }
                            } catch (InterruptedException | AbsentInformationException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }).start();

                    setTop(new HBox(suspendButton, new Button("runner.debugger.breakpoint") {{
                        setOnAction(e -> {
                            @lombok.Value
                            class BreakpointInfo {
                                String clazz;
                                int line;
                            }
                            Function<BreakpointInfo, BreakpointRequest> newBreakpoint = info -> {
                                ReferenceType type = vm.classesByName(info.getClazz()).getFirst();
                                BreakpointRequest newBp;
                                try {
                                    newBp = erm.createBreakpointRequest(type.locationsOfLine(info.getLine()).getFirst());
                                } catch (AbsentInformationException ex) {
                                    throw new RuntimeException(ex);
                                }
                                newBp.enable();
                                breakpointList.add(newBp);
                                breakpointRequestClassMap.put(newBp, info.getClazz());
                                return newBp;
                            };
                            BorderPane rightPane = new BorderPane();
                            Main.instance.showModal("runner.debugger.breakpoint",
                                new SplitPane(new VBox(new Button("add") {{
                                    setOnAction(e -> {
                                        for (int i = 1; i < 100; i++) {
                                            try {
                                                newBreakpoint.apply(new BreakpointInfo(runner.getRunClass(), i));
                                                break;
                                            } catch (Exception ignored) {
                                            }
                                        }
                                    });
                                }}, new ListView<>(breakpointList) {{
                                    setCellFactory(Util.createListCell(breakpoint -> {
                                        Location loc = breakpoint.location();
                                        try {
                                            return new Label(loc.sourceName() + ":" + loc.lineNumber());
                                        } catch (AbsentInformationException ex) {
                                            throw new RuntimeException(ex);
                                        }
                                    }));
                                    getSelectionModel().selectedItemProperty().addListener(
                                        (obs, old, breakpoint) -> {
                                            if (breakpoint != null) {
                                                TextField classField = new TextField();
                                                TextField lineField = new TextField();
                                                Location loc = breakpoint.location();
                                                classField.setText(breakpointRequestClassMap.get(breakpoint));
                                                lineField.setText(String.valueOf(loc.lineNumber()));
                                                rightPane.setCenter(new GridPane(2, 2) {{
                                                    addRow(0, new Label("class"), classField);
                                                    addRow(1, new Label("line"), lineField);
                                                }});
                                                Runnable add = () -> {
                                                    breakpointList.remove(breakpoint);
                                                    getSelectionModel().select(newBreakpoint.apply(
                                                        new BreakpointInfo(classField.getText(),
                                                            Integer.parseInt(lineField.getText()))));
                                                };
                                                classField.textProperty().addListener((obs1, old1, newValue) ->
                                                    add.run());
                                                lineField.textProperty().addListener((obs1, old1, newValue) ->
                                                    add.run());
                                            }
                                        });
                                }}), rightPane), 600, 400);
                        });
                    }}));

                    setCenter(new SplitPane(new BorderPane(null,
                        threadComboBox, null, new ListView<>(frameList) {{
                        getSelectionModel().selectedItemProperty().addListener((obs, oldVal, frame) -> {
                            if (frame != null) {
                                currentFrame.set(frame);
                                try {
                                    contentList.setAll(frame.visibleVariables());
                                    contentList.addAll(frame.location().declaringType().allFields());
                                } catch (AbsentInformationException e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        });
                        setCellFactory(Util.createListCell(frame -> {
                            Location location = frame.location();
                            String name;
                            try {
                                name = location.sourceName();
                            } catch (AbsentInformationException e) {
                                throw new RuntimeException(e);
                            }
                            return new VBox(new Label(name + ":" + location.lineNumber()) {{
                                getStyleClass().add("not-important");
                            }}, new Label(location.method().name()) {{
                                setFont(new Font(12));
                            }});
                        }));
                    }}, null), new ListView<>(contentList) {{
                        setCellFactory(Util.createListCell(content -> {
                            if (content instanceof LocalVariable variable) {
                                Value value = currentFrame.get().getValue(variable);
                                return new HBox(ResourceManager.createImageView("var", 32, 32),
                                    new VBox(new Label(variable.name() + "(" + variable.typeName() + ")"),
                                        new Label(valueToString(value))));
                            } else if (content instanceof Field field) {
                                ObjectReference thisObject = currentFrame.get().thisObject();
                                Value value = null;
                                if (thisObject != null) {
                                    value = thisObject.getValue(field);
                                }
                                return new HBox(ResourceManager.createImageView("field", 32, 32),
                                    new VBox(new Label(field.name() + "(" + field.typeName() + ")"),
                                        new Label(valueToString(value))));
                            } else {
                                return new Label(content.toString());
                            }
                        }));

                        addEventFilter(MouseEvent.MOUSE_CLICKED, e -> {
                            if (e.getClickCount() == 2) {
                                Object content = getSelectionModel().getSelectedItem();
                                Value value = null;
                                String name = null;
                                String info = ResourceManager.getText("none");
                                if (content instanceof LocalVariable variable) {
                                    value = currentFrame.get().getValue(variable);
                                    name = variable.name();
                                } else if (content instanceof Field field) {
                                    ObjectReference thisObject = currentFrame.get().thisObject();
                                    if (thisObject != null) {
                                        value = thisObject.getValue(field);
                                    } else {
                                        value = currentFrame.get().location().declaringType().getValue(field);
                                    }
                                    name = field.name();
                                    info = Modifier.toString(field.modifiers());
                                }
                                if (name != null) {
                                    FXMLLoader loader = new FXMLLoader(VarModal.FXML_URL);
                                    String finalName = name;
                                    Value finalValue = value;
                                    String finalInfo = info;
                                    loader.setControllerFactory(clazz ->
                                        new VarModal(finalName, finalValue, finalInfo, currentFrame.get(), content));
                                    try {
                                        Main.instance.showModal(
                                            ResourceManager.getText("runner.debugger.varModal.title", name),
                                            loader.load(), 400, 300);
                                    } catch (IOException ex) {
                                        throw new RuntimeException(ex);
                                    }
                                }
                            }
                        });
                    }}));
                });
            }).start();
        }

        private static String valueToString(Value value) {
            return switch (value) {
                case null -> "null";
                case StringReference stringReference -> stringReference.value();
                case ObjectReference objectReference -> objectReference.referenceType().name() +
                                                        "@" + objectReference.hashCode();
                default -> value.toString();
            };
        }

        private static AttachingConnector getAttachingConnector(String transportName) {
            VirtualMachineManager vmm = Bootstrap.virtualMachineManager();
            for (AttachingConnector connector : vmm.attachingConnectors()) {
                if (connector.transport().name().equals(transportName)) {
                    return connector;
                }
            }
            return null;
        }
    }
}
