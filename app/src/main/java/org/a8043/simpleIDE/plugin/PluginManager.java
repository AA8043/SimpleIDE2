package org.a8043.simpleIDE.plugin;

import lombok.SneakyThrows;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class PluginManager {
    public static final List<Plugin> PLUGIN_LIST = new ArrayList<>();

    @SneakyThrows
    public static void loadAll() {
        File pluginsDir = new File("./plugins");
        if (!pluginsDir.exists()) {
            if (!pluginsDir.mkdirs()) {
                throw new RuntimeException();
            }
        }
        for (File file : Objects.requireNonNull(pluginsDir.listFiles())) {
            PLUGIN_LIST.add(new Plugin(new URLClassLoader(new URL[]{file.toURI().toURL()},
                PluginManager.class.getClassLoader())));
        }
    }

    public static void enableAll() {
        PLUGIN_LIST.forEach(Plugin::enable);
    }

    public static void closeAll() {
        PLUGIN_LIST.forEach(Plugin::close);
    }
}
