package com.gcare.hms.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.gcare.hms.TestcontainersConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Runs the real backup, restore and drill scripts (infra/backup; docs/23) inside the test
 * PostgreSQL container, which has pg_dump, pg_restore, psql and gpg (task 5.4).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BackupRestoreDrillTests {

  private static final Path SCRIPTS = Path.of("..", "infra", "backup");

  @Autowired private PostgreSQLContainer postgres;

  @BeforeEach
  void copyScripts() throws Exception {
    for (String f : new String[] {"lib.sh", "backup.sh", "restore.sh", "drill.sh"}) {
      assertThat(Files.exists(SCRIPTS.resolve(f))).as(f).isTrue();
      postgres.copyFileToContainer(
          MountableFile.forHostPath(SCRIPTS.resolve(f)), "/tmp/gcare-backup/" + f);
    }
  }

  private ExecResult bash(String script) throws Exception {
    Map<String, String> env =
        Map.of(
            "PGHOST", "localhost",
            "PGUSER", postgres.getUsername(),
            "PGPASSWORD", postgres.getPassword(),
            "PGDATABASE", postgres.getDatabaseName());
    StringBuilder exports = new StringBuilder();
    env.forEach((k, v) -> exports.append("export ").append(k).append("='").append(v).append("'; "));
    return postgres.execInContainer("bash", "-c", exports + script);
  }

  @Test
  void theDrillBacksUpRestoresIntoAScratchDatabaseAndVerifiesIt() throws Exception {
    ExecResult r = bash("bash /tmp/gcare-backup/drill.sh");
    assertThat(r.getExitCode()).as(r.getStderr()).isZero();
    assertThat(r.getStdout())
        .contains("restore_verified=true", "drill_result=PASSED")
        .containsPattern("restore_flyway_version=\\d+")
        .containsPattern("restore_seconds=\\d+");
    // The scratch database is gone afterwards.
    ExecResult left =
        bash(
            "psql -X -At -d postgres -c \"SELECT count(*) FROM pg_database WHERE datname LIKE 'gcare_restore_drill_%'\"");
    assertThat(left.getStdout().trim()).isEqualTo("0");
  }

  @Test
  void restoreRefusesDamagedBackupsNonEmptyTargetsAndMissingKeys() throws Exception {
    String setup =
        """
        set -e
        W=$(mktemp -d); export GNUPGHOME=$W/gnupg; mkdir -m 700 $GNUPGHOME
        gpg --batch --quiet --passphrase '' --quick-gen-key 'Test <t@example.invalid>' default default never 2>/dev/null
        FPR=$(gpg --batch --list-keys --with-colons | awk -F: '/^fpr:/ {print $10; exit}')
        BACKUP_DIR=$W/b BACKUP_GPG_RECIPIENT=$FPR BACKUP_RETENTION_DAYS=1 bash /tmp/gcare-backup/backup.sh >/dev/null
        F=$(ls $W/b/*.dump.gpg)
        ! grep -q 'CREATE TABLE' $F
        set +e
        """;
    ExecResult r =
        bash(
            setup
                + """
                # 1. Restoring over the live (non-empty) database is refused.
                bash /tmp/gcare-backup/restore.sh $F >/dev/null 2>&1; echo "nonempty=$?"
                # 2. A damaged file is refused before anything is restored.
                cp $F $W/bad.dump.gpg; cp ${F%.dump.gpg}.manifest $W/bad.manifest
                printf 'x' >> $W/bad.dump.gpg
                psql -X -q -d postgres -c 'CREATE DATABASE t_damaged'
                PGDATABASE=t_damaged bash /tmp/gcare-backup/restore.sh $W/bad.dump.gpg >/dev/null 2>&1; echo "damaged=$?"
                # 3. Without the private key nothing can be restored.
                psql -X -q -d postgres -c 'CREATE DATABASE t_nokey'
                GNUPGHOME=$W/empty; mkdir -m 700 $W/empty
                GNUPGHOME=$W/empty PGDATABASE=t_nokey bash /tmp/gcare-backup/restore.sh $F >/dev/null 2>&1; echo "nokey=$?"
                psql -X -q -d postgres -c 'DROP DATABASE t_damaged' -c 'DROP DATABASE t_nokey'
                # 4. Required settings have no defaults.
                env -u BACKUP_RETENTION_DAYS BACKUP_DIR=$W/c BACKUP_GPG_RECIPIENT=$FPR bash /tmp/gcare-backup/backup.sh >/dev/null 2>&1; echo "noretention=$?"
                """);
    assertThat(r.getStdout())
        .as(r.getStderr())
        .contains("nonempty=4", "damaged=3", "noretention=1")
        .doesNotContain("nokey=0");
  }
}
