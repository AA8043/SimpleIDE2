package org.a8043.simpleIDE.project.index;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@AllArgsConstructor
@Getter
@Setter
public class MethodSignature {
    private String name;
    private Access access;
    private boolean isStatic;
    private IndexPoint returnType;
    private Map<String, IndexPoint> parameterMap;
}
