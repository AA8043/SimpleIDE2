package org.a8043.simpleIDE.fileEditor;

import cn.hutool.core.io.FileUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.io.File;

/**
 * 可控文件
 */
@AllArgsConstructor
@Getter
public class ControllableFile {
    /**
     * 文件<br>
     * 可以为null
     */
    private final File file;
    /**
     * 文件内容<br>
     * content可能与文件内容不一致, 使用{@link #read()}方法获取最新内容
     */
    @Setter
    private String content;
    /**
     * 只读
     */
    private final boolean readOnly;
    /**
     * 名称
     */
    private final String name;

    /**
     * 创建ControllableFile
     * @param file 文件
     * @param content 内容
     * @param readOnly 只读
     */
    public ControllableFile(File file, String content, boolean readOnly) {
        this(file, content, readOnly, file.getName());
    }

    /**
     * 创建只读的ControllableFile
     * @param name 名称
     * @param content 内容
     */
    public ControllableFile(String name, String content) {
        this(null, content, true, name);
    }

    /**
     * 读取文件内容到content
     * @return 内容
     */
    public String read() {
        if (file != null && file.exists()) {
            setContent(FileUtil.readUtf8String(file));
        }
        return content;
    }

    /**
     * 将content写入文件
     */
    public void write() {
        if (file != null && !readOnly) {
            FileUtil.writeUtf8String(content, file);
        }
    }
}
