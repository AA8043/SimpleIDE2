package org.a8043.simpleIDE;

import cn.hutool.core.annotation.Alias;
import lombok.*;

import java.io.File;
import java.util.Locale;

@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Getter
@Setter
public class Settings {
    @Alias("buildTool.maven.defaultPath")
    private File defaultMavenPath;
    @Alias("buildTool.gradle.defaultPath")
    private File defaultGradlePath;
    @Alias("appearance.languageName")
    private String languageName;
    @Alias("index.threadCount")
    private int indexThreadCount;
    @Alias("editor.autoSaveInterval")
    private int autoSaveInterval;

    public static Settings fromDefault() {
        return new Settings(new File("./maven"), new File("./gradle"),
            switch (Locale.getDefault().getLanguage()) {
                case "zh" -> "zh_cn";
                default -> "en_us";
            }, 64, 3000);
    }
}
