package org.a8043.simpleIDE.fileEditor;

import cn.hutool.core.io.FileUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.io.File;

@AllArgsConstructor
@Getter
public class ControllableFile {
    private final File file;
    @Setter
    private String content;
    private final boolean readOnly;
    private final String name;

    public ControllableFile(File file, String content, boolean readOnly) {
        this(file, content, readOnly, file.getName());
    }

    public ControllableFile(String name, String content) {
        this(null, content, true, name);
    }

    public String read() {
        if (file != null && file.exists()) {
            setContent(FileUtil.readUtf8String(file));
        }
        return content;
    }

    public void write() {
        if (file != null && !readOnly) {
            FileUtil.writeUtf8String(content, file);
        }
    }
}
