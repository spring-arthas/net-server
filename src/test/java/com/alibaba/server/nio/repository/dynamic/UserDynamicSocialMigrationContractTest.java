package com.alibaba.server.nio.repository.dynamic;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UserDynamicSocialMigrationContractTest {

    @Test
    public void migrationIsIdempotentAdditiveAndCreatesInteractionConstraints() throws Exception {
        String sql = new String(Files.readAllBytes(
                Paths.get("sql/user_dynamic_social_migration_20260812.sql")), StandardCharsets.UTF_8);
        String normalized = sql.toUpperCase();

        assertTrue(normalized.contains("CREATE TABLE IF NOT EXISTS `USER_DYNAMIC_INTERACTION`"));
        assertTrue(normalized.contains("IDEMPOTENCY_KEY"));
        assertTrue(normalized.contains("UNIQUE"));
        assertTrue(normalized.contains("MEDIA_JSON"));
        assertTrue(normalized.contains("REFERENCE_JSON"));
        assertTrue(normalized.contains("@DYNAMIC_DEL_TIME_COLUMN_EXISTS"));
        assertTrue(normalized.contains("ALTER TABLE USER_DYNAMIC ADD COLUMN DEL_TIME DATETIME(3) NULL"));
        assertTrue(normalized.contains("INFORMATION_SCHEMA.COLUMNS"));
        assertTrue(normalized.contains("INFORMATION_SCHEMA.STATISTICS"));
        assertFalse(normalized.contains("DROP TABLE"));
        assertFalse(normalized.contains("TRUNCATE TABLE"));
        assertFalse(normalized.contains("DELETE FROM"));
    }
}
