package org.a8043.simpleIDE.util;

import cn.hutool.core.io.watch.SimpleWatcher;
import cn.hutool.core.io.watch.WatchMonitor;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import lombok.SneakyThrows;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GitUtil {
    private static final List<Git> GIT_LIST = new ArrayList<>();

    public static Git getGit(File file) {
        return GIT_LIST.stream().filter(git -> git.getRepository().getWorkTree().equals(file)).findFirst().orElse(null);
    }

    @SneakyThrows
    public static void open(File file) {
        Git git = Git.open(file);
        GIT_LIST.add(git);
    }

    private static final Map<File, ObjectProperty<FileStatus>> FILE_STATUS_MAP = new HashMap<>();

    @SneakyThrows
    public static ObjectProperty<FileStatus> getFileStatus(File file) {
        if (FILE_STATUS_MAP.containsKey(file)) {
            return FILE_STATUS_MAP.get(file);
        } else {
            Git git = findGit(file);
            if (git == null) {
                return new SimpleObjectProperty<>(FileStatus.NORMAL);
            }
            ObjectProperty<FileStatus> statusProperty = new SimpleObjectProperty<>();
            FILE_STATUS_MAP.put(file, statusProperty);
            Runnable onChange = () -> {
                String relativePath = FileUtil.getRelativePath(git.getRepository().getWorkTree(), file)
                    .replace("\\", "/");
                Status status;
                try {
                    status = git.status().addPath(relativePath).call();
                } catch (GitAPIException e) {
                    throw new RuntimeException(e);
                }
                if (status.getAdded().contains(relativePath)) {
                    statusProperty.set(FileStatus.ADDED);
                } else if (status.getChanged().contains(relativePath)) {
                    statusProperty.set(FileStatus.CHANGED);
                } else if (status.getUntracked().contains(relativePath)) {
                    statusProperty.set(FileStatus.UNTRACKED);
                } else if (status.getIgnoredNotInIndex().contains(relativePath)) {
                    statusProperty.set(FileStatus.IGNORED);
                } else {
                    statusProperty.set(FileStatus.NORMAL);
                }
            };
            FileUtil.watch(file, new SimpleWatcher() {
                @Override
                public void onModify(WatchEvent<?> event, Path currentPath) {
                    onChange.run();
                }
            }, WatchMonitor.ENTRY_MODIFY);
            onChange.run();
            return statusProperty;
        }
    }

    private static Git findGit(File file) {
        return GIT_LIST.stream().filter(git -> file.getAbsolutePath().startsWith(
            git.getRepository().getWorkTree().getAbsolutePath())).findFirst().orElse(null);
    }

    public static void close() {
        GIT_LIST.forEach(Git::close);
    }

    public enum FileStatus {
        NORMAL, CHANGED, ADDED, UNTRACKED, IGNORED
    }
}
