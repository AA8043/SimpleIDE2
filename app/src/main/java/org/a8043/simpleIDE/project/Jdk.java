package org.a8043.simpleIDE.project;

import cn.hutool.core.util.RuntimeUtil;
import cn.hutool.json.JSONObject;
import com.github.javaparser.ParserConfiguration;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.a8043.simpleIDE.Main;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Getter
public class Jdk {
    public static final List<Jdk> JDK_LIST = new ArrayList<>();

    public static void addJdk(Jdk jdk) {
        JDK_LIST.add(jdk);
        Main.instance.getRecordJson().getJSONArray("jdks").add(new JSONObject()
            .set("path", jdk.getPath().getAbsolutePath()).set("version", jdk.getVersion()));
    }

    public static Jdk getJdk(File path) {
        Jdk result = JDK_LIST.stream().filter(jdk -> jdk.getPath().equals(path)).findFirst().orElse(null);
        if (result == null) {
            addJdk(result = new Jdk(path));
        }
        return result;
    }

    private final File path;
    private final File javaFile;
    private final String version;
    private final ParserConfiguration.LanguageLevel languageLevel;
    private final File srcFile;

    public Jdk(File path, String version) {
        this.path = path;
        javaFile = new File(path, "bin/" + (System.getProperty("os.name").toLowerCase().contains("win") ?
            "java.exe" : "java"));
        this.version = version;

        languageLevel = switch (version) {
            case "9" -> ParserConfiguration.LanguageLevel.JAVA_9;
            case "10" -> ParserConfiguration.LanguageLevel.JAVA_10;
            case "11" -> ParserConfiguration.LanguageLevel.JAVA_11;
            case "12" -> ParserConfiguration.LanguageLevel.JAVA_12;
            case "13" -> ParserConfiguration.LanguageLevel.JAVA_13;
            case "14" -> ParserConfiguration.LanguageLevel.JAVA_14;
            case "15" -> ParserConfiguration.LanguageLevel.JAVA_15;
            case "16" -> ParserConfiguration.LanguageLevel.JAVA_16;
            case "17" -> ParserConfiguration.LanguageLevel.JAVA_17;
            case "18" -> ParserConfiguration.LanguageLevel.JAVA_18;
            case "19" -> ParserConfiguration.LanguageLevel.JAVA_19;
            case "20" -> ParserConfiguration.LanguageLevel.JAVA_20;
            case "21" -> ParserConfiguration.LanguageLevel.JAVA_21;
            case "unknown" -> ParserConfiguration.LanguageLevel.BLEEDING_EDGE;
            default -> throw new RuntimeException("不支持的JDK版本: " + version);
        };

        srcFile = new File(path, "lib/src.zip");
    }

    public Jdk(File path) {
        this(path, getVersion(path));
    }

    private static String getVersion(File path) {
        try {
            AtomicReference<String> versionLineAtomic = new AtomicReference<>();
            RuntimeUtil.execForLines(path.getAbsolutePath(), "-version").forEach(line -> {
                if (line.startsWith("java version")) {
                    versionLineAtomic.set(line);
                }
            });
            String versionLine = versionLineAtomic.get();
            int firstQuote = versionLine.indexOf('"');
            int secondQuote = versionLine.indexOf('"', firstQuote + 1);
            return versionLine.substring(firstQuote + 1, secondQuote);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return "unknown";
        }
    }
}
