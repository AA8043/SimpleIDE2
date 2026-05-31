package org.a8043.simpleIDE.fileEditor.javaFile;

import cn.hutool.core.io.FileUtil;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.a8043.simpleIDE.fileEditor.ControllableFile;
import org.a8043.simpleIDE.fileEditor.FileEditor;
import org.a8043.simpleIDE.project.ProjectEditor;
import org.a8043.simpleIDE.project.ProjectModule;
import org.a8043.simpleIDE.project.index.IndexPoint;
import org.a8043.simpleIDE.util.JavaUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Java查找用法: 解析光标处的符号(类型/方法/字段/局部变量), 并在项目源码中查找其所有引用<br>
 * 查找是"跳转到定义"的逆操作: 对每个候选引用节点用所在文件的{@link JavaTypeResolver}解析,
 * 当其解析结果指向同一目标时即视为一处用法, 以保证与跳转到源的行为一致
 */
public class JavaUsageService {
    private final ProjectEditor editor;
    private final JavaFileState state;
    private final Supplier<String> contentSupplier;
    private final Supplier<ControllableFile> fileSupplier;
    private final JavaTypeResolver typeResolver;

    public JavaUsageService(ProjectEditor editor, JavaFileState state, Supplier<String> contentSupplier,
                            Supplier<ControllableFile> fileSupplier, JavaTypeResolver typeResolver) {
        this.editor = editor;
        this.state = state;
        this.contentSupplier = contentSupplier;
        this.fileSupplier = fileSupplier;
        this.typeResolver = typeResolver;
    }

    /**
     * 查找光标处符号在项目中的所有用法
     *
     * @param position 光标0-based位置
     * @return 用法列表, 若光标处无可查找的符号则返回空列表
     */
    public List<FileEditor.Usage> findUsages(int position) {
        CompilationUnit compilationUnit = state.getLatestCompilationUnit();
        if (compilationUnit == null) {
            return List.of();
        }
        UsageTarget target = resolveTarget(position, compilationUnit);
        if (target == null) {
            return List.of();
        }
        // 局部变量/参数: 作用域限于当前文件的所在方法, 无需扫描其它文件
        if (target instanceof LocalTarget localTarget) {
            return findLocalUsages(localTarget);
        }
        return findProjectUsages(target);
    }

    // ===== 解析光标处的目标符号 =====

    private UsageTarget resolveTarget(int position, CompilationUnit unit) {
        String content = getContent();

        // 类型引用(含 new Foo() 的类型部分)
        ClassOrInterfaceType type = firstByName(unit, ClassOrInterfaceType.class, position, content,
            ClassOrInterfaceType::getName);
        if (type != null) {
            IndexPoint typePoint = resolveTypeName(type, unit);
            return typePoint != null ? new TypeTarget(typePoint) : null;
        }

        // 方法调用
        MethodCallExpr methodCall = firstByName(unit, MethodCallExpr.class, position, content,
            MethodCallExpr::getName);
        if (methodCall != null) {
            IndexPoint owner = methodCall.getScope()
                .map(scope -> typeResolver.resolveExpressionType(scope, unit)).orElse(state.getIndexPoint());
            return owner != null ? new MethodTarget(owner, methodCall.getNameAsString()) : null;
        }

        // 字段访问
        FieldAccessExpr fieldAccess = firstByName(unit, FieldAccessExpr.class, position, content,
            FieldAccessExpr::getName);
        if (fieldAccess != null) {
            JavaTypeResolver.JavaFieldLookup lookup = typeResolver.resolveFieldLookup(
                typeResolver.resolveExpressionType(fieldAccess.getScope(), unit), fieldAccess.getNameAsString());
            return lookup != null ? new FieldTarget(lookup.owner(), fieldAccess.getNameAsString()) : null;
        }

        // 方法声明
        MethodDeclaration methodDeclaration = firstByName(unit, MethodDeclaration.class, position, content,
            MethodDeclaration::getName);
        if (methodDeclaration != null && state.getIndexPoint() != null) {
            return new MethodTarget(state.getIndexPoint(), methodDeclaration.getNameAsString());
        }

        // 参数
        Parameter parameter = firstByName(unit, Parameter.class, position, content, Parameter::getName);
        if (parameter != null) {
            return new LocalTarget(parameter, parameter.getNameAsString());
        }

        // 变量声明: 字段或局部变量
        VariableDeclarator variable = firstByName(unit, VariableDeclarator.class, position, content,
            VariableDeclarator::getName);
        if (variable != null) {
            if (variable.findAncestor(FieldDeclaration.class).isPresent()) {
                JavaTypeResolver.JavaFieldLookup lookup = typeResolver.resolveFieldLookup(state.getIndexPoint(),
                    variable.getNameAsString());
                IndexPoint owner = lookup != null ? lookup.owner() : state.getIndexPoint();
                return owner != null ? new FieldTarget(owner, variable.getNameAsString()) : null;
            }
            return new LocalTarget(variable, variable.getNameAsString());
        }

        // 类型声明
        ClassOrInterfaceDeclaration declaration = firstByName(unit, ClassOrInterfaceDeclaration.class, position,
            content, ClassOrInterfaceDeclaration::getName);
        if (declaration != null) {
            IndexPoint typePoint = typeResolver.resolveType(declaration.getNameAsString(), unit);
            if (typePoint == null && state.getIndexPoint() != null &&
                state.getIndexPoint().getName().equals(declaration.getNameAsString())) {
                typePoint = state.getIndexPoint();
            }
            return typePoint != null ? new TypeTarget(typePoint) : null;
        }

        // 普通名称引用: 局部变量/参数 -> 字段 -> 类型
        NameExpr nameExpr = firstByName(unit, NameExpr.class, position, content, NameExpr::getName);
        if (nameExpr != null) {
            return classifyNameExpr(nameExpr, unit);
        }
        return null;
    }

    private UsageTarget classifyNameExpr(NameExpr nameExpr, CompilationUnit unit) {
        String name = nameExpr.getNameAsString();
        Parameter parameter = typeResolver.resolveVisibleParameter(nameExpr);
        if (parameter != null) {
            return new LocalTarget(parameter, name);
        }
        VariableDeclarator localVariable = typeResolver.resolveVisibleLocalVariable(nameExpr);
        if (localVariable != null) {
            return new LocalTarget(localVariable, name);
        }
        JavaTypeResolver.JavaFieldLookup lookup = typeResolver.resolveFieldLookup(state.getIndexPoint(), name);
        if (lookup != null) {
            return new FieldTarget(lookup.owner(), name);
        }
        IndexPoint typePoint = typeResolver.resolveType(name, unit);
        return typePoint != null ? new TypeTarget(typePoint) : null;
    }

    // ===== 局部变量/参数: 仅在当前文件的所在方法内查找 =====

    private List<FileEditor.Usage> findLocalUsages(LocalTarget target) {
        String content = getContent();
        ControllableFile currentFile = fileSupplier.get();
        com.github.javaparser.ast.Node scope = target.declaration.findAncestor(MethodDeclaration.class)
            .map(m -> (com.github.javaparser.ast.Node) m)
            .or(() -> target.declaration.findAncestor(ConstructorDeclaration.class)
                .map(c -> (com.github.javaparser.ast.Node) c))
            .orElse(null);
        if (scope == null) {
            return List.of();
        }

        List<FileEditor.Usage> usageList = new ArrayList<>();
        scope.findAll(NameExpr.class).stream()
            .filter(nameExpr -> nameExpr.getNameAsString().equals(target.name))
            .filter(nameExpr -> resolvesToSameLocal(nameExpr, target))
            .forEach(nameExpr -> addUsage(usageList, currentFile, content,
                nameExpr.getName().getRange().orElse(null), contextOf(nameExpr)));
        return usageList;
    }

    private boolean resolvesToSameLocal(NameExpr nameExpr, LocalTarget target) {
        Parameter parameter = typeResolver.resolveVisibleParameter(nameExpr);
        if (parameter != null) {
            return parameter == target.declaration;
        }
        VariableDeclarator variable = typeResolver.resolveVisibleLocalVariable(nameExpr);
        if (variable != null) {
            return variable == target.declaration;
        }
        // 解析器仅支持方法体内的局部/参数解析; 构造器等上下文无法解析时按名称匹配(不处理同名遮蔽)
        return nameExpr.getNameAsString().equals(target.name);
    }

    // ===== 类型/方法/字段: 扫描全部项目源码 =====

    private List<FileEditor.Usage> findProjectUsages(UsageTarget target) {
        List<FileEditor.Usage> usageList = new ArrayList<>();
        for (File file : collectProjectJavaFiles()) {
            scanFile(file, target, usageList);
        }
        return usageList;
    }

    private void scanFile(File file, UsageTarget target, List<FileEditor.Usage> usageList) {
        String content = currentContentOf(file);
        if (content == null) {
            return;
        }
        CompilationUnit unit = editor.getJavaParser().parse(content).getResult().orElse(null);
        if (unit == null) {
            return;
        }

        IndexPoint filePoint = editor.getIndex().resolveIndexPointByFile(file);
        JavaFileState fileState = new JavaFileState(filePoint);
        JavaTypeResolver resolver = new JavaTypeResolver(editor, fileState, () -> content);

        unit.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(ClassOrInterfaceType n, Void arg) {
                super.visit(n, arg);
                if (!(target instanceof TypeTarget typeTarget)) {
                    return;
                }
                IndexPoint resolved = resolveTypeNameWith(resolver, n, unit);
                if (JavaUtil.isSameType(resolved, typeTarget.point)) {
                    record(n, n.getName().getRange().orElse(null));
                }
            }

            @Override
            public void visit(MethodCallExpr n, Void arg) {
                super.visit(n, arg);
                if (!(target instanceof MethodTarget methodTarget) ||
                    !n.getNameAsString().equals(methodTarget.name)) {
                    return;
                }
                IndexPoint owner = n.getScope()
                    .map(scope -> resolver.resolveExpressionType(scope, unit)).orElse(filePoint);
                if (memberOwnerMatches(owner, methodTarget.owner)) {
                    record(n, n.getName().getRange().orElse(null));
                }
            }

            @Override
            public void visit(MethodDeclaration n, Void arg) {
                super.visit(n, arg);
                if (target instanceof MethodTarget methodTarget && n.getNameAsString().equals(methodTarget.name) &&
                    memberOwnerMatches(filePoint, methodTarget.owner)) {
                    record(n, n.getName().getRange().orElse(null));
                }
            }

            @Override
            public void visit(FieldAccessExpr n, Void arg) {
                super.visit(n, arg);
                if (!(target instanceof FieldTarget fieldTarget) ||
                    !n.getNameAsString().equals(fieldTarget.name)) {
                    return;
                }
                JavaTypeResolver.JavaFieldLookup lookup = resolver.resolveFieldLookup(
                    resolver.resolveExpressionType(n.getScope(), unit), n.getNameAsString());
                if (lookup != null && memberOwnerMatches(lookup.owner(), fieldTarget.owner)) {
                    record(n, n.getName().getRange().orElse(null));
                }
            }

            @Override
            public void visit(NameExpr n, Void arg) {
                super.visit(n, arg);
                if (target instanceof MethodTarget) {
                    return;
                }
                if (target instanceof FieldTarget fieldTarget && n.getNameAsString().equals(fieldTarget.name)) {
                    if (resolver.resolveVisibleParameter(n) != null ||
                        resolver.resolveVisibleLocalVariable(n) != null) {
                        return;
                    }
                    JavaTypeResolver.JavaFieldLookup lookup = resolver.resolveFieldLookup(filePoint, n.getNameAsString());
                    if (lookup != null && memberOwnerMatches(lookup.owner(), fieldTarget.owner)) {
                        record(n, n.getName().getRange().orElse(null));
                    }
                } else if (target instanceof TypeTarget typeTarget) {
                    if (resolver.resolveVisibleParameter(n) != null ||
                        resolver.resolveVisibleLocalVariable(n) != null ||
                        resolver.resolveFieldLookup(filePoint, n.getNameAsString()) != null) {
                        return;
                    }
                    IndexPoint resolved = resolver.resolveType(n.getNameAsString(), unit);
                    if (JavaUtil.isSameType(resolved, typeTarget.point)) {
                        record(n, n.getName().getRange().orElse(null));
                    }
                }
            }

            private void record(com.github.javaparser.ast.Node node, Range range) {
                addUsage(usageList, editor.openFile(file), content, range, contextOf(node));
            }
        }, null);
    }

    private boolean memberOwnerMatches(IndexPoint resolvedOwner, IndexPoint targetOwner) {
        // 成员可能在目标类型或其子类上被引用; 沿继承链向上比较即可覆盖继承调用
        for (IndexPoint current = resolvedOwner; current != null; current = current.getParent()) {
            if (JavaUtil.isSameType(current, targetOwner)) {
                return true;
            }
        }
        return false;
    }

    private IndexPoint resolveTypeName(ClassOrInterfaceType type, CompilationUnit unit) {
        return resolveTypeNameWith(typeResolver, type, unit);
    }

    private IndexPoint resolveTypeNameWith(JavaTypeResolver resolver, ClassOrInterfaceType type, CompilationUnit unit) {
        IndexPoint resolved = resolver.resolveType(type.getNameWithScope(), unit);
        return resolved != null ? resolved : resolver.resolveType(type.getNameAsString(), unit);
    }

    // ===== 工具方法 =====

    private List<File> collectProjectJavaFiles() {
        List<File> fileList = new ArrayList<>();
        for (ProjectModule module : editor.getProjectModel().getModuleList()) {
            if (module.getLocation() != ProjectModule.Location.PROJECT) {
                continue;
            }
            for (File srcDir : module.getSrcDirList()) {
                if (srcDir.exists()) {
                    FileUtil.walkFiles(srcDir, file -> {
                        if (file.getName().endsWith(".java")) {
                            fileList.add(file);
                        }
                    });
                }
            }
        }
        return fileList;
    }

    /**
     * 取文件的最新内容: 若该文件已在编辑器中打开则用其(可能未保存的)内容, 否则从磁盘读取
     */
    private String currentContentOf(File file) {
        ControllableFile opened = editor.getOpenedFileList().stream()
            .filter(f -> Objects.equals(f.getFile(), file)).findFirst().orElse(null);
        if (opened != null) {
            return opened.getContent();
        }
        return file.exists() ? FileUtil.readUtf8String(file) : null;
    }

    private void addUsage(List<FileEditor.Usage> usageList, ControllableFile file, String content, Range range,
                          String context) {
        if (range == null) {
            return;
        }
        int position = JavaUtil.getPosition(range.begin, content);
        int line = range.begin.line;
        usageList.add(new FileEditor.Usage(file, position, line, context, lineTextOf(content, line)));
    }

    private String contextOf(com.github.javaparser.ast.Node node) {
        if (node == null) {
            return "";
        }
        return node.findAncestor(MethodDeclaration.class)
            .map(m -> typeOf(node) + "." + m.getNameAsString())
            .orElseGet(() -> typeOf(node));
    }

    private String typeOf(com.github.javaparser.ast.Node node) {
        return node.findAncestor(ClassOrInterfaceDeclaration.class)
            .map(ClassOrInterfaceDeclaration::getNameAsString).orElse("");
    }

    private static String lineTextOf(String content, int line) {
        String[] lines = content.split("\n", -1);
        if (line < 1 || line > lines.length) {
            return "";
        }
        return lines[line - 1].strip();
    }

    private <T extends com.github.javaparser.ast.Node> T firstByName(
        CompilationUnit unit, Class<T> nodeType, int position, String content,
        java.util.function.Function<T, com.github.javaparser.ast.expr.SimpleName> nameGetter) {
        return unit.findAll(nodeType).stream()
            .filter(node -> {
                Range range = nameGetter.apply(node).getRange().orElse(null);
                return range != null && JavaUtil.isInRange(position, range, content);
            })
            .findFirst().orElse(null);
    }

    private String getContent() {
        return contentSupplier.get();
    }

    // ===== 目标类型 =====

    private interface UsageTarget {
    }

    private record TypeTarget(IndexPoint point) implements UsageTarget {
    }

    private record MethodTarget(IndexPoint owner, String name) implements UsageTarget {
    }

    private record FieldTarget(IndexPoint owner, String name) implements UsageTarget {
    }

    private record LocalTarget(com.github.javaparser.ast.Node declaration, String name) implements UsageTarget {
    }
}
