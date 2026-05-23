package org.a8043.simpleIDE.fileEditor.javaFile;

import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.util.StrUtil;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParseStart;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.a8043.simpleIDE.fileEditor.CompleteItem;
import org.a8043.simpleIDE.project.ProjectEditor;
import org.a8043.simpleIDE.project.index.IndexPoint;
import org.a8043.simpleIDE.util.JavaUtil;
import org.a8043.simpleIDE.util.SearchUtil;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class JavaCompletionService {
    private final ProjectEditor editor;
    private final JavaFileState state;
    private final Supplier<String> contentSupplier;
    private final JavaTypeResolver typeResolver;

    public JavaCompletionService(ProjectEditor editor, JavaFileState state, Supplier<String> contentSupplier,
                                 JavaTypeResolver typeResolver) {
        this.editor = editor;
        this.state = state;
        this.contentSupplier = contentSupplier;
        this.typeResolver = typeResolver;
    }

    public List<CompleteItem> computeCompletion(int caretPosition) {
        CompilationUnit latestCompilationUnit = state.getLatestCompilationUnit();
        String content = getContent();
        if (latestCompilationUnit != null && JavaUtil.isPositionInString(latestCompilationUnit, caretPosition, content)) {
            return new ArrayList<>();
        }

        CompletionContext context = createCompletionContext(caretPosition, content);
        TextCompletionContext textContext = createTextCompletionContext(caretPosition, content);
        if (context.isMemberCompletion()) {
            List<CompleteItem> memberItems = computeMemberCompletion(textContext, context);
            if (!memberItems.isEmpty()) {
                return memberItems;
            }
        }
        return computeGlobalCompletion(textContext, context);
    }

    public JavaFile.CompletionApplyResult applyCompletion(CompleteItem item, int caretPosition) {
        String content = getContent();
        int start = item.getStart();
        String replacedContent = content.substring(0, start) + item.getText() + content.substring(caretPosition);
        List<JavaTextEditSegment> editList = new ArrayList<>();
        JavaTextEditSegment completionEdit = new JavaTextEditSegment(start, caretPosition,
            start, start + item.getText().length());
        editList.add(completionEdit);

        String importQualifiedName = item.getImportQualifiedName();
        String newContent = replacedContent;
        if (importQualifiedName != null && !importQualifiedName.isBlank()) {
            CompilationUnit compilationUnit = state.getLatestSuccessfulCompilationUnit();
            if (compilationUnit != null && shouldAddImport(compilationUnit, importQualifiedName)) {
                ImportInsertion importInsertion = insertImport(replacedContent, compilationUnit, importQualifiedName);
                newContent = importInsertion.getContent();
                editList.add(remapIntermediateEditToOriginal(importInsertion.getEditSegment(), completionEdit));
            }
        }

        editList.sort(Comparator.comparingInt(JavaTextEditSegment::getOldStart)
            .thenComparingInt(JavaTextEditSegment::getOldEnd));
        state.setPendingHighlightEdits(List.copyOf(editList));
        state.setPendingHighlightContent(newContent);
        return new JavaFile.CompletionApplyResult(newContent, start + item.getText().length());
    }

    private List<CompleteItem> computeMemberCompletion(TextCompletionContext textContext, CompletionContext context) {
        ResolvedCompletionScope scope = resolveCompletionScope(textContext, context);
        if (scope == null || scope.getType() == null) {
            return List.of();
        }
        return createMemberCompletionItems(scope.getType(), context, scope.isStaticOnly());
    }

    private List<CompleteItem> computeGlobalCompletion(TextCompletionContext textContext, CompletionContext context) {
        List<CompleteItem> itemList = new ArrayList<>(createVisibleTextCompletionItems(textContext, context));
        List<IndexPoint> searchResult = SearchUtil.search(editor.getIndex().getIndexList(),
            IndexPoint::getName, context.getKeyword());
        Set<String> added = new HashSet<>();
        itemList.forEach(item -> added.add(item.getText()));
        ListUtil.sub(searchResult, 0, 100).forEach(indexPoint -> {
            if (added.add(indexPoint.getName())) {
                itemList.add(new CompleteItem(indexPoint, context.getCaretPosition(), context.getReplaceStart()));
            }
        });
        return itemList;
    }

    private ResolvedCompletionScope resolveCompletionScope(TextCompletionContext textContext, CompletionContext context) {
        CompilationUnit compilationUnit = state.getLatestSuccessfulCompilationUnit();
        String scopeText = extractCompletionScopeText(context.getScopeStart());

        if ("this".equals(scopeText)) {
            return new ResolvedCompletionScope(state.getIndexPoint(), false);
        }

        TextMember typeMember = textContext.findTypeMember(scopeText);
        if (typeMember != null && typeMember.getTypeName() != null) {
            IndexPoint scopeType = typeResolver.resolveType(typeMember.getTypeName(), compilationUnit);
            if (scopeType != null) {
                return new ResolvedCompletionScope(scopeType, false);
            }
        }

        Expression scopeExpression = compilationUnit != null ?
            findCompletionScopeExpression(compilationUnit, context.getScopeStart()) : null;
        if (scopeExpression == null) {
            scopeExpression = parseCompletionScopeExpression(context.getScopeStart());
        }
        if (scopeExpression != null) {
            ResolvedCompletionScope resolvedExpressionScope = resolveExpressionScope(scopeExpression, compilationUnit);
            if (resolvedExpressionScope != null) {
                return resolvedExpressionScope;
            }
        }

        IndexPoint type = typeResolver.resolveType(scopeText, compilationUnit);
        if (type != null) {
            return new ResolvedCompletionScope(type, true);
        }
        return null;
    }

    private ResolvedCompletionScope resolveExpressionScope(Expression expression, CompilationUnit compilationUnit) {
        return switch (expression) {
            case ThisExpr ignored -> new ResolvedCompletionScope(state.getIndexPoint(), false);
            case SuperExpr ignored ->
                new ResolvedCompletionScope(state.getIndexPoint() != null ? state.getIndexPoint().getParent() : null, false);
            case NameExpr nameExpr -> {
                TextMember visibleMember = compilationUnit == null ? null : findVisibleMember(nameExpr);
                if (visibleMember != null && visibleMember.getTypeName() != null) {
                    IndexPoint type = typeResolver.resolveType(visibleMember.getTypeName(), compilationUnit);
                    yield type != null ? new ResolvedCompletionScope(type, false) : null;
                }
                IndexPoint type = typeResolver.resolveType(nameExpr.getNameAsString(), compilationUnit);
                yield type != null ? new ResolvedCompletionScope(type, true) : null;
            }
            case FieldAccessExpr fieldAccessExpr -> {
                IndexPoint type = typeResolver.resolveExpressionType(fieldAccessExpr, compilationUnit);
                yield type != null ? new ResolvedCompletionScope(type, false) : null;
            }
            case MethodCallExpr methodCallExpr -> {
                IndexPoint type = typeResolver.resolveExpressionType(methodCallExpr, compilationUnit);
                yield type != null ? new ResolvedCompletionScope(type, false) : null;
            }
            case EnclosedExpr enclosedExpr -> resolveExpressionScope(enclosedExpr.getInner(), compilationUnit);
            default -> {
                IndexPoint type = typeResolver.resolveExpressionType(expression, compilationUnit);
                yield type != null ? new ResolvedCompletionScope(type, false) : null;
            }
        };
    }

    private TextMember findVisibleMember(NameExpr nameExpr) {
        String name = nameExpr.getNameAsString();
        MethodDeclaration method = nameExpr.findAncestor(MethodDeclaration.class).orElse(null);
        if (method != null) {
            TextMember parameter = method.getParameterByName(name)
                .map(parameter1 -> new TextMember(parameter1.getNameAsString(),
                    JavaUtil.normalizeTypeName(parameter1.getType().asString()), "variable"))
                .orElse(null);
            if (parameter != null) {
                return parameter;
            }

            VariableDeclarator variable = method.findAll(VariableDeclarator.class).stream()
                .filter(declarator -> declarator.getNameAsString().equals(name))
                .filter(declarator -> {
                    int variablePosition = declarator.getName().getRange()
                        .map(range -> JavaUtil.getPosition(range.begin, getContent()))
                        .orElse(Integer.MAX_VALUE);
                    int currentPosition = nameExpr.getName().getRange()
                        .map(range -> JavaUtil.getPosition(range.begin, getContent()))
                        .orElse(Integer.MIN_VALUE);
                    return variablePosition <= currentPosition;
                })
                .max(Comparator.comparingInt(declarator -> declarator.getName().getRange()
                    .map(range -> JavaUtil.getPosition(range.begin, getContent())).orElse(-1)))
                .orElse(null);
            if (variable != null) {
                return new TextMember(variable.getNameAsString(),
                    JavaUtil.normalizeTypeName(variable.getType().asString()), "variable");
            }
        }

        if (state.getIndexPoint() != null) {
            var field = state.getIndexPoint().getField(name);
            if (field != null && field.getType() != null) {
                return new TextMember(field.getName(), field.getType().getName(), "field");
            }
        }
        return null;
    }

    private Expression findCompletionScopeExpression(CompilationUnit compilationUnit, int dotPosition) {
        AtomicReference<Expression> result = new AtomicReference<>();
        compilationUnit.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(FieldAccessExpr n, Void arg) {
                n.getName().getRange().ifPresent(range -> {
                    int start = JavaUtil.getPosition(range.begin, getContent());
                    if (start - 1 == dotPosition) {
                        result.set(n.getScope());
                    }
                });
                if (result.get() == null) {
                    super.visit(n, arg);
                }
            }

            @Override
            public void visit(MethodCallExpr n, Void arg) {
                n.getName().getRange().ifPresent(range -> {
                    int start = JavaUtil.getPosition(range.begin, getContent());
                    if (start - 1 == dotPosition) {
                        result.set(n.getScope().orElse(null));
                    }
                });
                if (result.get() == null) {
                    super.visit(n, arg);
                }
            }
        }, null);
        return result.get();
    }

    private Expression parseCompletionScopeExpression(int dotPosition) {
        String scopeText = extractCompletionScopeText(dotPosition);
        if (scopeText.isBlank()) {
            return null;
        }
        ParseResult<Expression> parseResult = new JavaParser().parse(ParseStart.EXPRESSION,
            Providers.provider(scopeText));
        return parseResult.getResult().orElse(null);
    }

    private String extractCompletionScopeText(int dotPosition) {
        String content = getContent();
        int start = dotPosition - 1;
        int parenthesesDepth = 0;
        int bracketDepth = 0;
        int braceDepth = 0;
        while (start >= 0) {
            char ch = content.charAt(start);
            String str = content.substring(start + 1, dotPosition).trim();
            switch (ch) {
                case ')' -> parenthesesDepth++;
                case ']' -> bracketDepth++;
                case '}' -> braceDepth++;
                case '(' -> {
                    if (parenthesesDepth == 0) {
                        return str;
                    }
                    parenthesesDepth--;
                }
                case '[' -> {
                    if (bracketDepth == 0) {
                        return str;
                    }
                    bracketDepth--;
                }
                case '{' -> {
                    if (braceDepth == 0) {
                        return str;
                    }
                    braceDepth--;
                }
                default -> {
                    if (parenthesesDepth == 0 && bracketDepth == 0 && braceDepth == 0 &&
                        isCompletionScopeBoundary(ch)) {
                        return str;
                    }
                }
            }
            start--;
        }
        return content.substring(0, dotPosition).trim();
    }

    private TextCompletionContext createTextCompletionContext(int caretPosition, String content) {
        ClassTextMembers classMembers = collectCurrentClassMembers(content);
        MethodTextMembers methodMembers = collectCurrentMethodMembers(content, caretPosition);
        return new TextCompletionContext(classMembers, methodMembers);
    }

    private CompletionContext createCompletionContext(int caretPosition, String content) {
        int keywordStart = caretPosition;
        while (keywordStart > 0 && isCompletionIdentifierChar(content.charAt(keywordStart - 1))) {
            keywordStart--;
        }
        while (keywordStart < caretPosition && Character.isWhitespace(content.charAt(keywordStart))) {
            keywordStart++;
        }
        int scopeStart = keywordStart > 0 && content.charAt(keywordStart - 1) == '.' ? keywordStart - 1 : -1;
        return new CompletionContext(caretPosition, scopeStart,
            keywordStart, content.substring(keywordStart, caretPosition));
    }

    private boolean isCompletionIdentifierChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '$';
    }

    private ClassTextMembers collectCurrentClassMembers(String content) {
        List<TextMember> fieldList = new ArrayList<>();
        List<TextMember> methodList = new ArrayList<>();
        Matcher matcher = Pattern.compile("([\\w<>\\[\\],.?]+)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(\\(|[;=])")
            .matcher(content);
        while (matcher.find()) {
            String typeName = matcher.group(1);
            String name = matcher.group(2);
            String tail = matcher.group(3);
            if ("(".equals(tail)) {
                methodList.add(new TextMember(name, null, "method"));
            } else {
                fieldList.add(new TextMember(name, JavaUtil.normalizeTypeName(typeName), "field"));
            }
        }
        return new ClassTextMembers(fieldList, methodList);
    }

    private MethodTextMembers collectCurrentMethodMembers(String content, int caretPosition) {
        String beforeCaret = content.substring(0, caretPosition);
        int methodBodyStart = beforeCaret.lastIndexOf('{');
        if (methodBodyStart < 0) {
            return new MethodTextMembers(new ArrayList<>(), new ArrayList<>());
        }

        int signatureStart = findMethodSignatureStart(beforeCaret, methodBodyStart);
        if (signatureStart < 0) {
            return new MethodTextMembers(new ArrayList<>(), new ArrayList<>());
        }

        String signatureText = beforeCaret.substring(signatureStart, methodBodyStart);
        String parameterText = extractMethodParameterText(signatureText);
        return new MethodTextMembers(collectParameters(parameterText),
            collectLocalVariables(beforeCaret.substring(methodBodyStart + 1)));
    }

    private int findMethodSignatureStart(String beforeCaret, int methodBodyStart) {
        for (int i = methodBodyStart - 1; i >= 0; i--) {
            char ch = beforeCaret.charAt(i);
            if (ch == ';' || ch == '}' || ch == '{') {
                return i + 1;
            }
        }
        return 0;
    }

    private String extractMethodParameterText(String signatureText) {
        int parameterStart = signatureText.indexOf('(');
        int parameterEnd = signatureText.lastIndexOf(')');
        if (parameterStart < 0 || parameterEnd <= parameterStart) {
            return "";
        }
        return signatureText.substring(parameterStart + 1, parameterEnd);
    }

    private List<TextMember> collectParameters(String parameterText) {
        List<TextMember> parameterList = new ArrayList<>();
        for (String parameter : splitParameters(parameterText)) {
            String trimmed = parameter.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length >= 2) {
                parameterList.add(new TextMember(parts[parts.length - 1],
                    JavaUtil.normalizeTypeName(String.join(" ",
                        Arrays.copyOf(parts, parts.length - 1))), "variable"));
            }
        }
        return parameterList;
    }

    private List<String> splitParameters(String parameterText) {
        List<String> parameters = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int genericDepth = 0;
        for (int i = 0; i < parameterText.length(); i++) {
            char ch = parameterText.charAt(i);
            if (ch == '<') {
                genericDepth++;
            } else if (ch == '>') {
                genericDepth = Math.max(0, genericDepth - 1);
            }
            if (ch == ',' && genericDepth == 0) {
                parameters.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        if (!current.isEmpty()) {
            parameters.add(current.toString());
        }
        return parameters;
    }

    private List<TextMember> collectLocalVariables(String methodBodyText) {
        List<TextMember> localVariableList = new ArrayList<>();
        Matcher matcher = Pattern.compile("([\\w<>\\[\\],.?]+)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*([=;])")
            .matcher(methodBodyText);
        while (matcher.find()) {
            localVariableList.add(new TextMember(matcher.group(2),
                JavaUtil.normalizeTypeName(matcher.group(1)), "variable"));
        }
        return localVariableList;
    }

    private List<CompleteItem> createVisibleTextCompletionItems(TextCompletionContext textContext,
                                                                CompletionContext context) {
        List<TextMember> visibleMembers = new ArrayList<>();
        visibleMembers.addAll(textContext.getClassMembers().getFieldList());
        visibleMembers.addAll(textContext.getClassMembers().getMethodList());
        visibleMembers.addAll(textContext.getMethodMembers().getParameterList());
        visibleMembers.addAll(textContext.getMethodMembers().getLocalVariableList());
        List<TextMember> searchResult = SearchUtil.search(visibleMembers, TextMember::getName, context.getKeyword());
        List<CompleteItem> itemList = new ArrayList<>();
        Set<String> added = new HashSet<>();
        ListUtil.sub(searchResult, 0, 100).forEach(member -> {
            if (added.add(member.getName())) {
                itemList.add(new CompleteItem(member.getKind(), member.getName(),
                    member.getTypeName() != null ? member.getTypeName() : ".",
                    context.getCaretPosition(), context.getReplaceStart(), member.getName()));
            }
        });
        return itemList;
    }

    private boolean isCompletionScopeBoundary(char ch) {
        return Character.isWhitespace(ch) || ch == ';' || ch == ',' || ch == '=' || ch == '+' || ch == '-' ||
               ch == '*' || ch == '/' || ch == '%' || ch == '&' || ch == '|' || ch == '^' || ch == '!' ||
               ch == '?' || ch == ':' || ch == '<' || ch == '>';
    }

    private void addMemberCompletionItems(List<CompleteItem> itemList, IndexPoint scopeType,
                                          int caretPosition, int start, String keyword,
                                          boolean staticOnly) {
        List<MemberCompletion> memberList = new ArrayList<>();
        scopeType.getFieldList().stream()
            .filter(field -> !staticOnly || field.isStatic())
            .forEach(field -> memberList.add(new MemberCompletion(field.getName(),
                new CompleteItem("field", field.getName(),
                    field.getType() != null ? StrUtil.join(".", (Object[]) field.getType().getPath()) : "void",
                    caretPosition, start, field.getName()))));
        scopeType.getMethodList().stream()
            .filter(method -> !staticOnly || method.isStatic())
            .forEach(method -> memberList.add(new MemberCompletion(method.getName(),
                new CompleteItem("method", method.getName(), method.getReturnType() != null ?
                    StrUtil.join(".", (Object[]) method.getReturnType().getPath()) : "void",
                    caretPosition, start, method.getName()))));

        List<MemberCompletion> searchResult = SearchUtil.search(memberList, MemberCompletion::getKeyword, keyword);
        Set<String> added = new HashSet<>();
        ListUtil.sub(searchResult, 0, 100).forEach(member -> {
            if (added.add(member.getKeyword())) {
                itemList.add(member.getItem());
            }
        });
    }

    private List<CompleteItem> createMemberCompletionItems(IndexPoint scopeType, CompletionContext context,
                                                           boolean staticOnly) {
        List<CompleteItem> itemList = new ArrayList<>();
        addMemberCompletionItems(itemList, scopeType,
            context.getCaretPosition(), context.getReplaceStart(), context.getKeyword(), staticOnly);
        return itemList;
    }

    private JavaTextEditSegment remapIntermediateEditToOriginal(JavaTextEditSegment intermediateEdit,
                                                                JavaTextEditSegment previousEdit) {
        int oldStart = mapIntermediateOffsetToOriginal(intermediateEdit.getOldStart(), previousEdit);
        int oldEnd = mapIntermediateOffsetToOriginal(intermediateEdit.getOldEnd(), previousEdit);
        return new JavaTextEditSegment(oldStart, oldEnd, intermediateEdit.getNewStart(), intermediateEdit.getNewEnd());
    }

    private int mapIntermediateOffsetToOriginal(int intermediateOffset, JavaTextEditSegment previousEdit) {
        int oldLength = previousEdit.getOldEnd() - previousEdit.getOldStart();
        int newLength = previousEdit.getNewEnd() - previousEdit.getNewStart();
        int delta = newLength - oldLength;
        if (intermediateOffset <= previousEdit.getNewStart()) {
            return intermediateOffset;
        }
        if (intermediateOffset >= previousEdit.getNewEnd()) {
            return intermediateOffset - delta;
        }
        return previousEdit.getOldStart();
    }

    private boolean shouldAddImport(CompilationUnit compilationUnit, String importQualifiedName) {
        String simpleName = importQualifiedName.substring(importQualifiedName.lastIndexOf('.') + 1);
        if (JavaUtil.resolvePointByName(editor.getIndex(), state.getIndexPoint(), simpleName, compilationUnit) != null) {
            return false;
        }
        return compilationUnit.getImports().stream()
            .filter(importDeclaration -> !importDeclaration.isAsterisk())
            .map(ImportDeclaration::getNameAsString)
            .noneMatch(importQualifiedName::equals);
    }

    private ImportInsertion insertImport(String content, CompilationUnit compilationUnit, String importQualifiedName) {
        String importText;
        int insertPosition;
        List<ImportDeclaration> importList = compilationUnit.getImports();
        if (!importList.isEmpty()) {
            ImportDeclaration lastImport = importList.getLast();
            insertPosition = lastImport.getRange().map(range -> JavaUtil.getPosition(range.end, content) + 1).orElse(0);
            importText = "\nimport " + importQualifiedName + ";";
        } else {
            Optional<PackageDeclaration> packageDeclaration = compilationUnit.getPackageDeclaration();
            if (packageDeclaration.isPresent()) {
                insertPosition = packageDeclaration.get().getRange().map(range -> JavaUtil.getPosition(range.end, content) + 1)
                    .orElse(0);
                importText = "\n\nimport " + importQualifiedName + ";";
            } else {
                insertPosition = 0;
                importText = "import " + importQualifiedName + ";\n\n";
            }
        }
        String newContent = content.substring(0, insertPosition) + importText + content.substring(insertPosition);
        return new ImportInsertion(newContent,
            new JavaTextEditSegment(insertPosition, insertPosition,
                insertPosition, insertPosition + importText.length()));
    }

    private String getContent() {
        return contentSupplier.get();
    }

    @AllArgsConstructor
    @Getter
    private static class ResolvedCompletionScope {
        private final IndexPoint type;
        private final boolean staticOnly;
    }

    @AllArgsConstructor
    @Getter
    private static class CompletionContext {
        private final int caretPosition;
        private final int scopeStart;
        private final int replaceStart;
        private final String keyword;

        private boolean isMemberCompletion() {
            return scopeStart >= 0;
        }
    }

    @AllArgsConstructor
    @Getter
    private static class TextCompletionContext {
        private final ClassTextMembers classMembers;
        private final MethodTextMembers methodMembers;

        private TextMember findTypeMember(String name) {
            return Stream.concat(methodMembers.getLocalVariableList().stream(),
                    Stream.concat(methodMembers.getParameterList().stream(), classMembers.getFieldList().stream()))
                .filter(member -> member.getName().equals(name))
                .findFirst().orElse(null);
        }
    }

    @AllArgsConstructor
    @Getter
    private static class ClassTextMembers {
        private final List<TextMember> fieldList;
        private final List<TextMember> methodList;
    }

    @AllArgsConstructor
    @Getter
    private static class MethodTextMembers {
        private final List<TextMember> parameterList;
        private final List<TextMember> localVariableList;
    }

    @AllArgsConstructor
    @Getter
    private static class TextMember {
        private final String name;
        private final String typeName;
        private final String kind;
    }

    @AllArgsConstructor
    @Getter
    private static class MemberCompletion {
        private final String keyword;
        private final CompleteItem item;
    }

    @AllArgsConstructor
    @Getter
    private static class ImportInsertion {
        private final String content;
        private final JavaTextEditSegment editSegment;
    }
}
