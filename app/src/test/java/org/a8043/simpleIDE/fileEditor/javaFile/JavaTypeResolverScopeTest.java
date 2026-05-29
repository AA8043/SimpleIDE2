package org.a8043.simpleIDE.fileEditor.javaFile;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.a8043.simpleIDE.project.ProjectModule;
import org.a8043.simpleIDE.project.index.Index;
import org.a8043.simpleIDE.project.index.IndexPoint;
import org.a8043.simpleIDE.project.index.Module;
import org.a8043.simpleIDE.project.index.Package;
import org.a8043.simpleIDE.util.JavaUtil;
import org.junit.Test;

import java.io.File;
import java.util.List;

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
}
