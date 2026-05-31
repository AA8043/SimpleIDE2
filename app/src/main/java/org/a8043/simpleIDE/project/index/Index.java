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

/**
 * 索引
 */
@Slf4j
public class Index extends JSONSupport implements Closeable {
    @Getter
    private final ProjectEditor editor;
    @Getter
    private boolean isIndexed;
    /**
     * 模块列表<br>
     * 固定的模块:
     * <ol>
     *     <li>未命名模块</li>
     *     <li>基础类型模块</li>
     * </ol>
     */
    @Getter
    private final List<Module> moduleList = new CopyOnWriteArrayList<>(List.of(
        new Module(this), new Module(this)));
    @Getter
    private final List<IndexPoint> indexList = new CopyOnWriteArrayList<>();
    private ZipFile standardLibraryZip;
    @Getter
    private final Map<Dependency, ZipFile> dependencyZipMap = new HashMap<>();
    @Getter
    private final Map<String, IndexPoint> basicTypeMap;
    private final Map<String, IndexPoint> arrayTypeMap = new HashMap<>();
    private final Map<IndexPoint, IndexPoint> arrayComponentTypeMap = new IdentityHashMap<>();

    public Index(ProjectEditor editor) {
        this.editor = editor;
        Package basicTypePkg = moduleList.get(1).getPackageList().getFirst();
        basicTypeMap = Map.of(
            "byte", new IndexPoint("byte", basicTypePkg, null, this),
            "short", new IndexPoint("short", basicTypePkg, null, this),
            "int", new IndexPoint("int", basicTypePkg, null, this),
            "long", new IndexPoint("long", basicTypePkg, null, this),
            "float", new IndexPoint("float", basicTypePkg, null, this),
            "double", new IndexPoint("double", basicTypePkg, null, this),
            "char", new IndexPoint("char", basicTypePkg, null, this),
            "boolean", new IndexPoint("boolean", basicTypePkg, null, this),
            "void", new IndexPoint("void", basicTypePkg, null, this)
        );
        indexList.addAll(basicTypeMap.values());
    }

    public Module getModule(String name) {
        return moduleList.stream().filter(m -> m.getProjectModule() != null &&
                                               Objects.equals(m.getProjectModule().getName(), name))
            .findFirst().orElse(null);
    }

    public Module getModuleByCacheName(String name) {
        return moduleList.stream().filter(m -> Objects.equals(m.getCacheName(), name))
            .findFirst().orElse(null);
    }

    public synchronized IndexPoint getOrCreateArrayType(IndexPoint componentType) {
        if (componentType == null) {
            return null;
        }
        String key = buildArrayTypeKey(componentType);
        IndexPoint cached = arrayTypeMap.get(key);
        if (cached != null) {
            return cached;
        }

        IndexPoint arrayType = new IndexPoint(componentType.getName() + "[]", componentType.getPkg(),
            resolveJavaLangObjectType(), this);
        arrayType.getFieldList().add(new FieldSignature("length",
            Access.PUBLIC, false, basicTypeMap.get("int")));
        arrayTypeMap.put(key, arrayType);
        arrayComponentTypeMap.put(arrayType, componentType);
        return arrayType;
    }

    public boolean isArrayType(IndexPoint type) {
        return arrayComponentTypeMap.containsKey(type);
    }

    public IndexPoint getArrayComponentType(IndexPoint arrayType) {
        return arrayComponentTypeMap.get(arrayType);
    }

    public IndexPoint resolveSerializedType(String moduleName, String path) {
        if (path == null || path.isBlank()) {
            return null;
        }

        int arrayDepth = JavaUtil.countArrayDimensions(path);
        String componentPath = JavaUtil.stripArraySuffix(path);
        Module module = getModuleByCacheName(moduleName);
        IndexPoint componentType = null;
        if (module != null) {
            componentType = module.getPoint(componentPath.split("\\."));
        }
        if (componentType == null) {
            componentType = basicTypeMap.get(componentPath);
        }
        if (componentType == null) {
            Module fallbackModule = JavaUtil.resolveModuleByPath(this, componentPath.split("\\."));
            if (fallbackModule != null) {
                componentType = fallbackModule.getPoint(componentPath.split("\\."));
            }
        }
        if (componentType == null) {
            return null;
        }
        for (int i = 0; i < arrayDepth; i++) {
            componentType = getOrCreateArrayType(componentType);
        }
        return componentType;
    }

    public IndexPoint index(Module module, String[] path, String content) {
        indexList.removeIf(point -> point.getPkg().getModule() == module && ArrayUtil.equals(point.getPath(), path));
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
                module.getOrCreatePackage(ArrayUtil.sub(path, 0, path.length - 1)), null, this);
            indexList.add(point);
            type.getMethods().forEach(method -> {
                Map<String, IncompleteType> parameterMap = new LinkedHashMap<>();
                method.getParameters().forEach(parameter -> parameterMap.put(parameter.getNameAsString(),
                    createIncompleteType(point, unit, parameter.getType().asString())));
                methodTempList.add(new MethodIndexTemp(point, method.getNameAsString(),
                    Access.fromJavaParser(method.getAccessSpecifier()), method.isStatic(),
                    createIncompleteType(point, unit, method.getType().asString()), parameterMap));
            });
            type.getFields().forEach(field -> field.getVariables().forEach(variable ->
                fieldTempList.add(new FieldIndexTemp(point, variable.getNameAsString(),
                    Access.fromJavaParser(field.getAccessSpecifier()), field.isStatic(),
                    createIncompleteType(point, unit, variable.getType().asString())))));
        }), () -> indexList.add(old));
    }

    private void indexMethodAndField(List<MethodIndexTemp> methodTempList, List<FieldIndexTemp> fieldTempList) {
        methodTempList.forEach(temp -> {
            IndexPoint in = temp.getIn();
            Map<String, IndexPoint> parameterMap = new LinkedHashMap<>();
            List<IndexPoint> parameterTypeList = new ArrayList<>();
            temp.getParameterMap().forEach((name, type) -> {
                IndexPoint parameterType = type.getPoint(in);
                parameterMap.put(name, parameterType);
                parameterTypeList.add(parameterType);
            });
            in.getMethodList().add(new MethodSignature(temp.getName(), temp.getAccess(), temp.isStatic(),
                temp.getReturnType().getPoint(in), parameterMap, parameterTypeList));
        });
        fieldTempList.forEach(temp -> {
            IndexPoint in = temp.getIn();
            in.getFieldList().add(new FieldSignature(temp.getName(), temp.getAccess(), temp.isStatic(),
                temp.getType().getPoint(in)));
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
        if (editor.getIndexCacheFile().exists()) {
            fromJson(new JSONObject(FileUtil.readUtf8String(editor.getIndexCacheFile())));
            isIndexed = true;
            return;
        }

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
            dependencyZipMap.put(dependency, dependency.getSourceZip()));

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
            editor.getProjectModel().getModuleList().forEach(module -> {
                if (module.getLocation() == ProjectModule.Location.PROJECT) {
                    module.getSrcDirList().forEach(srcDir -> {
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
                    });
                } else if (module.getLocation() == ProjectModule.Location.DEPENDENCY) {
                    // TODO: 获取依赖的类
                }
            });
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
        isIndexed = true;
    }

    public void reindexAll(Consumer<Integer> afterStatistics, Runnable afterIndexOne,
                           Runnable afterIndexAll, Consumer<Exception> onException) {
        moduleList.clear();
        moduleList.addAll(List.of(new Module(this), new Module(this)));
        indexList.clear();
        dependencyZipMap.clear();
        arrayTypeMap.clear();
        arrayComponentTypeMap.clear();
        indexAll(afterStatistics, afterIndexOne, afterIndexAll, onException);
    }

    @SneakyThrows
    @Override
    public void close() {
        standardLibraryZip.close();
        dependencyZipMap.values().forEach(zipFile -> {
            if (zipFile != null) {
                try {
                    zipFile.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
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

    private class IncompleteType {
        private final IndexPoint source;
        private final String typeName;
        private final CompilationUnit unit;
        private IndexPoint point;

        private IncompleteType(IndexPoint source, String typeName, CompilationUnit unit) {
            this.source = source;
            this.typeName = typeName;
            this.unit = unit;
        }

        public IndexPoint getPoint(IndexPoint source) {
            if (point != null) {
                return point;
            }
            return point = JavaUtil.resolveType(Index.this, source, typeName, unit);
        }
    }

    private IncompleteType createIncompleteType(IndexPoint source, CompilationUnit unit, String original) {
        return new IncompleteType(source, original, unit);
    }

    private File resolveProjectSourceFile(IndexPoint point) {
        ProjectModule projectModule = point.getPkg().getModule().getProjectModule();
        if (projectModule == null) {
            return null;
        }
        String relativePath = StrUtil.join("/", (Object[]) point.getPath()) + ".java";
        for (File srcDir : projectModule.getSrcDirList()) {
            File candidate = new File(srcDir, relativePath);
            if (candidate.exists()) {
                return candidate;
            }
        }
        return null;
    }

    @SneakyThrows
    public IndexPoint resolveIndexPointByFile(File file) {
        if (file == null) {
            return null;
        }

        File canonicalFile = file.getCanonicalFile();
        for (File srcDir : editor.getProjectModel().getSrcDirList()) {
            File canonicalSrcDir = srcDir.getCanonicalFile();
            if (!canonicalFile.toPath().startsWith(canonicalSrcDir.toPath())) {
                continue;
            }
            String relativePath = canonicalSrcDir.toPath().relativize(canonicalFile.toPath()).toString()
                .replace("\\", "/");
            if (!relativePath.endsWith(".java")) {
                continue;
            }
            String[] path = relativePath.substring(0, relativePath.length() - ".java".length()).split("/");
            Module module = JavaUtil.resolveModuleByPath(this, path);
            if (module == null) {
                continue;
            }
            return module.getPoint(path);
        }

        return null;
    }

    @Override
    public JSONObject toJSON() {
        return new JSONObject().set("moduleList", moduleList).set("indexList", indexList.stream()
            .filter(point -> !basicTypeMap.containsValue(point)).toList());
    }

    public void fromJson(JSONObject json) {
        List<ProjectModule> projectModuleList = editor.getProjectModel().getModuleList();
        Map<Module, List<String>> requireNameListMap = new HashMap<>();
        json.getJSONArray("moduleList").forEach(moduleJsonObject -> {
            JSONObject moduleJson = (JSONObject) moduleJsonObject;
            String name = moduleJson.getStr("name");
            boolean isNormal = !name.equals("<unnamed>") && !name.equals("<basic>");
            Module module = isNormal ? new Module(projectModuleList.stream().filter(m -> m.getName().equals(name))
                .findFirst().orElseThrow(), this) : switch (name) {
                case "<unnamed>" -> moduleList.getFirst();
                case "<basic>" -> moduleList.get(1);
                default -> throw new RuntimeException();
            };
            if (isNormal) {
                moduleList.add(module);
            }
            moduleJson.getJSONArray("packageList").forEach(nameObject ->
                module.getOrCreatePackage(((String) nameObject).split("\\.")));
            if (isNormal) {
                requireNameListMap.put(module, moduleJson.getJSONArray("requireList").toList(String.class));
            }
        });
        requireNameListMap.forEach((module, requireNameList) -> requireNameList.forEach(requireName -> {
            Module requireModule = getModule(requireName);
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
                module = moduleList.get(1);
            } else if (moduleName.equals("<unnamed>")) {
                module = moduleList.getFirst();
            } else {
                module = getModule(moduleName);
            }
            Package pkg;
            String[] pkgPath = ArrayUtil.sub(path, 0, path.length - 1);
            if (pkgPath.length == 0) {
                pkg = module.getPackageList().getFirst();
            } else {
                pkg = module.getPackage(pkgPath);
            }
            IndexPoint point = new IndexPoint(path[path.length - 1], pkg, null, this);
            indexList.add(point);
            JSONObject parent = indexJson.getJSONObject("parent");
            if (parent != null) {
                pointParentJsonMap.put(point, parent);
            }
        });
        json.getJSONArray("indexList").forEach(indexJsonObject -> {
            JSONObject indexJson = (JSONObject) indexJsonObject;
            IndexPoint point = getModuleByCacheName(indexJson.getStr("moduleName"))
                .getPoint(indexJson.getStr("path").split("\\."));
            indexJson.getJSONArray("methodList").forEach(methodJsonObject -> {
                JSONObject methodJson = (JSONObject) methodJsonObject;
                Map<String, IndexPoint> parameterMap = new LinkedHashMap<>();
                methodJson.getJSONObject("parameterMap").forEach((paramName, paramType) -> {
                    JSONObject typeJson = (JSONObject) paramType;
                    if (typeJson.isEmpty()) {
                        return;
                    }
                    parameterMap.put(paramName,
                        resolveSerializedType(typeJson.getStr("moduleName"), typeJson.getStr("path")));
                });
                JSONObject returnTypeJson = methodJson.getJSONObject("returnType");
                if (returnTypeJson.isEmpty()) {
                    return;
                }
                List<IndexPoint> parameterTypeList = new ArrayList<>();
                if (methodJson.containsKey("parameterTypeList")) {
                    methodJson.getJSONArray("parameterTypeList").forEach(parameterTypeObject -> {
                        JSONObject typeJson = (JSONObject) parameterTypeObject;
                        if (typeJson.isEmpty()) {
                            parameterTypeList.add(null);
                            return;
                        }
                        parameterTypeList.add(
                            resolveSerializedType(typeJson.getStr("moduleName"), typeJson.getStr("path")));
                    });
                } else {
                    parameterTypeList.addAll(parameterMap.values());
                }
                point.getMethodList().add(new MethodSignature(methodJson.getStr("name"),
                    Access.valueOf(methodJson.getStr("access")), methodJson.getBool("isStatic"),
                    resolveSerializedType(returnTypeJson.getStr("moduleName"), returnTypeJson.getStr("path")),
                    parameterMap, parameterTypeList));
            });
            indexJson.getJSONArray("fieldList").forEach(fieldJsonObject -> {
                JSONObject fieldJson = (JSONObject) fieldJsonObject;
                JSONObject typeJson = fieldJson.getJSONObject("type");
                ;
                if (typeJson.isEmpty()) {
                    return;
                }
                point.getFieldList().add(new FieldSignature(fieldJson.getStr("name"),
                    Access.valueOf(fieldJson.getStr("access")),
                    fieldJson.getBool("isStatic"),
                    resolveSerializedType(typeJson.getStr("moduleName"), typeJson.getStr("path"))));
            });
        });
        pointParentJsonMap.forEach((point, parentName) -> {
            IndexPoint parent = indexList.stream().filter(p ->
                p.getName().equals(parentName.getStr("name")) &&
                p.getPkg().getFullName().equals(parentName.getStr("package")) &&
                p.getPkg().getModule().getProjectModule().getName().equals(parentName.getStr("moduleName"))
            ).findFirst().orElseThrow();
            point.setParent(parent);
        });

        getStandardLibraryZip();
    }

    private String buildArrayTypeKey(IndexPoint componentType) {
        return componentType.getPkg().getModule().getCacheName() + ":" +
               StrUtil.join(".", (Object[]) componentType.getPath()) + "[]";
    }

    private IndexPoint resolveJavaLangObjectType() {
        Module javaBase = getModule("java.base");
        return javaBase != null ? javaBase.getPoint(new String[]{"java", "lang", "Object"}) : null;
    }
}
