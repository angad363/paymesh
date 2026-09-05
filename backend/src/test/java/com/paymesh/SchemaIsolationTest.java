package com.paymesh;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * THE SCHEMA-PER-SERVICE FENCE, made executable (ADR-038 section 4).
 * <p>
 * V38 carved the one public schema into nine service schemas and created a NOLOGIN role per
 * service granted USAGE on ONLY its own schema. That grant is the isolation guarantee -- a service
 * physically cannot read another's tables -- and this proves it rather than trusting it: for each
 * role it {@code SET ROLE}s into it, reads one of its own tables (allowed), and reads another
 * service's table (refused by the missing schema-USAGE grant). If a future migration widened a
 * grant by accident, exactly this test would catch it.
 * <p>
 * The single-process app does NOT connect as these roles yet; it uses the connection's superuser,
 * which is why the test can {@code SET ROLE} into each. Each service adopts its role verbatim when
 * it is extracted (3B+).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class SchemaIsolationTest {

    // Each service schema -> one representative table in it. The role <schema>_svc may read this
    // one (and every other in the same schema) and nothing in any other schema.
    private static final Map<String, String> OWN_TABLE = new LinkedHashMap<>();
    static {
        OWN_TABLE.put("identity", "users");
        OWN_TABLE.put("merchant", "merchants");
        OWN_TABLE.put("payment", "orders");
        OWN_TABLE.put("ledger", "ledger_accounts");
        OWN_TABLE.put("settlement", "payouts");
        OWN_TABLE.put("risk", "risk_assessments");
        OWN_TABLE.put("simulator", "provider_payments");
        OWN_TABLE.put("webhook", "webhook_endpoints");
        OWN_TABLE.put("engagement", "notifications");
    }

    @Autowired
    private DataSource dataSource;

    @Test
    void eachServiceRoleCanReadOnlyItsOwnSchema() throws SQLException {
        List<String> schemas = List.copyOf(OWN_TABLE.keySet());
        for (int i = 0; i < schemas.size(); i++) {
            String schema = schemas.get(i);
            String role = schema + "_svc";
            String ownTable = schema + "." + OWN_TABLE.get(schema);
            // Round-robin to a guaranteed-different schema, so every pair is exercised across the run.
            String otherSchema = schemas.get((i + 1) % schemas.size());
            String otherTable = otherSchema + "." + OWN_TABLE.get(otherSchema);

            try (Connection c = dataSource.getConnection()) {
                try {
                    exec(c, "SET ROLE " + role);

                    assertThatCode(() -> select(c, ownTable))
                            .as("%s may read its own %s", role, ownTable)
                            .doesNotThrowAnyException();

                    assertThatThrownBy(() -> select(c, otherTable))
                            .as("%s may NOT read %s in another schema", role, otherTable)
                            .isInstanceOf(SQLException.class)
                            .hasMessageContaining("permission denied");
                } finally {
                    // Never hand a SET ROLE back to the pool, even if an assertion above failed.
                    exec(c, "RESET ROLE");
                }
            }
        }
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static void select(Connection c, String qualifiedTable) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeQuery("SELECT 1 FROM " + qualifiedTable + " LIMIT 1");
        }
    }
}
