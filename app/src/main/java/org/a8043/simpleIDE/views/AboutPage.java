package org.a8043.simpleIDE.views;

import cn.hutool.core.io.resource.NoResourceException;
import cn.hutool.core.io.resource.ResourceUtil;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.web.WebView;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.a8043.simpleIDE.Main;
import org.a8043.simpleIDE.resource.ResourceManager;

import java.net.URL;

@Slf4j
public class AboutPage {
    public static final URL FXML_URL = ResourceUtil.getResource("AboutPage.fxml", AboutPage.class);
    @Getter
    @FXML
    private ScrollPane pane;
    @FXML
    private ImageView iconView;
    @FXML
    private Label versionLabel;
    @FXML
    private WebView dependencyLicenseView;

    @SneakyThrows
    public AboutPage() {
        FXMLLoader loader = new FXMLLoader(FXML_URL);
        loader.setControllerFactory(clazz -> this);
        loader.load();
    }

    @FXML
    private void initialize() {
        iconView.setImage(ResourceManager.getImage("icon"));
        versionLabel.setText(Main.instance.getVersionJson().getStr("ide"));
        try {
            dependencyLicenseView.getEngine().loadContent(ResourceUtil.readUtf8Str(
                "META-INF/dependency-license/index.html"));
        } catch (NoResourceException e) {
            log.error(e.getMessage(), e);
        }
    }
}
