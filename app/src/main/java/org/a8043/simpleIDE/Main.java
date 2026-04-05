package org.a8043.simpleIDE;

import animatefx.animation.FadeIn;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.a8043.simpleIDE.resource.ResourceManager;
import org.a8043.simpleIDE.util.config.ConfigUtil;
import org.a8043.simpleIDE.views.LoadView;
import org.a8043.simpleIDE.views.WelcomeView;

import java.io.File;
import java.net.URL;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

@Slf4j
@Getter
public class Main extends Application {
    public static final String MAIN_STYLE = ResourceUtil.readUtf8Str("styles/Main.css");
    public static Main instance;

    static {
        Logger fxLogger = Logger.getLogger("javafx");
        fxLogger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                Level level = record.getLevel();
                if (level.equals(Level.INFO)) {
                    log.info(record.getMessage());
                } else if (level.equals(Level.WARNING)) {
                    log.warn(record.getMessage());
                } else if (level.equals(Level.SEVERE)) {
                    log.error(record.getMessage());
                } else {
                    log.debug(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    public static void main(String[] args) {
        launch(args);
    }

    private JSONObject recordJson;
    private Settings settings;
    private Stage stage;
    private final StackPane pane = new StackPane();

    public Main() {
        instance = this;
    }

    @Override
    public void start(Stage stage) throws Exception {
        this.stage = stage;

        ResourceManager.loadFirstImage();
        LoadView.Controller loadView = LoadView.showWindow();
        new Thread(() -> {
            Function<String, Void> stepTipSetter = text -> {
                Platform.runLater(() -> loadView.setStepTip(text));
                log.info("Loading: {}", text);
                return null;
            };

            load(stepTipSetter);

            stepTipSetter.apply("...");
            Platform.runLater(() -> {
                setUserAgentStylesheet(ResourceUtil.getResource("styles/Main.css").toString());
                stage.setScene(new Scene(pane, stage.getWidth(), stage.getHeight()));
                display(WelcomeView.FXML_URL);
                ResourceManager.setup(pane);

                stage.setTitle("Simple IDE");
                stage.getIcons().add(ResourceManager.getImage("icon"));
                stage.setWidth(1000);
                stage.setHeight(600);
                stage.show();

                Platform.runLater(loadView::close);
            });
        }).start();
    }

    @SneakyThrows
    private void load(Function<String, Void> stepTipSetter) {
        stepTipSetter.apply("正在读取record...");
        File recordJsonFile = new File("./record.json");
        if (!recordJsonFile.exists()) {
            FileUtil.writeUtf8String(new JSONObject().set("projects", new JSONArray()).toString(), recordJsonFile);
        }
        recordJson = new JSONObject(FileUtil.readUtf8String(recordJsonFile));

        stepTipSetter.apply("正在读取settings...");
        File settingsJsonFile = new File("./settings.json");
        Runnable generateSettings = () -> FileUtil.writeUtf8String(ConfigUtil.toJson(
            settings = Settings.fromDefault()).toString(), settingsJsonFile);
        if (!settingsJsonFile.exists()) {
            generateSettings.run();
        } else {
            try {
                settings = ConfigUtil.toObject(new JSONObject(FileUtil.readUtf8String(settingsJsonFile)), Settings.class);
            } catch (Exception e) {
                log.error(e.getMessage(), e);
                Object lock = new Object();
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                    alert.setTitle("错误");
                    alert.setContentText("选择操作");
                    alert.setContentText("加载设置文件时发生了错误");
                    ButtonType regenerateButton = new ButtonType("重新生成");
                    alert.getButtonTypes().setAll(new ButtonType("退出"), regenerateButton);
                    Runnable unlockRunnable = () -> {
                        synchronized (lock) {
                            lock.notify();
                        }
                    };
                    alert.showAndWait().ifPresentOrElse(buttonType -> {
                        if (buttonType == regenerateButton) {
                            generateSettings.run();
                        } else {
                            Platform.exit();
                        }
                        unlockRunnable.run();
                    }, () -> {
                        Platform.exit();
                        unlockRunnable.run();
                    });
                });
                synchronized (lock) {
                    lock.wait();
                }
            }
        }

        stepTipSetter.apply("正在加载i18n...");
        ResourceManager.initI18n();

        stepTipSetter.apply("正在加载图片...");
        ResourceManager.loadAllImage();

        stepTipSetter.apply("正在加载本地库...");
        System.loadLibrary("native");
    }

    @Override
    public void stop() throws Exception {
        log.info("正在保存record和settings...");
        FileUtil.writeUtf8String(recordJson.toString(), new File("./record.json"));
        FileUtil.writeUtf8String(ConfigUtil.toJson(settings).toString(), new File("./settings.json"));
    }

    public void display(Node node) {
        if (pane.getChildren().isEmpty()) {
            pane.getChildren().add(node);
        } else {
            pane.getChildren().set(0, node);
        }
    }

    public void display(URL url) {
        display(url, fxmlLoader -> {
        });
    }

    @SneakyThrows
    public void display(URL url, Consumer<FXMLLoader> beforeLoad) {
        FXMLLoader fxmlLoader = new FXMLLoader(url);
        beforeLoad.accept(fxmlLoader);
        display(fxmlLoader.<Parent>load());
    }

    public <N extends Node> ModalController<N> showModal(N node, double width, double height) {
        return showModal("tip", node, width, height);
    }

    public <N extends Node> ModalController<N> showModal(String name, N node, double width, double height) {
        double heightGap = (pane.getHeight() - height) / 2;
        double widthGap = (pane.getWidth() - width) / 2;
        return showModal(name, node, width, height, widthGap, heightGap, false);
    }

    public <N extends Node> ModalController<N> showModal(String name, N node, double width, double height,
                                                         double x, double y, boolean closeWhenClickOutside) {
        Button closeButton = new Button("x");
        closeButton.setTooltip(new Tooltip(ResourceManager.getText("close")));
        VBox modal = new VBox();
        if (name != null) {
            height += 5;
            BorderPane titleBar = new BorderPane(new Label(name), null, closeButton, null, null);
            titleBar.setMaxHeight(5);
            modal.getChildren().addAll(titleBar, new Separator());
        }
        modal.getChildren().add(node);
        modal.getStyleClass().add("modal");

        AnchorPane modalPane = new AnchorPane(modal);
        modalPane.getStyleClass().add("modal-bg");
        ModalController<N> controller = new ModalController<>(node, modalPane);
        AnchorPane.setTopAnchor(modal, y);
        AnchorPane.setLeftAnchor(modal, x);
        AnchorPane.setRightAnchor(modal, pane.getWidth() - (x + width));
        AnchorPane.setBottomAnchor(modal, pane.getHeight() - (y + height));

        if (closeWhenClickOutside) {
            modalPane.setOnMouseClicked(e -> {
                if (e.getTarget() == modalPane) {
                    controller.close();
                }
            });
        }
        closeButton.setOnAction(e -> controller.close());

        pane.getChildren().add(modalPane);
        new FadeIn(modalPane).play();
        return controller;
    }

    public void showTipModal(String text) {
        showTipModal(text, () -> {
        });
    }

    public void showTipModal(String text, Runnable onClose) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setFont(new Font(12));
        Button button = new Button("确定");
        VBox box = new VBox(label, button);
        box.setAlignment(Pos.CENTER);
        ModalController<VBox> modalController = showModal(box, 400, 300);
        modalController.setOnClose(onClose);
        button.setOnAction(e -> modalController.close());
    }

    @Getter
    public class ModalController<N extends Node> {
        private final N node;
        private final AnchorPane modalPane;
        @Setter
        private Runnable onClose;

        public ModalController(N node, AnchorPane modalPane) {
            this.node = node;
            this.modalPane = modalPane;
        }

        public void close() {
            pane.getChildren().remove(modalPane);
            if (onClose != null) {
                onClose.run();
            }
        }
    }
}
