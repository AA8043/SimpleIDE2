package org.a8043.simpleIDE.project.index;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.ZipUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONSupport;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.modules.ModuleRequiresDirective;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.a8043.simpleIDE.Main;
import org.a8043.simpleIDE.project.ProjectEditor;
import org.a8043.simpleIDE.project.ProjectModule;
import org.a8043.simpleIDE.project.buildTool.Dependency;
import org.a8043.simpleIDE.util.JavaUtil;
import org.a8043.simpleIDE.util.Util;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Slf4j
public class Index extends JSONSupport implements Closeable {
    @Getter
    private final ProjectEditor editor;
    @Getter
    private final List<Module> moduleList = new CopyOnWriteArrayList<>(List.of(
        new Module(this), new Module(this)));
    @Getter
    private final List<IndexPoint> indexList = new CopyOnWriteArrayList<>();
    private ZipFile standardLibraryZip;
    private final Map<Dependency, ZipFile> dependencyZipMap = new HashMap<>();
    @Getter
    private final Map<String, IndexPoint> basicTypeMap;

    public Index(ProjectEditor editor) {
        this.editor = editor;
        Package basicTypePkg = moduleList.get(1).getPackageList().getFirst();
        basicTypeMap = Map.of(
            "byte", new IndexPoint("byte", basicTypePkg, null),
            "short", new IndexPoint("short", basicTypePkg, null),
            "int", new IndexPoint("int", basicTypePkg, null),
            "long", new IndexPoint("long", basicTypePkg, null),
            "float", new IndexPoint("float", basicTypePkg, null),
            "double", new IndexPoint("double", basicTypePkg, null),
            "char", new IndexPoint("char", basicTypePkg, null),
            "boolean", new IndexPoint("boolean", basicTypePkg, null),
            "void", new IndexPoint("void", basicTypePkg, null)
        );
        indexList.addAll(basicTypeMap.values());
    }

    public Module getModule(String name) {
        return moduleList.stream().filter(m -> m.getProjectModule() != null &&
                                               Objects.equals(m.getProjectModule().getName(), name))
            .findFirst().orElse(null);
    }

    public CompilationUnit getCompilationUnit(IndexPoint point) {
        if (point == null) {
            return null;
        }

        Module module = point.getPkg().getModule();
        StringBuilder pathBuilder = new StringBuilder();
        pathBuilder.append(module.getProjectModule().getName()).append("/");
        for (String aPath : point.getPkg().getPath()) {
            pathBuilder.append(aPath).append("/");
        }
        pathBuilder.append(point.getName()).append(".java");
        String pathString = pathBuilder.toString();

        ZipFile zipFile;
        ZipEntry zipEntry;
        if ((zipEntry = (zipFile = standardLibraryZip).getEntry(pathString)) == null) {
            List<Dependency> equalsModuleDependencyList = new ArrayList<>();
            dependencyZipMap.forEach((dependency, zip) -> {
                if (dependency.getModuleName().equals(module.getProjectModule().getName())) {
                    equalsModuleDependencyList.add(dependency);
                }
            });
            if (equalsModuleDependencyList.isEmpty()) {
                return null;
            }
            Dependency dependency = equalsModuleDependencyList.getFirst();
            // TODO: 获取类
        }

        String content = IoUtil.readUtf8(ZipUtil.getStream(zipFile, zipEntry));
        ParseResult<CompilationUnit> result = editor.getJavaParser().parse(content);
        return result.getResult().orElse(null);
    }

    public IndexPoint index(Module module, String[] path, String content) {
        List<MethodIndexTemp> methodTempList = new ArrayList<>();
        List<FieldIndexTemp> fieldTempList = new ArrayList<>();
        indexOne(module, path, content, methodTempList, fieldTempList);
        indexMethodAndField(methodTempList, fieldTempList);
        return module.getPoint(path);
    }

    public IndexPoint index(Package pkg, String name, String content) {
        return index(pkg.getModule(), ArrayUtil.addAll(pkg.getPath(), new String[]{name}), content);
    }

    private void indexOne(Module module, String[] path, String content,
                          List<MethodIndexTemp> methodTempList, List<FieldIndexTemp> fieldTempList) {
        IndexPoint old = module.getPoint(path);
        if (old != null) {
            indexList.remove(old);
        }

        ParseResult<CompilationUnit> result = editor.getJavaParser().parse(content);
        result.getResult().ifPresentOrElse(unit -> unit.getTypes().forEach(type -> {
            // TODO: 内部类
            IndexPoint point = new IndexPoint(type.getNameAsString(),
                module.getOrCreatePackage(ArrayUtil.sub(path, 0, path.length - 1)), null);
            indexList.add(point);
            type.getMethods().forEach(method -> {
                Map<String, IncompleteType> parameterMap = new HashMap<>();
                method.getParameters().forEach(parameter -> parameterMap.put(parameter.getNameAsString(),
                    createIncompleteType(point, unit, parameter.getType().asString())));
                String typeString = method.getType().asString();
                methodTempList.add(new MethodIndexTemp(point, method.getNameAsString(),
                    Access.fromJavaParser(method.getAccessSpecifier()), method.isStatic(),
                    createIncompleteType(point, unit, switch (typeString.charAt(typeString.length() - 1)) {
                        case ']' -> typeString.substring(0, typeString.length() - 2);
                        case '.' -> typeString.substring(0, typeString.length() - 3);
                        default -> typeString;
                    }), parameterMap));
            });
            type.getFields().forEach(field -> field.getVariables().forEach(variable ->
                fieldTempList.add(new FieldIndexTemp(point, variable.getNameAsString(),
                    Access.fromJavaParser(field.getAccessSpecifier()), field.isStatic(),
                    createIncompleteType(point, unit, field.getElementType().asString())))));
        }), () -> indexList.add(old));
    }

    private void indexMethodAndField(List<MethodIndexTemp> methodTempList, List<FieldIndexTemp> fieldTempList) {
        methodTempList.forEach(temp -> {
            IndexPoint in = temp.getIn();
            Map<String, IndexPoint> parameterMap = new HashMap<>();
            temp.getParameterMap().forEach((name, type) -> {
                type.full(in);
                Module module = type.getModule();
                parameterMap.put(name, module != null ? module.getPoint(type.getFull()) : null);
            });
            IncompleteType returnType = temp.getReturnType();
            String[] full = returnType.full(in);
            Module returnTypeModule = returnType.getModule();
            IndexPoint returnTypePoint = returnTypeModule != null ? returnTypeModule.getPoint(full) : null;
            in.getMethodList().add(new MethodSignature(temp.getName(), temp.getAccess(), temp.isStatic(),
                returnTypePoint, parameterMap));
        });
        fieldTempList.forEach(temp -> {
            IndexPoint in = temp.getIn();
            IncompleteType type = temp.getType();
            String[] full = type.full(in);
            Module module = type.getModule();
            in.getFieldList().add(new FieldSignature(temp.getName(), temp.getAccess(), temp.isStatic(),
                module != null ? module.getPoint(full) : null));
        });
    }

    private Module getOrCreateModule(ProjectModule projectModule) {
        Module module = getModule(projectModule.getName());
        if (module == null) {
            moduleList.add(module = new Module(projectModule, this));
        }
        return module;
    }

    public ZipFile getStandardLibraryZip() {
        return standardLibraryZip != null ? standardLibraryZip :
            (standardLibraryZip = ZipUtil.toZipFile(editor.getJdk().getSrcFile(), StandardCharsets.UTF_8));
    }

    public void indexAll(Consumer<Integer> afterStatistics, Runnable afterIndexOne,
                         Runnable afterIndexAll, Consumer<Exception> onException) {
        try {
            getStandardLibraryZip();
        } catch (Exception e) {
            onException.accept(e);
        }

        Set<Module> moduleSet = new HashSet<>();
        editor.getProjectModel().getModuleList().forEach(module -> moduleSet.add(getOrCreateModule(module)));
        moduleSet.forEach(module -> {
            try {
                String moduleInfoContent;
                switch (module.getProjectModule().getLocation()) {
                    case JDK -> moduleInfoContent = IoUtil.readUtf8(standardLibraryZip.getInputStream(
                        standardLibraryZip.getEntry(module.getProjectModule().getName() + "/module-info.java")));
                    default -> {
                        // TODO: 使用其他方法获取模块信息
                        return;
                    }
                }

                ParseResult<CompilationUnit> result = editor.getJavaParser().parse(moduleInfoContent);
                result.getResult().flatMap(CompilationUnit::getModule).ifPresent(moduleDecl ->
                    moduleDecl.getDirectives().forEach(directive -> {
                        switch (directive) {
                            case ModuleRequiresDirective requiresDirective -> {
                                Module requireModule = getModule(requiresDirective.getNameAsString());
                                if (requireModule != null) {
                                    module.addRequire(requireModule);
                                }
                            }
                            default -> {
                            }
                        }
                    }));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        editor.getProjectModel().getDependencyList().forEach(dependency ->
            dependencyZipMap.put(dependency, dependency.getSourceZip().waitFor()));

        List<MethodIndexTemp> methodTempList = new ArrayList<>();
        List<FieldIndexTemp> fieldTempList = new ArrayList<>();
        try {
            List<NeedIndex> needList = new ArrayList<>();
            standardLibraryZip.stream().forEach(entry -> {
                if (!entry.isDirectory() && entry.getName().endsWith(".java")
                    && entry.getName().startsWith(editor.getConfig().getOnlyIndexStartsWith())) {
                    String[] all = entry.getName().substring(0,
                        entry.getName().length() - ".java".length()).split("/");
                    String moduleName = all[0];
                    String[] path = ArrayUtil.sub(all, 1, all.length);
                    needList.add(new NeedIndex(moduleName, path, entry));
                }
            });
            editor.getProjectModel().getModuleList().forEach(module -> module.getSrcDirList().forEach(srcDir -> {
                String srcPath = srcDir.getAbsolutePath().replace("\\", "/");
                FileUtil.walkFiles(srcDir, file -> {
                    if (file.getName().endsWith(".java")) {
                        String filePath = file.getAbsolutePath().replace("\\", "/");
                        String relativePath = filePath.substring(srcPath.length() + 1);
                        String[] path = relativePath.substring(0,
                            relativePath.length() - ".java".length()).split("/");
                        needList.add(new NeedIndex(module.getName(), path, file));
                    }
                });
            }));
            int count = needList.size();
            log.debug("需要索引的数量: {}", count);
            afterStatistics.accept(count);

            Util.parallelForEach(needList, need -> {
                String content;
                if (need.getContent() instanceof ZipEntry entry) {
                    InputStream inputStream = ZipUtil.getStream(standardLibraryZip, entry);
                    byte[] bytes = new byte[0];
                    try {
                        bytes = inputStream.readAllBytes();
                    } catch (IOException e) {
                        onException.accept(e);
                    }
                    content = new String(bytes, StandardCharsets.UTF_8);
                } else if (need.getContent() instanceof File file) {
                    content = FileUtil.readUtf8String(file);
                } else {
                    throw new RuntimeException();
                }

                indexOne(getModule(need.getModuleName()), need.getPath(), content, methodTempList, fieldTempList);
                afterIndexOne.run();
            }, Main.instance.getSettings().getIndexThreadCount());
        } catch (Exception e) {
            onException.accept(e);
        }
        afterIndexAll.run();
        indexMethodAndField(methodTempList, fieldTempList);
    }

    @SneakyThrows
    @Override
    public void close() {
        standardLibraryZip.close();
        dependencyZipMap.values().forEach(zipFile -> {
            try {
                zipFile.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Value
    private static class NeedIndex {
        String moduleName;
        String[] path;
        Object content;
    }

    @Value
    private static class MethodIndexTemp {
        IndexPoint in;
        String name;
        Access access;
        boolean isStatic;
        IncompleteType returnType;
        Map<String, IncompleteType> parameterMap;
    }

    @Value
    private static class FieldIndexTemp {
        IndexPoint in;
        String name;
        Access access;
        boolean isStatic;
        IncompleteType type;
    }

    @Getter
    @Setter
    private class IncompleteType {
        private final IndexPoint source;
        private final boolean isIncomplete;
        private final String[] path;
        private String[] full;

        private IncompleteType(IndexPoint source, boolean isIncomplete, String[] path) {
            this.source = source;
            this.isIncomplete = isIncomplete;
            this.path = path;
        }

        public String[] full(IndexPoint source) {
            if (full != null) {
                return full;
            }
            if (isIncomplete) {
                return full = path;
            }
            String[] full = JavaUtil.getClassAbsolutePath(Index.this, source,
                StrUtil.join(".", (Object[]) path), null);
            return this.full = full != null ? full : path;
        }

        public Module getModule() {
            if (basicTypeMap.containsKey(ArrayUtil.join(path, "."))) {
                return moduleList.get(1);
            }
            return JavaUtil.resolveModuleByPath(Index.this, source, full);
        }
    }

    private IncompleteType createIncompleteType(IndexPoint source, CompilationUnit unit, String original) {
        String[] full = JavaUtil.getClassAbsolutePath(this, source, original, unit);
        boolean isIncomplete = full != null;
        return new IncompleteType(source, isIncomplete, isIncomplete ? full : new String[]{original});
    }

    @Override
    public JSONObject toJSON() {
        return new JSONObject().set("moduleList", moduleList).set("indexList", indexList);
    }

    public static Index convert(ProjectEditor editor, JSONObject json) {
        Index index = new Index(editor);

        List<ProjectModule> projectModuleList = editor.getProjectModel().getModuleList();
        Map<Module, List<String>> requireNameListMap = new HashMap<>();
        json.getJSONArray("moduleList").forEach(moduleJsonObject -> {
            JSONObject moduleJson = (JSONObject) moduleJsonObject;
            String name = moduleJson.getStr("name");
            boolean isNormal = !name.equals("<unnamed>") && !name.equals("<basic>");
            Module module = isNormal ? new Module(projectModuleList.stream().filter(m -> m.getName().equals(name))
                                                  .findFirst().orElseThrow(), index) : new Module(index);
            index.getModuleList().add(module);
            moduleJson.getJSONArray("packageList").forEach(nameObject ->
                module.getOrCreatePackage(((String) nameObject).split("\\.")));
            if (isNormal) {
                requireNameListMap.put(module, moduleJson.getJSONArray("requireList").toList(String.class));
            }
        });
        requireNameListMap.forEach((module, requireNameList) -> requireNameList.forEach(requireName -> {
            Module requireModule = index.getModule(requireName);
            if (requireModule != null) {
                module.addRequire(requireModule);
            }
        }));

        Map<IndexPoint, JSONObject> pointParentJsonMap = new HashMap<>();
        json.getJSONArray("indexList").forEach(indexJsonObject -> {
            JSONObject indexJson = (JSONObject) indexJsonObject;
            String[] path = indexJson.getStr("path").split("\\.");
            String moduleName = indexJson.getStr("moduleName");
            Module module;
            if (moduleName.equals("<basic>")) {
                module = index.getModuleList().get(1);
            } else if (moduleName.equals("<unnamed>")) {
                module = index.getModuleList().getFirst();
            } else {
                module = index.getModule(moduleName);
            }
            IndexPoint point = new IndexPoint(path[path.length - 1],
                module.getPackage(ArrayUtil.sub(path, 0, path.length - 1)), null);
            JSONObject parent = indexJson.getJSONObject("parent");
            if (parent != null) {
                pointParentJsonMap.put(point, parent);
            }
        });
        pointParentJsonMap.forEach((point, parentName) -> {
            IndexPoint parent = index.getIndexList().stream().filter(p ->
                p.getName().equals(parentName.getStr("name")) &&
                p.getPkg().getFullName().equals(parentName.getStr("package")) &&
                p.getPkg().getModule().getProjectModule().getName().equals(parentName.getStr("moduleName"))
            ).findFirst().orElseThrow();
            point.setParent(parent);
        });

        index.getStandardLibraryZip();
        return index;
    }
}
