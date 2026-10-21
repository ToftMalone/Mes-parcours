package com.example

import com.example.data.local.AppDatabase
import com.example.data.local.DATABASE_VERSION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Safeguard on invariant no. 2 of CLAUDE.md: every schema version comes with its
 * migration.
 *
 * This is not a theoretical precaution. A missing migration makes the opening of
 * the database fail on the device of a user who updates — and before
 * `fallbackToDestructiveMigrationOnDowngrade`, it erased all their tracks without
 * a word. The defect is never seen in development, where we most often install
 * on top of a database already at the right version, and only shows up for
 * those coming from an earlier version.
 *
 * The test rereads the chain actually declared, without copying it: copying would let
 * through exactly the omission we are trying to prevent.
 */
class MigrationChainTest {

    private val declaredVersion: Int = DATABASE_VERSION

    /** Oldest version still reachable, the one the first migration starts from. */
    private val oldestSupported: Int =
        AppDatabase.MIGRATIONS.minOf { it.startVersion }

    @Test
    fun `migrations form a continuous chain up to the declared version`() {
        val steps = AppDatabase.MIGRATIONS
            .associateBy { it.startVersion }

        var version = oldestSupported
        while (version < declaredVersion) {
            val step = steps[version]
            assertTrue(
                "No migration starts from version $version: a database at version $version " +
                        "cannot reach version $declaredVersion. Add " +
                        "MIGRATION_${version}_${version + 1} to AppDatabase.",
                step != null
            )
            assertEquals(
                "The migration starting from $version must lead to ${version + 1}: " +
                        "a version jump leaves a hole in the chain.",
                version + 1,
                step!!.endVersion
            )
            version = step.endVersion
        }

        assertEquals(
            "The last migration must lead to the version declared by @Database.",
            declaredVersion,
            version
        )
    }

    @Test
    fun `no starting version is declared twice`() {
        val starts = AppDatabase.MIGRATIONS.map { it.startVersion }
        assertEquals(
            "Two migrations start from the same version: Room would not know which " +
                    "appliquer.",
            starts.size,
            starts.distinct().size
        )
    }

    @Test
    fun `no migration exceeds the declared version`() {
        val tooFar = AppDatabase.MIGRATIONS.filter { it.endVersion > declaredVersion }
        assertTrue(
            "Migration(s) leading beyond the declared version ($declaredVersion): " +
                    "$tooFar. Was the @Database version forgotten?",
            tooFar.isEmpty()
        )
    }
}
