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
        state = new JavaFileState(resolveIndexPoint(file, editor));
        JavaTypeResolver typeResolver = new JavaTypeResolver(editor, state, this::getContent);
        diagnosticService = new JavaDiagnosticService(editor, state);
        highlightService = new JavaHighlightService(state);
        completionService = new JavaCompletionService(editor, state, this::getContent, typeResolver);
        hoverService = new JavaHoverService(editor, state, this::getContent, () -> getFile().getFile(), typeResolver);
        diagnosticService.analyze(getContent());
    }

    private static IndexPoint resolveIndexPoint(ControllableFile file, ProjectEditor editor) {
        File sourceFile = file.getFile();
        IndexPoint resolved = editor.resolveIndexPointByFile(sourceFile);
        if (resolved != null) {
            return resolved;
        }

        ParseResult<CompilationUnit> parseResult = editor.getJavaParser().parse(file.getContent());
        CompilationUnit compilationUnit = parseResult.getResult().orElse(null);
        if (compilationUnit == null || compilationUnit.getTypes().isEmpty()) {
            return null;
        }

        String typeName = compilationUnit.getPrimaryTypeName().orElse(compilationUnit.getType(0).getNameAsString());
        String[] packagePath = compilationUnit.getPackageDeclaration()
            .map(declaration -> declaration.getNameAsString().split("\\."))
            .orElse(new String[0]);
        String[] path = ArrayUtil.addAll(packagePath, new String[]{typeName});

        String moduleCacheName = resolveSourceCacheModuleName(sourceFile, editor);
        if (moduleCacheName != null) {
            org.a8043.simpleIDE.project.index.Module sourceModule =
                editor.getIndex().getModuleByCacheName(moduleCacheName);
            if (sourceModule != null) {
                IndexPoint point = sourceModule.getPoint(path);
                if (point != null) {
                    return point;
                }
            }
        }

        org.a8043.simpleIDE.project.index.Module module = JavaUtil.resolveModuleByPath(editor.getIndex(), path);
        return module != null ? module.getPoint(path) : null;
    }

    private static String resolveSourceCacheModuleName(File file, ProjectEditor editor) {
        if (file == null) {
            return null;
        }
        try {
            File sourceCacheDir = new File(editor.getConfigDir(), "source-cache").getCanonicalFile();
            File canonicalFile = file.getCanonicalFile();
            if (!canonicalFile.toPath().startsWith(sourceCacheDir.toPath())) {
                return null;
            }
            String relativePath = sourceCacheDir.toPath().relativize(canonicalFile.toPath()).toString()
                .replace("\\", "/");
            int separatorIndex = relativePath.indexOf("/");
            return separatorIndex >= 0 ? relativePath.substring(0, separatorIndex) : null;
        } catch (Exception e) {
            return null;
        }
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

    public SourceLocation resolveSourceLocation(int position) {
        return hoverService.resolveSourceLocation(position);
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

    @AllArgsConstructor
    @Getter
    public static class SourceLocation {
        private final File file;
        private final int position;
    }
}
