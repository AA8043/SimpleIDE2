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
import org.a8043.simpleIDE.project.ProjectModule;
import org.a8043.simpleIDE.project.index.IndexPoint;
import org.a8043.simpleIDE.project.index.Module;
import org.a8043.simpleIDE.util.JavaUtil;
import org.fxmisc.richtext.model.StyleSpans;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

public class JavaFile extends FileEditor {
    public static final String STYLE = ResourceUtil.readUtf8Str("styles/JavaHighlighter.css");
    private final JavaFileState state;
    private final JavaDiagnosticService diagnosticService;
    private final JavaHighlightService highlightService;
    private final JavaCompletionService completionService;
    private final JavaHoverService hoverService;
    private final JavaUsageService usageService;

    public JavaFile(ControllableFile file, ProjectEditor editor) {
        super(file, editor);
        state = new JavaFileState(resolveIndexPoint(file, editor));
        JavaTypeResolver typeResolver = new JavaTypeResolver(editor, state, this::getContent);
        diagnosticService = new JavaDiagnosticService(editor, state, typeResolver, this::getContent);
        highlightService = new JavaHighlightService(state);
        completionService = new JavaCompletionService(editor, state, this::getContent, typeResolver);
        hoverService = new JavaHoverService(editor, state, this::getContent, this::getFile, typeResolver);
        usageService = new JavaUsageService(editor, state, this::getContent, this::getFile, typeResolver);
        if (diagnosticService.analyze(getContent())) {
            synchronizeIndexPoint();
        }
    }

    private static IndexPoint resolveIndexPoint(ControllableFile file, ProjectEditor editor) {
        File sourceFile = file.getFile();
        IndexPoint resolved = editor.getIndex().resolveIndexPointByFile(sourceFile);
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

        Module module = JavaUtil.resolveModuleByPath(editor.getIndex(), path);
        return module != null ? module.getPoint(path) : null;
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
        if (diagnosticService.analyze(getContent())) {
            synchronizeIndexPoint();
        }
    }

    private void synchronizeIndexPoint() {
        CompilationUnit compilationUnit = state.getLatestCompilationUnit();
        if (compilationUnit == null) {
            return;
        }

        IndexTarget target = resolveIndexTarget(getFile().getFile(), getEditor(), compilationUnit);
        if (target == null || target.module() == null) {
            return;
        }

        IndexPoint currentPoint = state.getIndexPoint();
        if (currentPoint != null && !isSameTarget(currentPoint, target)) {
            getEditor().getIndex().getIndexList().remove(currentPoint);
        }
        state.setIndexPoint(getEditor().getIndex().index(target.module(), target.path(), getContent()));
    }

    private static IndexTarget resolveIndexTarget(File sourceFile, ProjectEditor editor, CompilationUnit compilationUnit) {
        if (sourceFile == null || compilationUnit.getTypes().isEmpty()) {
            return null;
        }

        String typeName = compilationUnit.getPrimaryTypeName().orElse(compilationUnit.getType(0).getNameAsString());
        String[] packagePath = compilationUnit.getPackageDeclaration()
            .map(declaration -> declaration.getNameAsString().split("\\."))
            .orElse(new String[0]);
        String[] path = ArrayUtil.addAll(packagePath, new String[]{typeName});

        IndexPoint existingPoint = editor.getIndex().resolveIndexPointByFile(sourceFile);
        Module module = existingPoint != null ? existingPoint.getPkg().getModule() : null;
        if (module != null) {
            return new IndexTarget(module, path);
        }

        module = resolveProjectModule(sourceFile, editor);
        if (module != null) {
            return new IndexTarget(module, path);
        }

        module = JavaUtil.resolveModuleByPath(editor.getIndex(), path);
        return module != null ? new IndexTarget(module, path) : null;
    }

    private static Module resolveProjectModule(File sourceFile, ProjectEditor editor) {
        try {
            File canonicalSourceFile = sourceFile.getCanonicalFile();
            for (ProjectModule projectModule : editor.getProjectModel().getModuleList()) {
                if (projectModule.getLocation() != ProjectModule.Location.PROJECT) {
                    continue;
                }
                for (File srcDir : projectModule.getSrcDirList()) {
                    File canonicalSrcDir = srcDir.getCanonicalFile();
                    if (canonicalSourceFile.toPath().startsWith(canonicalSrcDir.toPath())) {
                        return editor.getIndex().getModule(projectModule.getName());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean isSameTarget(IndexPoint indexPoint, IndexTarget target) {
        return indexPoint.getPkg() != null &&
               indexPoint.getPkg().getModule() == target.module() &&
               Objects.deepEquals(indexPoint.getPath(), target.path());
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
    public JumpTarget jump(int position) {
        SourceLocation sourceLocation = resolveSourceLocation(position);
        if (sourceLocation == null) {
            return null;
        }
        return new JumpTarget(sourceLocation.getFile(), sourceLocation.getPosition());
    }

    public SourceLocation resolveSourceLocation(int position) {
        return hoverService.resolveSourceLocation(position);
    }

    @Override
    public List<Usage> findUsages(int position) {
        return usageService.findUsages(position);
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
        private final ControllableFile file;
        private final IndexPoint point;
        private final int position;
    }

    private record IndexTarget(Module module, String[] path) {
    }
}
