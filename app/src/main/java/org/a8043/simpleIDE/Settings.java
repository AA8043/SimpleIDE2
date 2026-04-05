package org.a8043.simpleIDE;

import lombok.*;
import org.a8043.simpleIDE.util.config.ConfigClass;
import org.a8043.simpleIDE.util.config.Item;

import java.io.File;
import java.util.Locale;

@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Getter
@Setter
@ConfigClass(nodesName = """
    buildTool=settings.buildTool+settings.buildTool.description
    buildTool.maven=settings.buildTool.maven+settings.buildTool.maven.description
    buildTool.gradle=settings.buildTool.gradle+settings.buildTool.gradle.description
    appearance=settings.appearance+settings.appearance.description
    index=settings.index+settings.index.description
    """)
public class Settings {
    @Item(value = "buildTool.maven.defaultPath", title = "settings.buildTool.defaultPath",
        description = "settings.buildTool.defaultPath.description")
    private File defaultMavenPath;
    @Item(value = "buildTool.gradle.defaultPath", title = "settings.buildTool.defaultPath",
        description = "settings.buildTool.defaultPath.description")
    private File defaultGradlePath;
    @Item(value = "appearance.#", title = "settings.ide.languageName",
        description = "settings.ide.languageName.description")
    private String languageName;
    @Item(value = "index.#", title = "settings.index.threadCount",
        description = "settings.index.threadCount.description")
    private int indexThreadCount;

    public static Settings fromDefault() {
        return new Settings(new File("./maven"), new File("./gradle"),
            switch (Locale.getDefault().getLanguage()) {
                case "zh" -> "zh_cn";
                default -> "en_us";
            }, 64);
    }
}
