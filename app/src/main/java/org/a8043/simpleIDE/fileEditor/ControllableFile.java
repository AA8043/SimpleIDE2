package org.a8043.simpleIDE.fileEditor;

import cn.hutool.core.io.FileUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.io.File;

@AllArgsConstructor
@Getter
@Setter
public class ControllableFile {
    private File file;
    private String content;

    public String read() {
        if (file != null) {
            setContent(FileUtil.readUtf8String(file));
        }
        return content;
    }

    public final void write() {
        if (file != null) {
            FileUtil.writeUtf8String(content, file);
        }
    }
}
