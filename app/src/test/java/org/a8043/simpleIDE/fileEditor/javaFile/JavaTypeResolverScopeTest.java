package org.a8043.simpleIDE.fileEditor.javaFile;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.a8043.simpleIDE.project.ProjectEditor;
import org.a8043.simpleIDE.project.ProjectModule;
import org.a8043.simpleIDE.project.index.*;
import org.a8043.simpleIDE.project.index.Module;
import org.a8043.simpleIDE.project.index.Package;
import org.a8043.simpleIDE.util.JavaUtil;
import org.junit.Test;
import sun.misc.Unsafe;

import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class JavaTypeResolverScopeTest {
    @Test
    public void resolvesSystemOutPrintlnScopeChain() {
        CompilationUnit unit = StaticJavaParser.parse("""
            class Demo {
                void test() {
                    System.out.println(1);
                }
            }
            """);
        MethodCallExpr methodCallExpr = unit.findFirst(MethodCallExpr.class).orElseThrow();
        JavaTypeResolver resolver = new JavaTypeResolver(null, null, null);

        List<Expression> scopeList = resolver.getScopeExpressionList(methodCallExpr);

        assertEquals(3, scopeList.size());
        assertTrue(scopeList.get(0).isNameExpr());
        assertEquals("System", scopeList.get(0).asNameExpr().getNameAsString());
        assertTrue(scopeList.get(1).isFieldAccessExpr());
        assertEquals("out", scopeList.get(1).asFieldAccessExpr().getNameAsString());
        assertTrue(scopeList.get(2).isMethodCallExpr());
        assertEquals("println", scopeList.get(2).asMethodCallExpr().getNameAsString());
    }

    @Test
    public void resolvesCurrentCompilationUnitTopLevelTypeByName() {
        CompilationUnit unit = StaticJavaParser.parse("""
            package demo;

            class Demo {
            }
            """);
        Index index = new Index(null);
        Module module = new Module(new ProjectModule("demo-module", ProjectModule.Location.PROJECT,
            List.of(), List.of(new File(".")), List.of(), List.of(), List.of()), index);
        Package pkg = new Package(module, module.getPackageList().getFirst(), "demo", index);
        IndexPoint source = new IndexPoint("Demo", pkg, null, index);

        IndexPoint resolved = JavaUtil.resolvePointByName(index, source, "Demo", unit);

        assertSame(source, resolved);
    }

    @Test
    public void resolvesNestedTypeFieldAccessExpression() throws Exception {
        CompilationUnit unit = StaticJavaParser.parse("""
            package demo;
            
            class Use {
                void test() {
                    Outer.Inner.create();
                }
            }
            """);
        Index index = new Index(null);
        Module module = new Module(new ProjectModule("demo-module", ProjectModule.Location.PROJECT,
            List.of(), List.of(new File(".")), List.of(), List.of(), List.of()), index);
        Package pkg = new Package(module, module.getPackageList().getFirst(), "demo", index);
        IndexPoint use = new IndexPoint("Use", pkg, null, index);
        IndexPoint outer = new IndexPoint("Outer", pkg, null, index);
        IndexPoint inner = new IndexPoint("Inner", pkg, null, index);
        setEnclosingType(inner, outer);
        inner.getMethodList().add(new MethodSignature("create", Access.PUBLIC, true, inner, Map.of(), List.of()));
        index.getIndexList().add(use);
        index.getIndexList().add(outer);
        index.getIndexList().add(inner);
        JavaFileState state = new JavaFileState(use);
        JavaTypeResolver resolver = new JavaTypeResolver(null, state, unit::toString);
        setEditor(resolver, index);
        MethodCallExpr call = unit.findFirst(MethodCallExpr.class).orElseThrow();

        IndexPoint resolvedScope = resolver.resolveExpressionType(call.getScope().orElseThrow(), unit);
        IndexPoint resolvedCall = resolver.resolveExpressionType(call, unit);

        assertSame(inner, resolvedScope);
        assertSame(inner, resolvedCall);
    }

    private void setEditor(JavaTypeResolver resolver, Index index) throws Exception {
        Field editorField = JavaTypeResolver.class.getDeclaredField("editor");
        editorField.setAccessible(true);
        editorField.set(resolver, newTestProjectEditor(index));
    }

    private TestProjectEditor newTestProjectEditor(Index index) throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        TestProjectEditor editor = (TestProjectEditor) unsafe.allocateInstance(TestProjectEditor.class);
        Field indexField = TestProjectEditor.class.getDeclaredField("index");
        indexField.setAccessible(true);
        indexField.set(editor, index);
        return editor;
    }

    private void setEnclosingType(IndexPoint inner, IndexPoint outer) throws Exception {
        Field enclosingTypeField = IndexPoint.class.getDeclaredField("enclosingType");
        enclosingTypeField.setAccessible(true);
        enclosingTypeField.set(inner, outer);
    }

    private static class TestProjectEditor extends ProjectEditor {
        private Index index;

        private TestProjectEditor() {
            super(null);
        }

        @Override
        public Index getIndex() {
            return index;
        }
    }
}
