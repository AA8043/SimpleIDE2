package org.a8043.simpleIDE.util;

import cn.hutool.core.util.ArrayUtil;
import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.a8043.simpleIDE.project.ProjectModule;
import org.a8043.simpleIDE.project.index.Index;
import org.a8043.simpleIDE.project.index.IndexPoint;
import org.a8043.simpleIDE.project.index.Module;
import org.a8043.simpleIDE.project.index.Package;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Java工具类
 */
public class JavaUtil {
    /**
     * 判断两个IndexPoint是否指向同一类型<br>
     * 同一对象, 或同模块且路径相同则视为相同
     *
     * @param left 左侧类型
     * @param right 右侧类型
     * @return 是否为同一类型
     */
    public static boolean isSameType(IndexPoint left, IndexPoint right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null || left.getPkg() == null || right.getPkg() == null) {
            return false;
        }
        return Objects.equals(left.getPkg().getModule().getCacheName(),
            right.getPkg().getModule().getCacheName()) && Arrays.equals(left.getPath(), right.getPath());
    }

    /**
     * 在给定索引中查找包含指定路径的模块
     *
     * @param index 索引
     * @param path 路径
     * @return 如果找到则返回对应的Module, 否则返回null
     */
    public static Module resolveModuleByPath(Index index, String[] path) {
        if (index == null || path == null) {
            return null;
        }
        return index.getModuleList().stream().filter(module -> module.hasPoint(path)).findFirst().orElse(null);
    }

    /**
     * 根据当前源点所在模块和其可见模块列表解析路径对应的模块
     *
     * @param index 索引
     * @param source 当前上下文的IndexPoint
     * @param path 要解析的路径数组
     * @return 匹配的 Module或null
     */
    public static Module resolveModuleByPath(Index index, IndexPoint source, String[] path) {
        if (index == null || source == null || path == null) {
            return null;
        }
        Module module = source.getPkg().getModule();
        List<Module> requireList = module.getRequireList();
        if (requireList == null) {
            requireList = index.getModuleList();
        }
        return requireList.stream().filter(module1 -> module1.hasPoint(path)).findFirst()
            .orElseGet(() -> module.hasPoint(path) ? module : null);
    }

    /**
     * 根据名称解析一个IndexPoint<br>
     * 解析策略:
     * <ol>
     *   <li>在索引的基本类型表中查找</li>
     *   <li>在当前单元内查找同名类型声明</li>
     *   <li>在同包中查找</li>
     *   <li>根据 import 或 import * 解析</li>
     *   <li>尝试 java.lang 包查找</li>
     * </ol>
     *
     * @param index 索引
     * @param source 源IndexPoint
     * @param name 要解析的名称
     * @param unit 当前解析的CompilationUnit
     * @return 匹配的 IndexPoint或null
     */
    public static IndexPoint resolvePointByName(Index index, IndexPoint source, String name, CompilationUnit unit) {
        if (index != null) {
            IndexPoint basicType = index.getBasicTypeMap().get(name);
            if (basicType != null) {
                return basicType;
            }
        }

        if (unit != null && source != null) {
            if (Objects.equals(source.getName(), name)) {
                return source;
            }
        }

        if (index != null && unit != null && source != null) {
            TypeDeclaration<?> declaration = unit.findAll(TypeDeclaration.class).stream()
                .filter(type -> type.getNameAsString().equals(name)).findFirst().orElse(null);
            if (declaration != null) {
                Module module = source.getPkg() != null ? source.getPkg().getModule() : null;
                if (module != null) {
                    IndexPoint point = module.getPoint(buildTypePath(unit, declaration));
                    if (point != null) {
                        return point;
                    }
                }
                if (Objects.equals(source.getName(), declaration.getNameAsString())) {
                    return source;
                }
            }
        }

        if (index != null && source != null) {
            for (IndexPoint owner = source; owner != null; owner = owner.getEnclosingType()) {
                IndexPoint nestedPoint = resolveNestedPoint(owner, name);
                if (nestedPoint != null) {
                    return nestedPoint;
                }
            }
        }

        if (index != null && source != null) {
            Package pkg = source.getPkg();
            if (pkg != null) {
                IndexPoint inPkgPoint = pkg.getPoint(name);
                if (inPkgPoint != null) {
                    return inPkgPoint;
                }
            }
        }

        if (index != null && unit != null) {
            AtomicReference<String[]> importResult = new AtomicReference<>();
            NodeList<ImportDeclaration> importList = unit.getImports();
            importList.forEach(imp -> {
                String[] split = imp.getNameAsString().split("\\.");
                if (!imp.isStatic() && split.length > 0 && split[split.length - 1].equals(name)) {
                    importResult.set(split);
                }
            });
            if (importResult.get() != null) {
                IndexPoint point = resolvePointByPath(index,
                    Objects.requireNonNull(source).getPkg().getModule(), importResult.get());
                if (point != null) {
                    return point;
                }
            }
        }

        if (index != null) {
            Package pkg = index.getModule("java.base").getPackage(new String[]{"java", "lang"});
            if (pkg != null) {
                IndexPoint langPoint = pkg.getPoint(name);
                if (langPoint != null) {
                    return langPoint;
                }
            }
        }

        if (unit != null && index != null && source != null) {
            AtomicReference<IndexPoint> indexPoint = new AtomicReference<>();
            unit.getImports().forEach(declaration -> {
                if (declaration.isAsterisk()) {
                    String[] path = ArrayUtil.addAll(declaration.getNameAsString().split("\\."), new String[]{name});
                    IndexPoint point = resolvePointByPath(index, source.getPkg().getModule(), path);
                    if (point != null) {
                        indexPoint.set(point);
                    }
                }
            });
            if (indexPoint.get() != null) {
                return indexPoint.get();
            }
        }

        return null;
    }

    /**
     * 解析一个可能包含泛型和数组表示的类型名称为IndexPoint
     *
     * @param index 索引
     * @param source 当前上下文点
     * @param typeName 原始类型名
     * @param unit 当前CompilationUnit, 用于imports/内部类型解析
     * @return 解析后的 IndexPoint或null
     */
    public static IndexPoint resolveType(Index index, IndexPoint source, String typeName, CompilationUnit unit) {
        if (index == null || typeName == null || typeName.isBlank()) {
            return null;
        }

        String normalizedTypeName = normalizeTypeName(typeName);
        int arrayDepth = countArrayDimensions(normalizedTypeName);
        String componentTypeName = stripArraySuffix(normalizedTypeName);
        componentTypeName = eraseTypeArguments(componentTypeName).trim();
        if (componentTypeName.startsWith("? extends ")) {
            componentTypeName = componentTypeName.substring("? extends ".length()).trim();
        } else if (componentTypeName.startsWith("? super ")) {
            componentTypeName = componentTypeName.substring("? super ".length()).trim();
        } else if ("?".equals(componentTypeName)) {
            componentTypeName = "Object";
        }

        IndexPoint componentType = resolveDeclaredType(index, source, componentTypeName, unit);
        if (componentType == null) {
            return null;
        }
        for (int i = 0; i < arrayDepth; i++) {
            componentType = index.getOrCreateArrayType(componentType);
        }
        return componentType;
    }

    /**
     * 在模块依赖图中按宽度优先搜索解析给定路径对应的IndexPoint
     * 会优先搜索source模块自身, 然后其依赖(含传递依赖), 最后尝试unnamed和JDK模块
     *
     * @param index 索引
     * @param source 要从哪个模块开始解析
     * @param path 要解析的路径数组
     * @return 找到的IndexPoint或null
     */
    public static IndexPoint resolvePointByPath(Index index, Module source, String[] path) {
        IndexPoint pointInSelf = source.getPoint(path);
        if (pointInSelf != null) {
            return pointInSelf;
        }

        Set<Module> visited = new HashSet<>();
        Queue<Module> queue = new LinkedList<>();

        source.getRequireList().stream().filter(dep -> !visited.contains(dep)).forEach(dep -> {
            visited.add(dep);
            queue.offer(dep);
        });

        while (!queue.isEmpty()) {
            Module current = queue.poll();
            IndexPoint point = current.getPoint(path);
            if (point != null) {
                return point;
            }
            for (Module transitiveDep : current.getRequireList()) {
                if (!visited.contains(transitiveDep)) {
                    visited.add(transitiveDep);
                    queue.offer(transitiveDep);
                }
            }
        }

        Module unnamedModule = index.getModuleList().getFirst();
        if (unnamedModule != null && !visited.contains(unnamedModule)) {
            IndexPoint pointInUnnamed = unnamedModule.getPoint(path);
            if (pointInUnnamed != null) {
                return pointInUnnamed;
            }
        }

        for (Module module : index.getModuleList()) {
            if (module.getProjectModule() != null &&
                module.getProjectModule().getLocation() == ProjectModule.Location.JDK &&
                !visited.contains(module)) {
                IndexPoint point = module.getPoint(path);
                if (point != null) {
                    return point;
                }
            }
        }

        return null;
    }

    /**
     * 将JavaParser的Position转换为给定文本的0-based字符偏移量
     *
     * @param pos JavaParser Position
     * @param text 完整文本内容
     * @return 0-based偏移量. 如果超出范围, 返回文本长度或 0
     */
    public static int getPosition(Position pos, String text) {
        if (text.isEmpty()) {
            return 0;
        }
        String[] lines = text.split("\n", -1);
        int targetLine = Math.max(1, pos.line);
        if (targetLine > lines.length) {
            return text.length();
        }
        int position = 0;
        for (int i = 0; i < targetLine - 1; i++) {
            position += lines[i].length() + 1;
        }
        int targetColumn = Math.max(1, pos.column);
        position += Math.min(targetColumn - 1, lines[targetLine - 1].length());
        return Math.min(position, text.length());
    }

    /**
     * 判断给定的0-based位置是否位于指定Range内
     *
     * @param position 0-based 位置
     * @param range JavaParser Range
     * @param text 完整文本内容
     * @return 如果位置在范围内（包含边界）返回 true，否则 false
     */
    public static boolean isInRange(int position, Range range, String text) {
        int start = getPosition(range.begin, text);
        int end = getPosition(range.end, text) + 1;
        return position >= start && position <= end;
    }

    /**
     * 判断给定偏移量是否位于编译单元中的字符串字面量内部
     *
     * @param compilationUnit 解析后的CompilationUnit
     * @param position 0-based偏移量
     * @param content 源文件完整文本
     * @return 若位置位于字符串字面量内部返回true
     */
    public static boolean isPositionInString(CompilationUnit compilationUnit, int position, String content) {
        AtomicBoolean result = new AtomicBoolean(false);
        compilationUnit.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(StringLiteralExpr n, Void arg) {
                Range range = n.getRange().orElse(null);
                if (range != null) {
                    int start = getPosition(range.begin, content) + 1;
                    int end = getPosition(range.end, content) - 1;
                    if (position >= start && position <= end) {
                        result.set(true);
                        return;
                    }
                }
                super.visit(n, arg);
            }
        }, null);
        return result.get();
    }

    /**
     * 规范化类型名
     *
     * @param typeName 原始类型名
     * @return 规范化后的类型名
     */
    public static String normalizeTypeName(String typeName) {
        return typeName.replace("final ", "").replace("...", "[]").trim();
    }

    /**
     * 计算类型名中数组维度的数量(通过末尾的 [] 连续出现判断)
     *
     * @param typeName 类型名
     * @return 数组维度数
     */
    public static int countArrayDimensions(String typeName) {
        String normalizedTypeName = normalizeTypeName(typeName);
        int count = 0;
        while (normalizedTypeName.endsWith("[]")) {
            count++;
            normalizedTypeName = normalizedTypeName.substring(0, normalizedTypeName.length() - 2).trim();
        }
        return count;
    }

    /**
     * 去除类型名末尾的数组后缀([]), 并返回基类型名
     *
     * @param typeName 原始类型名
     * @return 去除数组后缀后的类型名
     */
    public static String stripArraySuffix(String typeName) {
        String normalizedTypeName = normalizeTypeName(typeName);
        while (normalizedTypeName.endsWith("[]")) {
            normalizedTypeName = normalizedTypeName.substring(0, normalizedTypeName.length() - 2).trim();
        }
        return normalizedTypeName;
    }

    /**
     * 擦除泛型类型参数
     *
     * @param typeName 含泛型参数的类型名
     * @return 擦除泛型参数后的类型名
     */
    public static String eraseTypeArguments(String typeName) {
        StringBuilder builder = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < typeName.length(); i++) {
            char ch = typeName.charAt(i);
            if (ch == '<') {
                depth++;
                continue;
            }
            if (ch == '>') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth == 0) {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    public static String[] buildTypePath(CompilationUnit unit, TypeDeclaration<?> declaration) {
        String[] packagePath = unit.getPackageDeclaration().map(pkg -> pkg.getNameAsString().split("\\."))
            .orElse(new String[0]);
        List<String> typeNameList = new ArrayList<>();
        Optional<TypeDeclaration> current = Optional.of(declaration);
        while (current.isPresent()) {
            typeNameList.add(current.get().getNameAsString());
            current = current.get().findAncestor(TypeDeclaration.class);
        }
        Collections.reverse(typeNameList);
        return ArrayUtil.addAll(packagePath, typeNameList.toArray(new String[0]));
    }

    public static IndexPoint resolveNestedPoint(IndexPoint owner, String name) {
        if (owner == null || name == null || owner.getIndex() == null) {
            return null;
        }
        return owner.getIndex().getIndexList().stream()
            .filter(point -> Objects.equals(point.getName(), name))
            .filter(point -> isSameType(point.getEnclosingType(), owner))
            .findFirst().orElse(null);
    }

    private static IndexPoint resolveNestedPath(IndexPoint owner, String[] nestedPath, int offset) {
        IndexPoint current = owner;
        for (int i = offset; i < nestedPath.length; i++) {
            current = resolveNestedPoint(current, nestedPath[i]);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static IndexPoint resolveDeclaredType(Index index, IndexPoint source, String typeName, CompilationUnit unit) {
        if (typeName.contains(".")) {
            String[] path = typeName.split("\\.");
            if (source != null && source.getPkg() != null && source.getPkg().getModule() != null) {
                IndexPoint point = resolvePointByPath(index, source.getPkg().getModule(), path);
                if (point != null) {
                    return point;
                }
            }
            Module module = resolveModuleByPath(index, path);
            if (module != null) {
                IndexPoint point = module.getPoint(path);
                if (point != null) {
                    return point;
                }
            }
            if (source != null) {
                IndexPoint owner = resolvePointByName(index, source, path[0], unit);
                IndexPoint nestedPoint = resolveNestedPath(owner, path, 1);
                if (nestedPoint != null) {
                    return nestedPoint;
                }
                if (source.getPkg() != null) {
                    String[] pkgPath = source.getPkg().getPath();
                    String[] samePackagePath = ArrayUtil.addAll(pkgPath != null ? pkgPath : new String[0], path);
                    if (source.getPkg().getModule() != null) {
                        IndexPoint samePackagePoint = resolvePointByPath(index, source.getPkg().getModule(), samePackagePath);
                        if (samePackagePoint != null) {
                            return samePackagePoint;
                        }
                    }
                }
            }
        }
        return resolvePointByName(index, source, typeName, unit);
    }
}
