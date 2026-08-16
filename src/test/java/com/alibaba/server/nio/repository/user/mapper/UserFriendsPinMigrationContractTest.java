package com.alibaba.server.nio.repository.user.mapper;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class UserFriendsPinMigrationContractTest {

    @Test
    public void migrationAddsPinColumnsAndOrderingIndexIdempotently() throws Exception {
        String migration = new String(Files.readAllBytes(Paths.get(
                "sql/user_friend_pinning_migration_20260730.sql")), StandardCharsets.UTF_8);

        assertTrue(migration.contains("information_schema.COLUMNS"));
        assertTrue(migration.contains("COLUMN_NAME = 'is_pinned'"));
        assertTrue(migration.contains("is_pinned TINYINT(1) NOT NULL DEFAULT 0"));
        assertTrue(migration.contains("COLUMN_NAME = 'pinned_at'"));
        assertTrue(migration.contains("pinned_at DATETIME(3) NULL"));
        assertTrue(migration.contains("information_schema.STATISTICS"));
        assertTrue(migration.contains("idx_user_friends_pin_order"));
        assertTrue(migration.contains("user_id, del, is_pinned, pinned_at"));
        assertTrue(migration.contains("PREPARE"));
    }
}
