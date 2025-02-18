/*
 * Copyright (c) 2023, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.graalvm.python.embedding.utils;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import org.graalvm.polyglot.io.FileSystem;

public final class VirtualFileSystem implements FileSystem, AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(VirtualFileSystem.class.getName());
    static {
        LOGGER.setUseParentHandlers(false);
        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setFormatter(new SimpleFormatter() {
            @Override
            public synchronized String format(LogRecord lr) {
                return String.format("%s: %s\n", lr.getLevel().getName(), lr.getMessage());
            }
        });
        LOGGER.addHandler(consoleHandler);
    }

    /*
     * Root of the virtual filesystem in the resources.
     */
    static final String VFS_ROOT = "/org.graalvm.python.vfs";

    static final String VFS_HOME = "home";
    static final String VFS_VENV = "venv";
    static final String VFS_SRC = "src";

    /*
     * Index of all files and directories available in the resources at runtime. - paths are
     * absolute - directory paths end with a '/' - uses '/' separator regardless of platform. Used
     * to determine directory entries, if an entry is a file or a directory, etc.
     */
    private static final String FILES_LIST_PATH = VFS_ROOT + "/fileslist.txt";
    private static final String VENV_PREFIX = VFS_ROOT + "/" + VFS_VENV;
    private static final String HOME_PREFIX = VFS_ROOT + "/" + VFS_HOME;
    private static final String PROJ_PREFIX = VFS_ROOT + "/proj";
    private static final String SRC_PREFIX = VFS_ROOT + "/" + VFS_SRC;

    private boolean extractOnStartup = "true".equals(System.getProperty("graalpy.vfs.extractOnStartup"));

    public static enum HostIO {
        NONE,
        READ,
        READ_WRITE,
    }

    /**
     * Builder class to create {@link VirtualFileSystem} instances.
     */
    public static final class Builder {
        private static final Predicate<Path> DEFAULT_EXTRACT_FILTER = (p) -> {
            var s = p.toString();
            return s.endsWith(".so") || s.endsWith(".dylib") || s.endsWith(".pyd") || s.endsWith(".dll") || s.endsWith(".ttf");
        };

        private static final String DEFAULT_WINDOWS_MOUNT_POINT = "X:\\graalpy_vfs";
        private String DEFAULT_UNIX_MOUNT_POINT = "/graalpy_vfs";
        private Path mountPoint;
        private Predicate<Path> extractFilter = DEFAULT_EXTRACT_FILTER;
        private HostIO allowHostIO = HostIO.READ_WRITE;
        private boolean caseInsensitive = isWindows();

        private Class<?> resourceLoadingClass;

        private Builder() {
        }

        /**
         * Sets the file system to be case-insensitive. Defaults to true on Windows and false
         * elsewhere.
         */
        public Builder caseInsensitive(boolean value) {
            caseInsensitive = value;
            return this;
        }

        /**
         * Determines if and how much host IO is allowed outside of the virtual filesystem.
         */
        public Builder allowHostIO(HostIO b) {
            allowHostIO = b;
            return this;
        }

        /**
         * The mount point for the virtual filesystem on Windows. This mount point shadows any real
         * filesystem, so should be chosen to avoid clashes with the users machine, e.g. if set to
         * "X:\graalpy_vfs", then a resource with path /org.graalvm.python.vfs/xyz/abc is visible as
         * "X:\graalpy_vfs\xyz\abc". This needs to be an absolute path with platform-specific
         * separators without any trailing separator. If that file or directory actually exists, it
         * will not be accessible.
         */
        public Builder windowsMountPoint(String s) {
            if (isWindows()) {
                mountPoint = getMountPointAsPath(s);
            }
            return this;
        }

        /**
         * The mount point for the virtual filesystem on Unices. This mount point shadows any real
         * filesystem, so should be chosen to avoid clashes with the users machine, e.g. if set to
         * "/graalpy_vfs", then a resource with path /org.graalvm.python.vfs/xyz/abc is visible as
         * "/graalpy_vfs/xyz/abc". This needs to be an absolute path with platform-specific
         * separators without any trailing separator. If that file or directory actually exists, it
         * will not be accessible.
         */
        public Builder unixMountPoint(String s) {
            if (!isWindows()) {
                mountPoint = getMountPointAsPath(s);
            }
            return this;
        }

        /**
         * By default, virtual filesystem resources are loaded by delegating to
         * <code>VirtualFileSystem.class.getResource(name)</code>. Use
         * <code>resourceLoadingClass</code> to determine where to locate resources in cases when
         * for example <code>VirtualFileSystem</code> is on module path and the jar containing the
         * resources is on class path.
         */
        public Builder resourceLoadingClass(Class<?> c) {
            resourceLoadingClass = c;
            return this;
        }

        /**
         * This filter applied to files in the virtual filesystem treats them as symlinks to real
         * files in the host filesystem. This is useful, for example, if files in the virtual
         * filesystem need to be accessed outside the Truffle sandbox. They will be extracted to the
         * Java temporary directory on demand. The default filter matches any DLLs, dynamic
         * libraries, shared objects, and Python C extension files, because these need to be
         * accessed by the operating system loader. Setting this filter to <code>null</code> denies
         * any extraction. Any other filter is combined with the default filter.
         */
        public Builder extractFilter(Predicate<Path> filter) {
            if (extractFilter == null) {
                extractFilter = null;
            } else {
                extractFilter = (p) -> filter.test(p) || DEFAULT_EXTRACT_FILTER.test(p);
            }
            return this;
        }

        public VirtualFileSystem build() {
            if (mountPoint == null) {
                mountPoint = isWindows() ? Path.of(DEFAULT_WINDOWS_MOUNT_POINT) : Path.of(DEFAULT_UNIX_MOUNT_POINT);
            }
            return new VirtualFileSystem(extractFilter, mountPoint, allowHostIO, resourceLoadingClass, caseInsensitive);
        }
    }

    private static Path getMountPointAsPath(String mp) {
        Path mountPoint = Path.of(mp);
        if (mp.endsWith(PLATFORM_SEPARATOR) || !mountPoint.isAbsolute()) {
            throw new IllegalArgumentException(String.format("Virtual filesystem mount point must be set to an absolute path without a trailing separator: '%s'", mp));
        }
        return mountPoint;
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static VirtualFileSystem create() {
        return newBuilder().build();
    }

    /*
     * Maps platform-specific paths to entries.
     */
    private Map<String, BaseEntry> vfsEntries;

    /**
     * Class used to read resources with getResource(name). By default VirtualFileSystem.class.
     */
    private Class<?> resourceLoadingClass;

    private final FileSystem delegate;

    private static final String PLATFORM_SEPARATOR = Paths.get("").getFileSystem().getSeparator();
    private static final char RESOURCE_SEPARATOR_CHAR = '/';
    private static final String RESOURCE_SEPARATOR = String.valueOf(RESOURCE_SEPARATOR_CHAR);

    private abstract class BaseEntry {
        private final String platformPath;

        private BaseEntry(String platformPath) {
            this.platformPath = platformPath;
        }

        String getPlatformPath() {
            return platformPath;
        }

        String getResourcePath() {
            return platformPathToResourcePath(platformPath);
        }
    }

    private final class FileEntry extends BaseEntry {
        private byte[] data;

        public FileEntry(String path) {
            super(path);
        }

        private byte[] getData() throws IOException {
            if (data == null) {
                data = readResource(getResourcePath());
            }
            return data;
        }
    }

    private final class DirEntry extends BaseEntry {
        List<BaseEntry> entries = new ArrayList<>();

        DirEntry(String platformPath) {
            super(platformPath);
        }
    }

    /*
     * Determines where the virtual filesystem lives in the real filesystem, e.g. if set to
     * "X:\graalpy_vfs", then a resource with path /org.graalvm.python.vfs/xyz/abc is visible as
     * "X:\graalpy_vfs\xyz\abc". This needs to be an absolute path with platform-specific separators
     * without any trailing separator. If that file or directory actually exists, it will not be
     * accessible.
     */
    private final Path mountPoint;
    private final String mountPointLowerCase;

    /**
     * The temporary directory where to extract files/directories to.
     */
    private final Path extractDir;

    /**
     * A filter to determine if a path should be extracted (see {@link #shouldExtract(Path)}).
     */
    private final Predicate<Path> extractFilter;
    private final boolean caseInsensitive;

    private static final class DeleteTempDir extends Thread {
        private final Path extractDir;

        DeleteTempDir(Path extractDir) {
            this.extractDir = extractDir;
        }

        private static final SimpleFileVisitor<Path> deleteVisitor = new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        };

        @Override
        public void run() {
            removeExtractDir();
        }

        private void removeExtractDir() {
            if (extractDir != null && Files.exists(extractDir)) {
                try {
                    Files.walkFileTree(extractDir, deleteVisitor);
                } catch (IOException e) {
                    System.err.format("Could not delete temp directory '%s': %s", extractDir, e);
                }
            }
        }
    }

    private final DeleteTempDir deleteTempDir;

    /**
     * If an extract filter is given, the virtual file system will lazily extract files and
     * directories matching the filter to a temporary directory. This happens if the
     * {@link #toAbsolutePath(Path) absolute path} is computed. This argument may be {@code null}
     * causing that no extraction will happen.
     */
    VirtualFileSystem(Predicate<Path> extractFilter,
                    Path mountPoint,
                    HostIO allowHostIO,
                    Class<?> resourceLoadingClass,
                    boolean caseInsensitive) {
        if (resourceLoadingClass != null) {
            this.resourceLoadingClass = resourceLoadingClass;
        } else {
            this.resourceLoadingClass = VirtualFileSystem.class;
        }

        this.caseInsensitive = caseInsensitive;
        this.mountPoint = mountPoint;

        this.mountPointLowerCase = mountPoint.toString().toLowerCase(Locale.ROOT);

        fine("VirtualFilesystem %s, allowHostIO: %s, resourceLoadingClass: %s, caseInsensitive: %s, extractOnStartup: %s%s",
                        mountPoint, allowHostIO.toString(), this.resourceLoadingClass.getName(), caseInsensitive, extractOnStartup, extractFilter != null ? "" : ", extractFilter: null");

        this.extractFilter = extractFilter;
        if (extractFilter != null) {
            try {
                this.extractDir = Files.createTempDirectory("org.graalvm.python.vfsx");
                this.deleteTempDir = new DeleteTempDir(this.extractDir);
                Runtime.getRuntime().addShutdownHook(deleteTempDir);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        } else {
            this.extractDir = null;
            this.deleteTempDir = null;
        }
        delegate = switch (allowHostIO) {
            case NONE -> null;
            case READ -> FileSystem.newReadOnlyFileSystem(FileSystem.newDefaultFileSystem());
            case READ_WRITE -> FileSystem.newDefaultFileSystem();
        };
    }

    @Override
    public void close() {
        if (deleteTempDir != null) {
            deleteTempDir.removeExtractDir();
        }
    }

    static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");
    }

    String vfsHomePath() {
        return resourcePathToPlatformPath(HOME_PREFIX);
    }

    String vfsSrcPath() {
        return resourcePathToPlatformPath(SRC_PREFIX);
    }

    String vfsVenvPath() {
        return resourcePathToPlatformPath(VENV_PREFIX);
    }

    /**
     * Converts the given path starting with the internal resource root to the path as seen by
     * Python IO. For example if no other mount point was set then the path
     * "/org.graalvm.python.vfs/src/hello.py" will be converted to the default mount point
     * "/graalpy_vfs/src/hello.py" .
     *
     * @deprecated use {@link #getMountPoint()} instead
     */
    @Deprecated
    public String resourcePathToPlatformPath(String resourcePath) {
        if (!(resourcePath.length() > VFS_ROOT.length() && resourcePath.startsWith(VFS_ROOT))) {
            String msg = "Resource path is expected to start with '" + VFS_ROOT + "' but was '" + resourcePath + "'.\n" +
                            "Please also ensure that your virtual file system resources root directory is '" + VFS_ROOT + "'";
            throw new IllegalArgumentException(msg);
        }
        var path = resourcePath.substring(VFS_ROOT.length() + 1);
        if (!PLATFORM_SEPARATOR.equals(RESOURCE_SEPARATOR)) {
            path = path.replace(RESOURCE_SEPARATOR, PLATFORM_SEPARATOR);
        }
        String absolute = mountPoint.resolve(path).toString();
        if (resourcePath.endsWith(RESOURCE_SEPARATOR) && !absolute.endsWith(PLATFORM_SEPARATOR)) {
            absolute += PLATFORM_SEPARATOR;
        }
        return absolute;
    }

    private String platformPathToResourcePath(String inputPath) {
        String mountPointString = mountPoint.toString();
        String path = inputPath;
        assert path.startsWith(mountPointString);
        if (path.startsWith(mountPointString)) {
            path = path.substring(mountPointString.length());
        }
        if (!PLATFORM_SEPARATOR.equals(RESOURCE_SEPARATOR)) {
            path = path.replace(PLATFORM_SEPARATOR, RESOURCE_SEPARATOR);
        }
        if (path.endsWith(RESOURCE_SEPARATOR)) {
            path = path.substring(0, path.length() - RESOURCE_SEPARATOR.length());
        }
        path = VFS_ROOT + path;
        return path;
    }

    private String toCaseComparable(String file) {
        return caseInsensitive ? file.toLowerCase(Locale.ROOT) : file;
    }

    private boolean projWarning = false;

    private void initEntries() throws IOException {
        vfsEntries = new HashMap<>();
        try (InputStream stream = this.resourceLoadingClass.getResourceAsStream(FILES_LIST_PATH)) {
            if (stream == null) {
                warn("VFS.initEntries: could not read resource %s", FILES_LIST_PATH);
                return;
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(stream));
            String line;
            finest("VFS entries:");
            while ((line = br.readLine()) != null) {

                if (!projWarning && line.startsWith(PROJ_PREFIX)) {
                    projWarning = true;
                    LOGGER.warning("");
                    LOGGER.warning(String.format("%s source root was deprecated, use %s instead.", PROJ_PREFIX, SRC_PREFIX));
                    LOGGER.warning("");
                }

                String platformPath = resourcePathToPlatformPath(line);
                int i = 0;
                DirEntry parent = null;
                while ((i = platformPath.indexOf(PLATFORM_SEPARATOR, i)) != -1) {
                    String dir = platformPath.substring(0, i);
                    String dirKey = toCaseComparable(dir);
                    DirEntry dirEntry = (DirEntry) vfsEntries.get(dirKey);
                    if (dirEntry == null) {
                        dirEntry = new DirEntry(dir);
                        vfsEntries.put(dirKey, dirEntry);
                        if (parent != null) {
                            parent.entries.add(dirEntry);
                        }
                    }
                    parent = dirEntry;
                    i++;
                }
                assert parent != null;
                if (!platformPath.endsWith(PLATFORM_SEPARATOR)) {
                    FileEntry fileEntry = new FileEntry(platformPath);
                    vfsEntries.put(toCaseComparable(platformPath), fileEntry);
                    finest("  %s", fileEntry.getResourcePath());
                    parent.entries.add(fileEntry);
                    if (extractOnStartup) {
                        Path p = Paths.get(fileEntry.getPlatformPath());
                        if (shouldExtract(p)) {
                            getExtractedPath(p);
                        }
                    }
                }
            }
        }
    }

    byte[] readResource(String path) throws IOException {
        try (InputStream stream = this.resourceLoadingClass.getResourceAsStream(path)) {
            if (stream == null) {
                warn("VFS.initEntries: could not read resource %s", path);
                return null;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int n;
            byte[] data = new byte[4096];

            while ((n = stream.readNBytes(data, 0, data.length)) != 0) {
                buffer.write(data, 0, n);
            }
            buffer.flush();
            return buffer.toByteArray();
        }
    }

    private Path toAbsolutePathInternal(Path path) {
        if (pathIsInVfs(path)) {
            return path;
        }
        return mountPoint.resolve(path);
    }

    private BaseEntry getEntry(Path inputPath) throws IOException {
        if (vfsEntries == null) {
            initEntries();
            if (vfsEntries == null || vfsEntries.isEmpty()) {
                warn("VFS.getEntry: no entries after init");
            }
        }
        Path path = toAbsolutePathInternal(inputPath).normalize();
        return vfsEntries.get(toCaseComparable(path.toString()));
    }

    /**
     * The mount point for the virtual filesystem.
     *
     * @see Builder#windowsMountPoint(String)
     * @see Builder#unixMountPoint(String)
     */
    public String getMountPoint() {
        return this.mountPoint.toString();
    }

    private boolean pathIsInVfs(Path path) {
        return toCaseComparable(path.normalize().toString()).startsWith(mountPointLowerCase);
    }

    /**
     * Uses {@link #extractFilter} to determine if the given platform path should be extracted.
     */
    private boolean shouldExtract(Path path) {
        boolean ret = extractFilter != null && extractFilter.test(path);
        finest("VFS.shouldExtract '%s' %s", path, ret);
        return ret;
    }

    /**
     * Extracts a file or directory from the resource to the temporary directory and returns the
     * path to the extracted file. Nonexistent parent directories will also be created
     * (recursively). If the extracted file or directory already exists, nothing will be done.
     */
    private Path getExtractedPath(Path path) {
        assert shouldExtract(path);
        return extractPath(path, true);
    }

    private Path extractPath(Path path, boolean extractLibsDir) {
        assert extractDir != null;
        try {
            /*
             * Remove the mountPoint(X) (e.g. "graalpy_vfs(x)") prefix if given. Method 'file' is
             * able to handle relative paths and we need it to compute the extract path.
             */
            BaseEntry entry = getEntry(path);
            if (entry == null) {
                warn("no entry for '%s'", path);
                return null;
            }
            Path relPath = mountPoint.relativize(Paths.get(entry.getPlatformPath()));

            // create target path
            Path xPath = extractDir.resolve(relPath);
            if (!Files.exists(xPath)) {
                if (entry instanceof FileEntry fileEntry) {
                    // first create parent dirs
                    Path parent = xPath.getParent();
                    assert parent == null || !Files.exists(parent) || Files.isDirectory(parent);
                    if (parent == null) {
                        throw new NullPointerException("Parent is null during extracting path.");
                    }
                    Files.createDirectories(parent);

                    // write data extracted file
                    Files.write(xPath, fileEntry.getData());
                    finest("extracted '%s' -> '%s'", path, xPath);

                    if (extractLibsDir) {
                        Path pkgDir = getPythonPackageDir(path);
                        if (pkgDir != null) {
                            Path libsDir = Paths.get(pkgDir + ".libs");
                            extract(libsDir);
                        }
                    }

                } else if (entry instanceof DirEntry) {
                    Files.createDirectories(xPath);
                } else {
                    return path;
                }
            }

            return xPath;
        } catch (IOException e) {
            throw new RuntimeException(String.format("Error while extracting virtual filesystem path '%s' to the disk", path), e);
        }
    }

    private static Path getPythonPackageDir(Path path) {
        Path prev = null;
        Path p = path;
        while ((p = p.getParent()) != null) {
            Path fileName = p.getFileName();
            if (fileName != null && "site-packages".equals(fileName.toString())) {
                return prev;
            }
            prev = p;
        }
        return null;
    }

    private void extract(Path path) throws IOException {
        BaseEntry entry = getEntry(path);
        if (entry instanceof FileEntry) {
            extractPath(path, false);
        } else if (entry != null) {
            if (((DirEntry) entry).entries != null) {
                for (BaseEntry be : ((DirEntry) entry).entries) {
                    extract(Path.of(be.getPlatformPath()));
                }
            }
        }
    }

    void extractResources(Path resourcesDirectory) throws IOException {
        fine("VFS.extractResources '%s'", resourcesDirectory);
        InputStream stream = this.resourceLoadingClass.getResourceAsStream(FILES_LIST_PATH);
        if (stream == null) {
            warn("VFS.initEntries: could not read resource %s", FILES_LIST_PATH);
            return;
        }
        BufferedReader br = new BufferedReader(new InputStreamReader(stream));
        String resourcePath;
        while ((resourcePath = br.readLine()) != null) {
            Path destFile = resourcesDirectory.resolve(Path.of(resourcePath.substring(VFS_ROOT.length() + 1)));
            if (destFile == null) {
                continue;
            }
            if (resourcePath.endsWith(RESOURCE_SEPARATOR)) {
                Files.createDirectories(destFile);
            } else {
                Path parent = destFile.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                finest("VFS.extractResources '%s' -> '%s'", resourcePath, destFile);
                Files.write(destFile, readResource(resourcePath));
            }
        }
    }

    private FileSystemProvider defaultFileSystemProvider;

    private FileSystemProvider getDefaultFileSystem() {
        if (defaultFileSystemProvider == null) {
            for (FileSystemProvider provider : FileSystemProvider.installedProviders()) {
                if ("file".equals(provider.getScheme())) {
                    defaultFileSystemProvider = provider;
                }
            }
        }
        return defaultFileSystemProvider;
    }

    Path getPath(URI uri) {
        return getDefaultFileSystem().getPath(uri);
    }

    @Override
    @Deprecated
    public Path parsePath(URI uri) {
        // similar as in c.o.t.polyglot.FileSystems.DeniedIOFileSystem
        if (uri.getScheme().equals("file")) {
            Path path = getDefaultFileSystem().getPath(uri);
            finest("VFS.parsePath '%s' -> '%s'", uri, path);
            return path;
        } else {
            String msg = "Unsupported URI scheme '%s'";
            finer(msg, uri.getScheme());
            throw new UnsupportedOperationException(String.format(msg, uri.getScheme()));
        }
    }

    @Override
    @Deprecated
    public Path parsePath(String path) {
        // same as in c.o.t.polyglot.FileSystems.DeniedIOFileSystem
        Path p = Paths.get(path);
        finer("VFS.parsePath '%s' -> '%s'", path, p);
        return p;
    }

    @Override
    @Deprecated
    public void checkAccess(Path path, Set<? extends AccessMode> modes, LinkOption... linkOptions) throws IOException {
        if (!pathIsInVfs(path)) {
            if (delegate != null) {
                boolean passed = false;
                try {
                    delegate.checkAccess(path, modes, linkOptions);
                    passed = true;
                } finally {
                    finest("VFS.checkAccess delegated '%s' %s and ", path, passed ? "passed" : "did not pass");
                }
            } else {
                String msg = String.format("filesystem without host IO: '%s'", path);
                finest("VFS.checkAccess %s", msg);
                throw new SecurityException(msg);
            }
        } else {
            if (modes.contains(AccessMode.WRITE)) {
                String msg = String.format("read-only filesystem: '%s'", path);
                finer("VFS.checkAccess %s", msg);
                throw new SecurityException(msg);
            }
            if (getEntry(path) == null) {
                String msg = String.format("no such file or directory: '%s'", path);
                finer("VFS.checkAccess %s", msg);
                throw new NoSuchFileException(msg);
            }
            finer("VFS.checkAccess %s OK", path);
        }
    }

    @Override
    @Deprecated
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        if (!pathIsInVfs(dir)) {
            if (delegate != null) {
                boolean passed = false;
                try {
                    delegate.createDirectory(dir, attrs);
                } finally {
                    finest("VFS.createDirectory delegated '%s' %s", dir, passed ? "passed" : "did not pass");
                }
            } else {
                String msg = String.format("filesystem without host IO: '%s'", dir);
                finest("VFS.createDirectory %s", msg);
                throw new SecurityException("filesystem without host IO: " + dir);
            }
        } else {
            String msg = String.format("read-only filesystem: '%s'", dir);
            finer("VFS.createDirectory %s", msg);
            throw new SecurityException(msg);
        }
    }

    @Override
    @Deprecated
    public void delete(Path path) throws IOException {
        if (!pathIsInVfs(path)) {
            if (delegate != null) {
                boolean passed = false;
                try {
                    delegate.delete(path);
                } finally {
                    finest("VFS.delete delegated '%s' %s", path, passed ? "passed" : "did not pass");
                }
            } else {
                String msg = String.format("filesystem without host IO: '%s'", path);
                finest("VFS.delete %s", msg);
                throw new SecurityException(msg);
            }
        } else {
            String msg = String.format("read-only filesystem: '%s'", path);
            finer("VFS.delete %s", msg);
            throw new SecurityException(msg);
        }
    }

    @Override
    @Deprecated
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        if (!pathIsInVfs(path)) {
            if (delegate != null) {
                boolean passed = false;
                try {
                    return delegate.newByteChannel(path, options, attrs);
                } finally {
                    finest("VFS.newByteChannel delegated '%s' %s", path, passed ? "passed" : "did not pass");
                }
            } else {
                String msg = String.format("filesystem without host IO: '%s'", path);
                finest("VFS.newByteChannel %s", msg);
                throw new SecurityException(msg);
            }
        }

        if (options.isEmpty() || (options.size() == 1 && options.contains(StandardOpenOption.READ))) {
            BaseEntry entry = getEntry(path);
            if (entry == null) {
                String msg = String.format("No such file or directory: '%s'", path);
                finer("VFS.newByteChannel '%s'", path);
                throw new FileNotFoundException(msg);
            }
            if (!(entry instanceof FileEntry fileEntry)) {
                finer("VFS.newByteChannel Is a directory '%s'", path);
                // this constructor is used since we rely on the error message to convert to the
                // appropriate python error
                throw new FileSystemException(path.toString(), null, "Is a directory");
            }
            return new SeekableByteChannel() {
                long position = 0;

                final byte[] bytes = fileEntry.getData();

                @Override
                public int read(ByteBuffer dst) throws IOException {
                    if (position > bytes.length) {
                        return -1;
                    } else if (position == bytes.length) {
                        return 0;
                    } else {
                        int length = Math.min(bytes.length - (int) position, dst.remaining());
                        dst.put(bytes, (int) position, length);
                        position += length;
                        if (dst.hasRemaining()) {
                            position++;
                        }
                        return length;
                    }
                }

                @Override
                public int write(ByteBuffer src) throws IOException {
                    String msg = String.format("read-only filesystem: '%s'", path);
                    finer("VFS.newByteChannel '%s'", msg);
                    throw new IOException(msg);
                }

                @Override
                public long position() throws IOException {
                    return position;
                }

                @Override
                public SeekableByteChannel position(long newPosition) throws IOException {
                    position = Math.max(0, newPosition);
                    return this;
                }

                @Override
                public long size() throws IOException {
                    return bytes.length;
                }

                @Override
                public SeekableByteChannel truncate(long size) throws IOException {
                    String msg = String.format("read-only filesystem: '%s'", path);
                    finer("VFS.newByteChannel '%s'", msg);
                    throw new IOException(msg);
                }

                @Override
                public boolean isOpen() {
                    return true;
                }

                @Override
                public void close() throws IOException {
                }
            };
        } else {
            String msg = String.format("read-only filesystem: '%s'", path);
            finer("VFS.newByteChannel '%s'", msg);
            throw new SecurityException(msg);
        }
    }

    @Override
    @Deprecated
    public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
        if (!pathIsInVfs(dir)) {
            if (delegate != null) {
                boolean passed = false;
                try {
                    return delegate.newDirectoryStream(dir, filter);
                } finally {
                    finest("VFS.newDirectoryStream delegated '%s' %s", dir, passed ? "passed" : "did not pass");
                }
            } else {
                String msg = String.format("filesystem without host IO: '%s'", dir);
                finest("VFS.newDirectoryStream %s", msg);
                throw new SecurityException(msg);
            }
        }
        BaseEntry entry = getEntry(dir);
        if (entry instanceof FileEntry) {
            finer("VFS.newDirectoryStream not a directory %s", dir);
            throw new NotDirectoryException(dir.toString());
        } else if (entry instanceof DirEntry dirEntry) {
            return new DirectoryStream<>() {
                @Override
                public void close() throws IOException {
                    // nothing to do
                }

                @Override
                public Iterator<Path> iterator() {
                    finest("VFS.newDirectoryStream %s entries:", dir);
                    return dirEntry.entries.stream().filter(e -> {
                        boolean accept = false;
                        try {
                            accept = filter.accept(Path.of(e.platformPath));
                            finest("VFS.newDirectoryStream entry %s accept: %s", e.platformPath, accept);
                        } catch (IOException ex) {
                            ex.printStackTrace();
                            return false;
                        }
                        return accept;
                    }).map(e -> Path.of(e.getPlatformPath())).iterator();
                }
            };
        } else {
            throw new NoSuchFileException(dir.toString());
        }
    }

    @Override
    @Deprecated
    public Path toAbsolutePath(Path path) {
        boolean pathIsInVFS = pathIsInVfs(path);
        if (!pathIsInVFS) {
            if (delegate != null) {
                Path ret = delegate.toAbsolutePath(path);
                finest("VFS.toAbsolutePath delegated '%s' -> '%s'", path, ret);
                return ret;
            } else {
                String msg = String.format("filesystem without host IO: '%s'", path);
                finest("VFS.toAbsolutePath %s", msg);
                throw new SecurityException(msg);
            }
        }
        Path result = path;
        if (pathIsInVFS && shouldExtract(path)) {
            result = getExtractedPath(path);
            if (result == null) {
                finer("VFS.toAbsolutePath could not extract '%s'", path);
                result = path;
            }
        }
        Path ret = toAbsolutePathInternal(result);
        finer("VFS.toAbsolutePath '%s' -> '%s'", path, ret);
        return ret;
    }

    @Override
    @Deprecated
    public Path toRealPath(Path path, LinkOption... linkOptions) throws IOException {
        boolean pathIsInVFS = pathIsInVfs(path);
        if (!pathIsInVFS) {
            if (delegate != null) {
                Path ret = delegate.toRealPath(path);
                finest("VFS.toRealPath delegated '%s' -> '%s'", path, ret);
                return ret;
            } else {
                String msg = String.format("filesystem without host IO: '%s'", path);
                finest("VFS.toRealPath %s", msg);
                throw new SecurityException(msg);
            }
        }
        Path result = path;
        if (pathIsInVFS && shouldExtract(path)) {
            result = getExtractedPath(path);
            if (result == null) {
                finer("VFS.toRealPath could not extract '%s'", path);
                result = path;
            }
        }
        Path ret = result.normalize();
        finer("VFS.toRealPath '%s' -> '%s'", path, ret);
        return ret;
    }

    @Override
    @Deprecated
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        if (!pathIsInVfs(path)) {
            if (delegate != null) {
                Map<String, Object> ret = delegate.readAttributes(path, attributes, options);
                finest("VFS.readAttributes delegated '%s' -> '%s'", path, ret);
                return ret;
            } else {
                String msg = String.format("filesystem without host IO: '%s'", path);
                finest("VFS.readAttributes %s", msg);
                throw new SecurityException(msg);
            }
        }
        BaseEntry entry = getEntry(path);
        if (entry == null) {
            String msg = String.format("no such file or directory: '%s'", path);
            finer("VFS.readAttributes %s", msg);
            throw new NoSuchFileException(msg);
        }
        HashMap<String, Object> attrs = new HashMap<>();
        if (attributes.startsWith("unix:") || attributes.startsWith("posix:")) {
            finer("VFS.readAttributes unsupported attributes '%s' %s", path, attributes);
            throw new UnsupportedOperationException();
        }

        attrs.put("creationTime", FileTime.fromMillis(0));
        attrs.put("lastModifiedTime", FileTime.fromMillis(0));
        attrs.put("lastAccessTime", FileTime.fromMillis(0));
        attrs.put("isRegularFile", entry instanceof FileEntry);
        attrs.put("isDirectory", entry instanceof DirEntry);
        attrs.put("isSymbolicLink", false);
        attrs.put("isOther", false);
        attrs.put("size", (long) (entry instanceof FileEntry fileEntry ? fileEntry.getData().length : 0));
        attrs.put("mode", 0555);
        attrs.put("dev", 0L);
        attrs.put("nlink", 1);
        attrs.put("uid", 0);
        attrs.put("gid", 0);
        attrs.put("ctime", FileTime.fromMillis(0));
        finer("VFS.readAttributes '%s' %s", path, attrs);
        return attrs;
    }

    private static void warn(String msgFormat, Object... args) {
        if (LOGGER.isLoggable(Level.WARNING)) {
            LOGGER.log(Level.WARNING, String.format(msgFormat, args));
        }
    }

    private static void fine(String msgFormat, Object... args) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(Level.FINE, String.format(msgFormat, args));
        }
    }

    private static void finer(String msgFormat, Object... args) {
        if (LOGGER.isLoggable(Level.FINER)) {
            LOGGER.log(Level.FINER, String.format(msgFormat, args));
        }
    }

    private static void finest(String msgFormat, Object... args) {
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(Level.FINEST, String.format(msgFormat, args));
        }
    }

}
