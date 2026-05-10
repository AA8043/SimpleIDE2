package org.a8043.simpleIDE.plugin;

import cn.hutool.core.comparator.VersionComparator;
import cn.hutool.core.io.IoUtil;
import cn.hutool.json.JSONObject;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.a8043.simpleIDE.Main;
import org.a8043.simpleIDE.pluginApi.PluginMain;

import java.net.URLClassLoader;
import java.util.Objects;

@Slf4j
public class Plugin {
    private final URLClassLoader classLoader;
    private final PluginInfo info;
    private final PluginMain main;
    private boolean isEnabled;

    @SneakyThrows
    public Plugin(URLClassLoader classLoader) {
        this.classLoader = classLoader;
        this.info = new JSONObject(IoUtil.readUtf8(
            Objects.requireNonNull(classLoader.getResource("plugin.json")).openStream())).toBean(PluginInfo.class);
        this.main = classLoader.loadClass(info.getMainClass()).asSubclass(PluginMain.class).getConstructor().newInstance();
    }

    public void enable() {
        if (!isIDEVersionInRange(info.getMinIDEVersion(), info.getMaxIDEVersion())) {
            log.warn("插件 {} 要求的IDE版本 {}~{} 与当前IDE版本 {} 不兼容",
                info.getName(), info.getMinIDEVersion(), info.getMaxIDEVersion(),
                Main.instance.getVersionJson().getStr("ide"));
            return;
        }
        main.onEnable();
        isEnabled = true;
    }

    public void disable() {
        if (isEnabled) {
            main.onDisable();
            isEnabled = false;
        }
    }

    @SneakyThrows
    public void close() {
        disable();
        classLoader.close();
    }

    private static boolean isIDEVersionInRange(String min, String max) {
        String version = Main.instance.getVersionJson().getStr("ide");
        if (VersionComparator.INSTANCE.compare(version, min) < 0) {
            return false;
        }
        if (max.equals("-")) {
            return true;
        }
        return VersionComparator.INSTANCE.compare(version, max) <= 0;
    }
}
