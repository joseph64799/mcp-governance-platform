package io.github.mcpgov.catalog;

import io.github.mcpgov.contracts.Json;
import io.github.mcpgov.contracts.Model.Snapshot;
import java.nio.file.*;
import java.io.IOException;

/** Atomic single-process snapshot store. Not a database replacement for a multi-replica admin service. */
public final class FileCatalogStore implements CatalogStore {
    private final Path path;
    public FileCatalogStore(Path path) { this.path = path.toAbsolutePath(); }
    @Override public synchronized Snapshot load() {
        try { return Files.exists(path) ? Json.read(Files.readString(path), Snapshot.class) : Snapshot.empty(); }
        catch (IOException e) { throw new IllegalStateException("Cannot read catalog", e); }
    }
    @Override public synchronized void save(long expectedVersion, Snapshot next) {
        if (load().version() != expectedVersion) throw new IllegalStateException("Concurrent catalog update");
        try {
            Files.createDirectories(path.getParent());
            Path temporary = Files.createTempFile(path.getParent(), "catalog-", ".tmp");
            try {
                Files.writeString(temporary, Json.write(next));
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        } catch (IOException e) { throw new IllegalStateException("Cannot atomically persist catalog", e); }
    }
}
