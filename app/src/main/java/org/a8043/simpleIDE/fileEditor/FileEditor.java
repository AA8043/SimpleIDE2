package org.a8043.simpleIDE.fileEditor;

import lombok.Getter;
import lombok.Value;
import org.a8043.simpleIDE.project.ProjectEditor;
import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

@Getter
public abstract class FileEditor {
    private final ControllableFile file;
    private final ProjectEditor editor;

    public FileEditor(ControllableFile file, ProjectEditor editor) {
        this.file = file;
        this.editor = editor;
    }

    public final void setContent(String content) {
        file.setContent(content);
        onContentChanged();
    }

    protected abstract void onContentChanged();

    public final String getContent() {
        return file.getContent();
    }

    public abstract String getHighlightingStyle();

    public abstract List<CompleteItem> computeCompletion(int caretPosition);

    public final StyleSpans<Collection<String>> computeHighlighting() {
        StyleSpans<Collection<String>> originalSpans = doComputeHighlighting();
        return fillUnstyledParts(originalSpans, file.getContent(), List.of("default"));
    }

    protected abstract StyleSpans<Collection<String>> doComputeHighlighting();

    private static StyleSpans<Collection<String>> fillUnstyledParts(StyleSpans<Collection<String>> originalSpans,
                                                                    String text, Collection<String> defaultStyle) {
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        int lastPosition = 0;
        for (StyleSpan<Collection<String>> span : originalSpans) {
            int spanLength = span.getLength();
            LinkedHashSet<String> styleSet = new LinkedHashSet<>(defaultStyle);
            styleSet.addAll(span.getStyle());
            spansBuilder.add(new ArrayList<>(styleSet), spanLength);
            lastPosition += spanLength;
        }
        if (lastPosition < text.length()) {
            spansBuilder.add(defaultStyle, text.length() - lastPosition);
        }
        return spansBuilder.create();
    }

    public abstract String computeHoverTip(int position);

    public abstract JumpTarget jump(int position);

    /**
     * 查找光标处符号在项目中的所有引用<br>
     * 默认不支持, 返回null; 支持的编辑器(如Java)应重写此方法
     *
     * @param position 光标的0-based位置
     * @return 引用列表; 若该位置没有可查找引用的符号则返回空列表; 若编辑器不支持则返回null
     */
    public List<Usage> findUsages(int position) {
        return null;
    }

    public abstract List<CodeError> getProblemList();

    @Value
    public static class JumpTarget {
        ControllableFile file;
        int position;
    }

    /**
     * 一处符号引用
     */
    @Value
    public static class Usage {
        /**
         * 引用所在文件
         */
        ControllableFile file;
        /**
         * 引用在文件中的0-based位置
         */
        int position;
        /**
         * 引用所在行号(1-based)
         */
        int line;
        /**
         * 引用所在上下文描述(如所属类型/方法的限定名)
         */
        String context;
        /**
         * 引用所在源码行文本(已去除首尾空白), 用于预览
         */
        String lineText;
    }
}
