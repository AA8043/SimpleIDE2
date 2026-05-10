package org.a8043.simpleIDE.views;

import cn.hutool.core.io.resource.ResourceUtil;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import lombok.SneakyThrows;
import org.a8043.simpleIDE.Main;
import org.a8043.simpleIDE.resource.ResourceManager;

public class LoadView {
    @SneakyThrows
    public static Controller showWindow() {
        FXMLLoader fxmlLoader = new FXMLLoader(ResourceUtil.getResource("LoadView.fxml", LoadView.class));
        Stage stage = new Stage();
        stage.setTitle("Simple IDE");
        stage.getIcons().add(ResourceManager.getImage("icon"));
        stage.initStyle(StageStyle.UNDECORATED);
        stage.setWidth(600);
        stage.setHeight(400);
        stage.setScene(new Scene(fxmlLoader.load(), 1000, 600));
        stage.show();
        LoadView loadView = fxmlLoader.getController();
        return new Controller() {
            @Override
            public void setStepTip(String text) {
                loadView.setStepTip(text);
            }

            @Override
            public void close() {
                stage.close();
            }
        };
    }

    @FXML
    private ImageView iconView;
    @FXML
    private Label stepTipLabel;
    @FXML
    private Label versionLabel;

    @FXML
    private void initialize() {
        iconView.setImage(ResourceManager.getImage("icon"));
        stepTipLabel.setGraphic(ResourceManager.createImageView("loading", 32, 32));
        versionLabel.setText("SimpleIDE {ide}; JavaFX {javafx}"
            .replace("{ide}", Main.instance.getVersionJson().getStr("ide"))
            .replace("{javafx}", Main.instance.getVersionJson().getStr("javafx")));
    }

    public void setStepTip(String text) {
        stepTipLabel.setText(text);
    }

    public interface Controller {
        void setStepTip(String text);

        void close();
    }
}
