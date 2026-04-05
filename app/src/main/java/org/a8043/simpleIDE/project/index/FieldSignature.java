package org.a8043.simpleIDE.project.index;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@AllArgsConstructor
@Getter
@Setter
@ToString
public class FieldSignature {
    private String name;
    private Access access;
    private boolean isStatic;
    private IndexPoint type;
}
