package org.a8043.simpleIDE.fileEditor.javaFile;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.ArrayUtil;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.a8043.simpleIDE.fileEditor.CodeError;
import org.a8043.simpleIDE.fileEditor.CompleteItem;
import org.a8043.simpleIDE.fileEditor.ControllableFile;
import org.a8043.simpleIDE.fileEditor.FileEditor;
import org.a8043.simpleIDE.project.ProjectEditor;
import org.a8043.simpleIDE.project.index.IndexPoint;
import org.a8043.simpleIDE.util.FileUtil;
import org.a8043.simpleIDE.util.JavaUtil;
import org.fxmisc.richtext.model.StyleSpans;

import java.io.File;
import java.util.Collection;
import java.util.List;

public class JavaFile extends FileEditor {
    public static final String STYLE = ResourceUtil.readUtf8Str("styles/JavaHighlighter.css");
    private final JavaFileState state;
    private final JavaDiagnosticService diagnosticService;
    private final JavaHighlightService highlightService;
    private final JavaCompletionService completionService;
    private final JavaHoverService hoverService;

    public JavaFile(ControllableFile file, ProjectEditor editor) {
        super(file, editor);
        state = new JavaFileState(resolveIndexPoint(file.getFile(), editor));
        JavaTypeResolver typeResolver = new JavaTypeResolver(editor, state, this::getContent);
        diagnosticService = new JavaDiagnosticService(editor, state);
        highlightService = new JavaHighlightService(state);
        completionService = new JavaCompletionService(editor, state, this::getContent, typeResolver);
        hoverService = new JavaHoverService(editor, state, this::getContent, typeResolver);
        diagnosticService.analyze(getContent());
    }

    private static IndexPoint resolveIndexPoint(File file, ProjectEditor editor) {
        String relativePath = FileUtil.getRelativePath(
            FileUtil.findFileDirInFolders(editor.getProjectModel().getSrcDirList(), file.getName()), file);
        String[] path = relativePath.substring(0, relativePath.length() - ".java".length()).split("/");
        return JavaUtil.resolveModuleByPath(editor.getIndex(), path)
            .getPackage(ArrayUtil.sub(path, 0, path.length - 1))
            .getPoints().stream()
            .filter(point -> point.getName().equals(path[path.length - 1])).findFirst().orElse(null);
    }

    public ParseResult<CompilationUnit> getLatestParseResult() {
        return state.getLatestParseResult();
    }

    public CompilationUnit getLatestCompilationUnit() {
        return state.getLatestCompilationUnit();
    }

    public CompilationUnit getLatestSuccessfulCompilationUnit() {
        return state.getLatestSuccessfulCompilationUnit();
    }

    @Override
    public List<CompleteItem> computeCompletion(int caretPosition) {
        return completionService.computeCompletion(caretPosition);
    }

    public CompletionApplyResult applyCompletion(CompleteItem item, int caretPosition) {
        return completionService.applyCompletion(item, caretPosition);
    }

    @Override
    protected void onContentChanged() {
        diagnosticService.analyze(getContent());
    }

    @Override
    public String getHighlightingStyle() {
        return STYLE;
    }

    @Override
    protected StyleSpans<Collection<String>> doComputeHighlighting() {
        return highlightService.computeHighlighting(getContent());
    }

    @Override
    public String computeHoverTip(int position) {
        return hoverService.computeHoverTip(position);
    }

    @Override
    public List<CodeError> getProblemList() {
        return List.copyOf(state.getProblemList());
    }

    @AllArgsConstructor
    @Getter
    public static class CompletionApplyResult {
        private final String content;
        private final int caretPosition;
    }
}
