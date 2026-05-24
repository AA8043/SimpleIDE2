package org.a8043.simpleIDE.fileEditor.javaFile;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
}
