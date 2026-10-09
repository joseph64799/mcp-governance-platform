package io.github.mcpgov.catalog;

import io.github.mcpgov.contracts.Model.Snapshot;

/** Persistence port. A production multi-writer implementation must enforce the expected version atomically. */
public interface CatalogStore {
    Snapshot load();
    void save(long expectedVersion, Snapshot next);
}
