package org.a8043.simpleIDE.fileEditor.javaFile;

import com.github.javaparser.ParseResult;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.*;
import org.a8043.simpleIDE.fileEditor.CodeError;
import org.a8043.simpleIDE.project.ProjectEditor;
import org.a8043.simpleIDE.resource.ResourceManager;
import org.a8043.simpleIDE.util.JavaUtil;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

public class JavaDiagnosticService {
    private final ProjectEditor editor;
    private final JavaFileState state;
    private final JavaSymbolChecker symbolChecker;

    public JavaDiagnosticService(ProjectEditor editor, JavaFileState state, JavaTypeResolver typeResolver,
                                 Supplier<String> contentSupplier) {
        this.editor = editor;
        this.state = state;
        this.symbolChecker = new JavaSymbolChecker(editor, state, typeResolver, contentSupplier);
    }

    public boolean analyze(String content) {
        ParseResult<CompilationUnit> parseResult = editor.getJavaParser().parse(content);
        state.recordParseResult(parseResult, content);
        state.getProblemList().clear();
        state.getProblemHighlightList().clear();
        collectSyntaxProblems(parseResult, content);

        CompilationUnit unit = parseResult.getResult().orElse(null);
        if (parseResult.isSuccessful()) {
            state.setPendingHighlightEdits(null);
            state.setPendingHighlightContent(null);
        }
        if (unit == null) {
            return false;
        }

        Function<List<Modifier>, Void> duplicateModifiersErrorAdder = list -> {
            Set<Modifier.Keyword> modifierSet = new HashSet<>();
            list.forEach(modifier -> {
                if (modifierSet.contains(modifier.getKeyword())) {
                    Range range = modifier.getRange().orElse(null);
                    if (range != null) {
                        int start = JavaUtil.getPosition(range.begin, content);
                        int end = JavaUtil.getPosition(range.end, content) + 1;
                        state.getProblemList().add(new CodeError(start, end,
                            ResourceManager.getText("semanticError.duplicateModifiers", modifier.getKeyword()),
                            CodeError.Type.SEMANTIC_ERROR));
                        state.getProblemHighlightList().add(new JavaSyntaxHighlight(range, "problem"));
                    }
                } else {
                    modifierSet.add(modifier.getKeyword());
                }
            });
            return null;
        };
        unit.findAll(MethodDeclaration.class).forEach(method ->
            duplicateModifiersErrorAdder.apply(method.getModifiers()));
        unit.findAll(ClassOrInterfaceDeclaration.class).forEach(type ->
            duplicateModifiersErrorAdder.apply(type.getModifiers()));
        unit.findAll(FieldDeclaration.class).forEach(field ->
            duplicateModifiersErrorAdder.apply(field.getModifiers()));

        unit.findAll(IfStmt.class).forEach(ifStmt -> {
            addEmptyBodyWarning(ifStmt.getThenStmt(), "if", content);
            ifStmt.getElseStmt().ifPresent(elseStmt -> {
                if (!elseStmt.isIfStmt()) {
                    addEmptyBodyWarning(elseStmt, "else", content);
                }
            });
        });
        unit.findAll(ForStmt.class).forEach(forStmt ->
            addEmptyBodyWarning(forStmt.getBody(), "for", content));
        unit.findAll(ForEachStmt.class).forEach(forEachStmt ->
            addEmptyBodyWarning(forEachStmt.getBody(), "for-each", content));
        unit.findAll(WhileStmt.class).forEach(whileStmt ->
            addEmptyBodyWarning(whileStmt.getBody(), "while", content));
        unit.findAll(DoStmt.class).forEach(doStmt ->
            addEmptyBodyWarning(doStmt.getBody(), "do-while", content));

        // 语义检查: 仅在解析完全成功时进行, 避免基于残缺语法树误报
        if (parseResult.isSuccessful()) {
            symbolChecker.check(unit, content);
        }

        return true;
    }

    private void addEmptyBodyWarning(Statement statement, String keyword, String content) {
        if (!isEmptyStatementBody(statement)) {
            return;
        }
        Range range = statement.getRange().orElse(null);
        if (range == null) {
            return;
        }
        state.getProblemList().add(new CodeError(JavaUtil.getPosition(range.begin, content),
            JavaUtil.getPosition(range.end, content) + 1,
            ResourceManager.getText("warning.emptyStatementBody", keyword), CodeError.Type.WARNING));
        state.getProblemHighlightList().add(new JavaSyntaxHighlight(range, "warning"));
    }

    private boolean isEmptyStatementBody(Statement statement) {
        return statement instanceof EmptyStmt ||
               statement instanceof BlockStmt blockStmt && blockStmt.getStatements().isEmpty();
    }

    private void collectSyntaxProblems(ParseResult<CompilationUnit> parseResult, String content) {
        parseResult.getProblems().forEach(problem -> problem.getLocation().ifPresent(problemLocation -> {
            int start = JavaUtil.getPosition(problemLocation.toRange().orElseThrow().begin, content);
            int end = JavaUtil.getPosition(problemLocation.toRange().orElseThrow().end, content) + 1;
            state.getProblemList().add(new CodeError(start, end, problem.getMessage(), CodeError.Type.SYNTAX_ERROR));
        }));
    }
}
