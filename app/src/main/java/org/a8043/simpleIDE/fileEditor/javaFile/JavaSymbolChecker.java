package org.a8043.simpleIDE.fileEditor.javaFile;

import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.TypeParameter;
import org.a8043.simpleIDE.fileEditor.CodeError;
import org.a8043.simpleIDE.project.ProjectEditor;
import org.a8043.simpleIDE.project.ProjectModule;
import org.a8043.simpleIDE.project.index.FieldSignature;
import org.a8043.simpleIDE.project.index.IndexPoint;
import org.a8043.simpleIDE.project.index.MethodSignature;
import org.a8043.simpleIDE.resource.ResourceManager;
import org.a8043.simpleIDE.util.JavaUtil;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Java语义检查: 解析项目内Java文件中的符号引用, 若无法解析则添加SEMANTIC_ERROR<br>
 * 检查范围: 类型引用(ClassOrInterfaceType)、变量/符号(NameExpr)、方法调用(MethodCallExpr)<br>
 * 由于索引是有意不完整的(依赖库类、内部类、泛型不完整), 因此采取保守策略: 仅在能确认时才报错
 */
public class JavaSymbolChecker {
    private static final String[] OBJECT_PATH = {"java", "lang", "Object"};

    private final ProjectEditor editor;
    private final JavaFileState state;
    private final JavaTypeResolver typeResolver;
    private final Supplier<String> contentSupplier;

    /**
     * 单次check()期间的类型成员缓存, 避免重复解析超类型源码
     */
    private final Map<IndexPoint, MemberIndex> memberCache = new IdentityHashMap<>();
    private Set<String> objectMemberMethodNames = Set.of();
    private Set<String> objectMemberFieldNames = Set.of();
    /**
     * 当前文件的实时编译单元: 索引同步发生在check()之后, 磁盘源码亦可能滞后,
     * 故当前类型的成员需取自该实时单元而非索引/磁盘, 避免对刚输入的成员误报
     */
    private CompilationUnit currentUnit;

    public JavaSymbolChecker(ProjectEditor editor, JavaFileState state, JavaTypeResolver typeResolver,
                             Supplier<String> contentSupplier) {
        this.editor = editor;
        this.state = state;
        this.typeResolver = typeResolver;
        this.contentSupplier = contentSupplier;
    }

    /**
     * 对一个解析成功的编译单元执行语义检查, 将无法解析的符号作为SEMANTIC_ERROR加入问题列表
     *
     * @param unit 解析成功的编译单元
     * @param content 源文件完整文本
     */
    public void check(CompilationUnit unit, String content) {
        if (!shouldCheck(unit)) {
            return;
        }
        memberCache.clear();
        currentUnit = unit;
        cacheObjectMembers();

        checkTypeReferences(unit, content);
        checkNameReferences(unit, content);
        checkFieldAccesses(unit, content);
        checkMethodCalls(unit, content);
    }

    /**
     * 判断当前文件是否满足执行语义检查的前提条件<br>
     * 仅当索引已就绪、当前文件属于项目源码模块、且基础类型(String/Object)可解析时才检查,
     * 以避免在索引不完整时产生误报
     */
    private boolean shouldCheck(CompilationUnit unit) {
        if (editor == null || !editor.getIndex().isIndexed()) {
            return false;
        }
        IndexPoint indexPoint = state.getIndexPoint();
        if (indexPoint == null || indexPoint.getPkg() == null || indexPoint.getPkg().getModule() == null) {
            return false;
        }
        ProjectModule projectModule = indexPoint.getPkg().getModule().getProjectModule();
        if (projectModule == null || projectModule.getLocation() != ProjectModule.Location.PROJECT) {
            return false;
        }
        // 健全性检查: 确认java.lang可达, 防止过激的onlyIndexStartsWith配置导致全量误报
        return typeResolver.resolveType("String", unit) != null &&
               typeResolver.resolveType("Object", unit) != null;
    }

    private void cacheObjectMembers() {
        IndexPoint objectType = JavaUtil.resolvePointByPath(editor.getIndex(),
            state.getIndexPoint().getPkg().getModule(), OBJECT_PATH);
        if (objectType == null) {
            objectMemberMethodNames = Set.of();
            objectMemberFieldNames = Set.of();
            return;
        }
        objectMemberMethodNames = objectType.getMethodList().stream()
            .map(MethodSignature::getName).collect(Collectors.toUnmodifiableSet());
        objectMemberFieldNames = objectType.getFieldList().stream()
            .map(FieldSignature::getName).collect(Collectors.toUnmodifiableSet());
    }

    // ===== 类型引用检查 =====

    private void checkTypeReferences(CompilationUnit unit, String content) {
        unit.findAll(ClassOrInterfaceType.class).forEach(type -> {
            // 仅检查最外层名称, 限定名(如a.b.C)的scope交由其自身的ClassOrInterfaceType处理
            if (type.getScope().isPresent()) {
                return;
            }
            String simpleName = type.getNameAsString();
            if (isTypeParameter(type, simpleName)) {
                return;
            }
            if (resolveTypeName(type, unit) != null) {
                return;
            }
            // 保守: 该名称被import或*-import覆盖时可能来自未索引的依赖/JDK, 不报错
            if (isImported(unit, simpleName)) {
                return;
            }
            addError(type.getName().getRange().orElse(null), content,
                "semanticError.cannotResolveType", simpleName);
        });
    }

    private IndexPoint resolveTypeName(ClassOrInterfaceType type, CompilationUnit unit) {
        IndexPoint resolved = typeResolver.resolveType(type.getNameWithScope(), unit);
        if (resolved != null) {
            return resolved;
        }
        return typeResolver.resolveType(type.getNameAsString(), unit);
    }

    /**
     * 判断给定名称是否为所在类/方法/构造器声明的泛型类型参数, 这类名称不应作为类型解析失败处理
     */
    static boolean isTypeParameter(Node node, String name) {
        for (Node current = node; current != null; current = current.getParentNode().orElse(null)) {
            List<TypeParameter> typeParameterList = switch (current) {
                case ClassOrInterfaceDeclaration declaration -> declaration.getTypeParameters();
                case MethodDeclaration declaration -> declaration.getTypeParameters();
                case ConstructorDeclaration declaration -> declaration.getTypeParameters();
                default -> null;
            };
            if (typeParameterList != null && typeParameterList.stream()
                .anyMatch(parameter -> parameter.getNameAsString().equals(name))) {
                return true;
            }
        }
        return false;
    }

    // ===== 变量/符号引用检查 =====

    private void checkNameReferences(CompilationUnit unit, String content) {
        unit.findAll(NameExpr.class).forEach(nameExpr -> {
            String name = nameExpr.getNameAsString();
            // 保守: switch的case标签(枚举常量)与注解内的名称往往指向当前类型外/未索引的符号
            if (isInSwitchCaseLabel(nameExpr) || isInAnnotation(nameExpr)) {
                return;
            }
            if (isResolvableName(nameExpr, name, unit)) {
                return;
            }
            // 保守: 名称可能是被静态导入或*-import引入的成员/类型
            if (isImported(unit, name) || isStaticImported(unit, name)) {
                return;
            }
            // 限定名前缀(如a.b.C中的a): 仅当其为已知包名或符合包命名约定(非大写开头)时跳过,
            // 否则视为可解析失败的类型引用(如把System误写为Systema)
            if (isQualifierName(nameExpr) && isSuppressedQualifierRoot(name)) {
                return;
            }
            addError(nameExpr.getName().getRange().orElse(null), content,
                "semanticError.cannotResolveSymbol", name);
        });
    }

    /**
     * 判断限定名前缀根名是否应被抑制(不报错)<br>
     * 已知包名或符合包命名约定(首字母非大写)的名称视为包前缀; 其它(如大写开头的类型名误写)则不抑制
     */
    private boolean isSuppressedQualifierRoot(String name) {
        return isKnownPackageRoot(name) || looksLikePackageSegment(name);
    }

    /**
     * 判断名称是否符合包路径段命名约定(空或首字母非大写), 大写开头的名称通常是类型名而非包名
     */
    static boolean looksLikePackageSegment(String name) {
        return name.isEmpty() || !Character.isUpperCase(name.charAt(0));
    }

    /**
     * 判断给定名称是否为索引中任一包路径的首段(即一个包的根段名)
     */
    private boolean isKnownPackageRoot(String name) {
        return editor.getIndex().getModuleList().stream()
            .flatMap(module -> module.getPackageList().stream())
            .map(pkg -> pkg.getPath())
            .anyMatch(path -> path != null && path.length > 0 && path[0].equals(name));
    }

    private boolean isInSwitchCaseLabel(NameExpr nameExpr) {
        Node parent = nameExpr.getParentNode().orElse(null);
        return parent instanceof SwitchEntry;
    }

    private boolean isInAnnotation(NameExpr nameExpr) {
        return nameExpr.findAncestor(AnnotationExpr.class).isPresent();
    }

    private boolean isResolvableName(NameExpr nameExpr, String name, CompilationUnit unit) {
        // 局部变量/参数
        if (typeResolver.resolveVisibleParameter(nameExpr) != null ||
            typeResolver.resolveVisibleLocalVariable(nameExpr) != null) {
            return true;
        }
        // 模式变量(instanceof / switch)
        if (isPatternVariable(nameExpr, name)) {
            return true;
        }
        // 字段(含继承字段)
        IndexPoint currentType = currentTypeFor(nameExpr, unit);
        MemberIndex memberIndex = collectMembers(currentType);
        if (memberIndex.hasField(name)) {
            return true;
        }
        // 作为类型名引用(如静态访问 Integer.MAX_VALUE 中的 Integer)
        if (typeResolver.resolveType(name, unit) != null) {
            return true;
        }
        // 成员信息不完整时保守放行, 不报错
        return !memberIndex.complete();
    }

    /**
     * 判断NameExpr是否为某个字段访问/方法调用的限定名前缀的一部分,
     * 这类名称往往是包名或外部类名片段, 索引无法可靠覆盖, 因此跳过
     */
    static boolean isQualifierName(NameExpr nameExpr) {
        Node parent = nameExpr.getParentNode().orElse(null);
        if (parent instanceof FieldAccessExpr fieldAccessExpr) {
            return fieldAccessExpr.getScope() == nameExpr;
        }
        return false;
    }

    private boolean isPatternVariable(NameExpr nameExpr, String name) {
        int position = nameExpr.getName().getRange()
            .map(range -> JavaUtil.getPosition(range.begin, getContent())).orElse(Integer.MIN_VALUE);
        return nameExpr.findCompilationUnit()
            .map(unit -> unit.findAll(TypePatternExpr.class).stream()
                .anyMatch(pattern -> pattern.getNameAsString().equals(name) &&
                                     pattern.getRange()
                                         .map(range -> JavaUtil.getPosition(range.begin, getContent()) <= position)
                                         .orElse(false)))
            .orElse(false);
    }

    // ===== 字段访问检查 =====

    private void checkFieldAccesses(CompilationUnit unit, String content) {
        unit.findAll(FieldAccessExpr.class).forEach(fieldAccessExpr -> {
            // 保守: 注解内的字段访问往往指向未索引的常量
            if (fieldAccessExpr.findAncestor(AnnotationExpr.class).isPresent()) {
                return;
            }
            String name = fieldAccessExpr.getNameAsString();
            String qualifiedName = JavaUtil.resolveQualifiedName(fieldAccessExpr);
            if (qualifiedName != null && JavaUtil.resolveType(editor.getIndex(), currentTypeFor(fieldAccessExpr, unit),
                qualifiedName, unit) != null) {
                return;
            }
            // 无法解析scope类型时保守放行(如java.lang这类包名前缀, scope类型为null)
            IndexPoint ownerType = typeResolver.resolveExpressionType(fieldAccessExpr.getScope(), unit);
            if (ownerType == null) {
                return;
            }
            if (JavaUtil.resolveNestedPoint(ownerType, name) != null) {
                return;
            }
            MemberIndex memberIndex = collectMembers(ownerType);
            if (!memberIndex.complete() || memberIndex.hasField(name)) {
                return;
            }
            // 数组的length由索引特殊处理, 此处已在成员集合中, 无需额外判断
            addError(fieldAccessExpr.getName().getRange().orElse(null), content,
                "semanticError.cannotResolveSymbol", name);
        });
    }

    // ===== 方法调用检查 =====

    private void checkMethodCalls(CompilationUnit unit, String content) {
        unit.findAll(MethodCallExpr.class).forEach(methodCallExpr -> {
            String name = methodCallExpr.getNameAsString();
            Expression scope = methodCallExpr.getScope().orElse(null);

            IndexPoint ownerType;
            if (scope == null) {
                // 无scope: 调用当前类型(含继承)的方法
                ownerType = currentTypeFor(methodCallExpr, unit);
                // 保守: 可能是静态导入的方法
                if (isStaticImported(unit, name)) {
                    return;
                }
            } else {
                ownerType = typeResolver.resolveExpressionType(scope, unit);
                // 无法解析owner类型时保守放行
                if (ownerType == null) {
                    return;
                }
            }

            MemberIndex memberIndex = collectMembers(ownerType);
            if (!memberIndex.complete() || memberIndex.hasMethod(name)) {
                return;
            }
            addError(methodCallExpr.getName().getRange().orElse(null), content,
                "semanticError.cannotResolveMethod", name);
        });
    }

    // ===== 类型成员收集 =====

    /**
     * 收集给定类型(含其所有可解析超类型与Object)的方法名与字段名集合<br>
     * 若任一超类型无法解析(可能来自未索引的依赖库/JDK), 则标记结果为不完整,
     * 调用方据此保守放行而非误报
     *
     * @param type 起始类型
     * @return 成员索引, complete标识是否完整覆盖了继承链
     */
    private MemberIndex collectMembers(IndexPoint type) {
        if (type == null) {
            return new MemberIndex(Set.of(), Set.of(), false);
        }
        MemberIndex cached = memberCache.get(type);
        if (cached != null) {
            return cached;
        }

        Set<String> methodNames = new HashSet<>(objectMemberMethodNames);
        Set<String> fieldNames = new HashSet<>(objectMemberFieldNames);
        boolean complete = collectMembers0(type, methodNames, fieldNames,
            Collections.newSetFromMap(new IdentityHashMap<>()));
        MemberIndex result = new MemberIndex(methodNames, fieldNames, complete);
        memberCache.put(type, result);
        return result;
    }

    private boolean collectMembers0(IndexPoint type, Set<String> methodNames, Set<String> fieldNames,
                                    Set<IndexPoint> visited) {
        if (type == null) {
            return false;
        }
        if (!visited.add(type)) {
            return true;
        }

        type.getMethodList().forEach(method -> methodNames.add(method.getName()));
        type.getFieldList().forEach(field -> fieldNames.add(field.getName()));

        ClassOrInterfaceDeclaration declaration = resolveDeclaration(type);
        if (declaration == null) {
            // 没有源码声明: 无法得知其超类型, 视为不完整
            return false;
        }

        // 来自源码声明的成员
        declaration.getMethods().forEach(method -> methodNames.add(method.getNameAsString()));
        declaration.getFields().forEach(field -> field.getVariables()
            .forEach(variable -> fieldNames.add(variable.getNameAsString())));

        CompilationUnit declarationUnit = isTypeFromCurrentSource(type) ? currentUnit : type.resolveCompilationUnit();
        boolean complete = true;
        for (ClassOrInterfaceType superType : superTypesOf(declaration)) {
            IndexPoint superPoint = resolveSuperType(type, superType, declarationUnit);
            if (superPoint == null) {
                complete = false;
                continue;
            }
            complete &= collectMembers0(superPoint, methodNames, fieldNames, visited);
        }
        // 隐式继承java.lang.Object: 若声明没有显式extends, 仍认为完整(Object成员已预置)
        return complete;
    }

    private List<ClassOrInterfaceType> superTypesOf(ClassOrInterfaceDeclaration declaration) {
        return Stream.concat(
            declaration.getExtendedTypes().stream(),
            declaration.getImplementedTypes().stream()).toList();
    }

    /**
     * 解析类型对应的源码声明<br>
     * 对当前文件正在编辑的类型, 取自实时编译单元(索引同步发生在检查之后, 磁盘源码可能滞后);
     * 其它类型则从其源文件解析
     */
    private ClassOrInterfaceDeclaration resolveDeclaration(IndexPoint type) {
        if (isTypeFromCurrentSource(type) && currentUnit != null) {
            return currentUnit.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(declaration -> Arrays.equals(JavaUtil.buildTypePath(currentUnit, declaration), type.getPath()))
                .findFirst().orElse(null);
        }
        return typeResolver.resolveTypeDeclaration(type);
    }

    private boolean isTypeFromCurrentSource(IndexPoint type) {
        IndexPoint current = state.getIndexPoint();
        if (type == null || current == null) {
            return false;
        }
        return JavaUtil.isSameType(type, current) ||
               type.getPkg() != null && current.getPkg() != null &&
               Objects.equals(type.getPkg().getModule().getCacheName(), current.getPkg().getModule().getCacheName()) &&
               Arrays.equals(type.getSourcePath(), current.getSourcePath());
    }

    private IndexPoint currentTypeFor(Node node, CompilationUnit unit) {
        IndexPoint fallback = state.getIndexPoint();
        if (fallback == null || unit == null) {
            return fallback;
        }
        TypeDeclaration<?> declaration = node.findAncestor(TypeDeclaration.class).orElse(null);
        if (declaration == null && node instanceof TypeDeclaration<?> typeDeclaration) {
            declaration = typeDeclaration;
        }
        if (declaration == null || fallback.getPkg() == null || fallback.getPkg().getModule() == null) {
            return fallback;
        }
        IndexPoint resolved = JavaUtil.resolvePointByPath(editor.getIndex(), fallback.getPkg().getModule(),
            JavaUtil.buildTypePath(unit, declaration));
        return resolved != null ? resolved : fallback;
    }

    private IndexPoint resolveSuperType(IndexPoint owner, ClassOrInterfaceType superType,
                                        CompilationUnit declarationUnit) {
        IndexPoint resolved = JavaUtil.resolveType(editor.getIndex(), owner,
            superType.getNameWithScope(), declarationUnit);
        if (resolved != null) {
            return resolved;
        }
        return JavaUtil.resolveType(editor.getIndex(), owner,
            superType.getNameAsString(), declarationUnit);
    }

    // ===== 工具方法 =====

    /**
     * 判断给定简单名是否被非静态的具名import或*-import覆盖<br>
     * 这类名称可能来自未被索引的依赖库或JDK包, 保守起见不视为解析失败
     */
    static boolean isImported(CompilationUnit unit, String simpleName) {
        for (ImportDeclaration importDeclaration : unit.getImports()) {
            if (importDeclaration.isStatic()) {
                continue;
            }
            if (importDeclaration.isAsterisk()) {
                return true;
            }
            String importName = importDeclaration.getNameAsString();
            String last = importName.substring(importName.lastIndexOf('.') + 1);
            if (last.equals(simpleName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断给定名称是否被静态import(具名或*-import)引入
     */
    static boolean isStaticImported(CompilationUnit unit, String name) {
        for (ImportDeclaration importDeclaration : unit.getImports()) {
            if (!importDeclaration.isStatic()) {
                continue;
            }
            if (importDeclaration.isAsterisk()) {
                return true;
            }
            String importName = importDeclaration.getNameAsString();
            String last = importName.substring(importName.lastIndexOf('.') + 1);
            if (last.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private void addError(Range range, String content, String messageKey, Object arg) {
        if (range == null) {
            return;
        }
        int start = JavaUtil.getPosition(range.begin, content);
        int end = JavaUtil.getPosition(range.end, content) + 1;
        state.getProblemList().add(new CodeError(start, end,
            ResourceManager.getText(messageKey, arg), CodeError.Type.SEMANTIC_ERROR));
        state.getProblemHighlightList().add(new JavaSyntaxHighlight(range, "problem"));
    }

    private String getContent() {
        return contentSupplier.get();
    }

    /**
     * 类型成员名称集合及其完整性标识
     *
     * @param methodNames 方法名集合
     * @param fieldNames 字段名集合
     * @param complete 是否完整覆盖了继承链(false表示存在无法解析的超类型)
     */
    private record MemberIndex(Set<String> methodNames, Set<String> fieldNames, boolean complete) {
        boolean hasMethod(String name) {
            return methodNames.contains(name);
        }

        boolean hasField(String name) {
            return fieldNames.contains(name);
        }
    }
}
