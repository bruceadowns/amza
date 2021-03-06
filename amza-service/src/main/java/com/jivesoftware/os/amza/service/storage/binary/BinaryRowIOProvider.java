package com.jivesoftware.os.amza.service.storage.binary;

import com.google.common.collect.Lists;
import com.google.common.io.Files;
import com.jivesoftware.os.amza.api.wal.RowIO;
import com.jivesoftware.os.amza.api.IoStats;
import com.jivesoftware.os.amza.service.storage.filer.DiskBackedWALFiler;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.commons.io.FileUtils;

/**
 * @author jonathan.colt
 */
public class BinaryRowIOProvider implements RowIOProvider {

    private final int defaultUpdatesBetweenLeaps;
    private final int defaultMaxLeaps;
    private final boolean useMemMap;

    public BinaryRowIOProvider(
        int defaultUpdatesBetweenLeaps,
        int defaultMaxLeaps,
        boolean useMemMap) {

        this.defaultUpdatesBetweenLeaps = defaultUpdatesBetweenLeaps;
        this.defaultMaxLeaps = defaultMaxLeaps;
        this.useMemMap = useMemMap;
    }

    @Override
    public RowIO open(File dir, String name, boolean createIfAbsent, int updatesBetweenLeaps, int maxLeaps) throws Exception {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Failed trying to mkdirs for " + dir);
        }
        File file = new File(dir, name);
        if (!createIfAbsent && !file.exists()) {
            return null;
        }
        DiskBackedWALFiler filer = new DiskBackedWALFiler(file.getAbsolutePath(), "rw", useMemMap, 0);
        BinaryRowReader rowReader = new BinaryRowReader(filer);
        BinaryRowWriter rowWriter = new BinaryRowWriter(filer);
        return new BinaryRowIO(dir,
            name,
            rowReader,
            rowWriter,
            updatesBetweenLeaps > 0 ? updatesBetweenLeaps : defaultUpdatesBetweenLeaps,
            maxLeaps > 0 ? maxLeaps : defaultMaxLeaps);
    }

    @Override
    public List<String> listExisting(File dir) {
        File[] existing = dir.listFiles();
        return existing != null ? Lists.transform(Arrays.asList(existing), File::getName) : Collections.<String>emptyList();
    }

    @Override
    public File versionedKey(File baseKey, String latestVersion) throws Exception {
        File directory = new File(baseKey, latestVersion);
        FileUtils.forceMkdir(directory);
        return directory;
    }

    @Override
    public boolean ensureKey(File key) {
        return key.exists() || key.mkdirs();
    }

    @Override
    public boolean exists(File dir, String name) {
        return new File(dir, name).exists();
    }

    @Override
    public long sizeInBytes(File dir, String name) {
        return new File(dir, name).length();
    }

    @Override
    public File buildKey(File versionedKey, String name) {
        return new File(versionedKey, name);
    }

    @Override
    public void moveTo(File fromDir, String fromName, File toDir, String toName) throws Exception {
        File from = new File(fromDir, fromName);
        File to = new File(toDir, toName);
        Files.move(from, to);
    }

    @Override
    public void delete(File dir, String name) throws Exception {
        FileUtils.deleteQuietly(new File(dir, name));
    }
}
