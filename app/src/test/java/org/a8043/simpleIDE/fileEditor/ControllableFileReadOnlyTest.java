package org.a8043.simpleIDE.fileEditor;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;

public class ControllableFileReadOnlyTest {
    @Test
    public void writeDoesNothingWhenReadOnly() throws Exception {
        File tempFile = File.createTempFile("simpleide", ".txt");
        tempFile.deleteOnExit();
        Files.writeString(tempFile.toPath(), "original");

        ControllableFile controllableFile = new ControllableFile(tempFile, "changed", true);
        controllableFile.write();

        assertEquals("original", Files.readString(tempFile.toPath()));
    }
}
