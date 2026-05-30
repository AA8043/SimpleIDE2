package org.a8043.simpleIDE.util;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.io.LineHandler;
import cn.hutool.core.io.watch.SimpleWatcher;
import cn.hutool.core.io.watch.WatchMonitor;
import cn.hutool.core.util.RuntimeUtil;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import lombok.*;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.URIish;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.util.*;
import java.util.function.Consumer;

/**
 * Git工具类
 */
public class GitUtil {
    private static final List<String> DEFAULT_ARG_LIST = List.of("git",
        "-c", "credential.helper=", "-c", "core.quotepath=false", "-c", "log.showSignature=false");
    private static final List<Git> GIT_LIST = new ArrayList<>();

    /**
     * 打开Git仓库
     * @param file 目录
     */
    @SneakyThrows
    public static void open(File file) {
        Git git = Git.open(file);
        GIT_LIST.add(git);
    }

    /**
     * 初始化Git仓库
     * @param dir 目录
     */
    @SneakyThrows
    public static void init(File dir) {
        List<String> argList = new ArrayList<>(DEFAULT_ARG_LIST);
        argList.add("init");
        RuntimeUtil.exec(null, dir, argList.toArray(new String[0]));
    }

    private static final Map<File, ObjectProperty<FileStatus>> FILE_STATUS_MAP = new HashMap<>();

    /**
     * 获取文件状态
     * @param file 文件
     * @return 文件状态
     */
    @SneakyThrows
    public static ObjectProperty<FileStatus> getFileStatus(File file) {
        if (FILE_STATUS_MAP.containsKey(file)) {
            return FILE_STATUS_MAP.get(file);
        } else {
            ObjectProperty<FileStatus> statusProperty = new SimpleObjectProperty<>();
            FILE_STATUS_MAP.put(file, statusProperty);
            org.a8043.simpleIDE.util.FileUtil.watch(file, new SimpleWatcher() {
                @Override
                public void onModify(WatchEvent<?> event, Path currentPath) {
                    refreshFileStatus(file);
                }
            }, WatchMonitor.ENTRY_MODIFY);
            refreshFileStatus(file);
            return statusProperty;
        }
    }

    /**
     * 刷新文件状态
     * @param file 文件
     */
    @SneakyThrows
    private static void refreshFileStatus(File file) {
        Git git = findGit(file);
        if (git == null) {
            return;
        }
        ObjectProperty<FileStatus> statusProperty = FILE_STATUS_MAP.get(file);
        String relativePath = org.a8043.simpleIDE.util.FileUtil.getRelativePath(git.getRepository().getWorkTree(), file);
        Status status = git.status().addPath(relativePath).call();
        if (status.getAdded().contains(relativePath)) {
            statusProperty.set(FileStatus.ADDED);
        } else if (status.getRemoved().contains(relativePath) || status.getMissing().contains(relativePath)) {
            statusProperty.set(FileStatus.REMOVED);
        } else if (status.getChanged().contains(relativePath) || status.getModified().contains(relativePath)) {
            statusProperty.set(FileStatus.CHANGED);
        } else if (status.getUntracked().contains(relativePath)) {
            statusProperty.set(FileStatus.UNTRACKED);
        } else if (status.getIgnoredNotInIndex().contains(relativePath)) {
            statusProperty.set(FileStatus.IGNORED);
        } else {
            statusProperty.set(FileStatus.NORMAL);
        }
    }

    /**
     * 获取变更的文件列表
     * @param dir 目录
     * @return 变更的文件列表
     */
    @SneakyThrows
    public static List<File> getChangedFiles(File dir) {
        Git git = findGit(dir);
        if (git == null) {
            return List.of();
        }
        List<String> filePathList = new ArrayList<>();
        Status status = git.status().call();
        filePathList.addAll(status.getAdded());
        filePathList.addAll(status.getChanged());
        filePathList.addAll(status.getModified());
        filePathList.addAll(status.getUntracked());
        filePathList.addAll(status.getRemoved());
        filePathList.addAll(status.getMissing());
        return filePathList.stream().map(path -> new File(git.getRepository().getWorkTree(), path)).toList();
    }

    /**
     * 提交
     * @param dir 目录
     * @param fileList 文件列表
     * @param message 提交信息
     * @param isAmend 修改
     * @param onOutput 输出回调
     */
    public static void commit(File dir, List<File> fileList, String message, boolean isAmend, Consumer<String> onOutput) {
        Consumer<List<String>> run = argList -> {
            onOutput.accept(DateUtil.format(new Date(), "HH:mm:ss") + ": " + argList);
            IoUtil.readUtf8Lines(RuntimeUtil.exec(null, dir,
                argList.toArray(new String[0])).getInputStream(), (LineHandler) onOutput::accept);
        };

        List<String> addArgList = new ArrayList<>(DEFAULT_ARG_LIST);
        addArgList.addAll(List.of("add", "--ignore-errors", "-A", "-f", "--"));
        fileList.forEach(file -> addArgList.add(org.a8043.simpleIDE.util.FileUtil.getRelativePath(dir, file)));
        run.accept(addArgList);

        List<String> commitArgList = new ArrayList<>(DEFAULT_ARG_LIST);
        File messageFile = FileUtil.writeUtf8String(message, FileUtil.createTempFile());
        commitArgList.add("commit");
        if (isAmend) {
            commitArgList.add("--amend");
        }
        commitArgList.addAll(List.of("-F", messageFile.getAbsolutePath(), "--"));
        run.accept(commitArgList);

        FILE_STATUS_MAP.keySet().forEach(GitUtil::refreshFileStatus);
    }

    /**
     * 获取分支列表
     * @param dir 目录
     * @return 分支列表
     */
    @SneakyThrows
    public static List<String> getBranchList(File dir) {
        Git git = findGit(dir);
        if (git == null) {
            return List.of();
        }
        return git.branchList().call().stream().map(ref -> Repository.shortenRefName(ref.getName())).toList();
    }

    /**
     * 获取远程列表
     * @param dir 目录
     * @return 远程列表
     */
    @SneakyThrows
    public static List<Remote> getRemoteList(File dir) {
        Git git = findGit(dir);
        if (git == null) {
            return List.of();
        }
        return git.remoteList().call().stream().map(remote -> new Remote(remote.getName(),
            remote.getURIs().stream().map(URIish::toString).findFirst().orElse(null), git)).toList();
    }

    /**
     * 推送
     * @param dir 目录
     * @param remote 远程
     * @param remoteBranch 远程分支
     * @param branch 本地分支
     * @param isForce 强制
     * @param onOutput 输出回调
     */
    public static void push(File dir, String remote, String remoteBranch,
                            String branch, boolean isForce, Consumer<String> onOutput) {
        List<String> argList = new ArrayList<>(DEFAULT_ARG_LIST);
        argList.addAll(List.of("push", "--progress", "--porcelain"));
        if (isForce) {
            argList.add("--force");
        }
        argList.addAll(List.of(remote, "refs/heads/" + branch + ":" + remoteBranch));
        onOutput.accept(DateUtil.format(new Date(), "HH:mm:ss") + ": " + argList);
        IoUtil.readUtf8Lines(RuntimeUtil.exec(null, dir,
            argList.toArray(new String[0])).getInputStream(), (LineHandler) onOutput::accept);
    }

    private static Git findGit(File file) {
        return GIT_LIST.stream().filter(git -> file.getAbsolutePath().startsWith(
            git.getRepository().getWorkTree().getAbsolutePath())).findFirst().orElse(null);
    }

    /**
     * 关闭所有Git
     */
    public static void close() {
        GIT_LIST.forEach(Git::close);
    }

    /**
     * 创建分支
     * @param projectDir 目录
     * @param branch 分支名称
     * @return 进程退出码
     */
    @SneakyThrows
    public static int createBranch(File projectDir, String branch) {
        List<String> argList = new ArrayList<>(DEFAULT_ARG_LIST);
        argList.addAll(List.of("switch", "-c", branch));
        return RuntimeUtil.exec(null, projectDir, argList.toArray(new String[0])).waitFor();
    }

    /**
     * 切换分支
     * @param projectDir 目录
     * @param branch 分支名称
     * @return 进程退出码
     */
    @SneakyThrows
    public static int switchBranch(File projectDir, String branch) {
        List<String> argList = new ArrayList<>(DEFAULT_ARG_LIST);
        argList.addAll(List.of("switch", branch));
        return RuntimeUtil.exec(null, projectDir, argList.toArray(new String[0])).waitFor();
    }

    /**
     * 删除分支
     * @param dir 目录
     * @param branch 分支名称
     * @return 进程退出码
     */
    @SneakyThrows
    public static int deleteBranch(File dir, String branch) {
        List<String> argList = new ArrayList<>(DEFAULT_ARG_LIST);
        argList.addAll(List.of("branch", "-D", branch));
        return RuntimeUtil.exec(null, dir, argList.toArray(new String[0])).waitFor();
    }

    /**
     * 获取当前分支
     * @param dir 目录
     * @return 当前分支名称, 若未找到Git仓库则返回null
     */
    @SneakyThrows
    public static String getCurrentBranch(File dir) {
        Git git = findGit(dir);
        if (git == null) {
            return null;
        }
        return git.getRepository().getBranch();
    }

    /**
     * 远程信息
     */
    @Setter
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static class Remote {
        @Getter
        private String name;
        @Getter
        private String url;
        private final Git git;

        @SneakyThrows
        public void save() {
            git.remoteRemove().setRemoteName(name).call();
            git.remoteAdd().setName(name).setUri(new URIish(url)).call();
        }
    }

    /**
     * 文件状态
     */
    public enum FileStatus {
        NORMAL, CHANGED, ADDED, REMOVED, UNTRACKED, IGNORED
    }
}
