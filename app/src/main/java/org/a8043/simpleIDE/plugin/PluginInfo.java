package org.a8043.simpleIDE.plugin;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PluginInfo {
    private String name;
    private String version;
    private String description;
    private String author;
    private String mainClass;

    private String minIDEVersion;
    private String maxIDEVersion;
}
