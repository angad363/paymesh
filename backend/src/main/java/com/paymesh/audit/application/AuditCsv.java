package com.paymesh.audit.application;

import com.paymesh.audit.domain.AuditEvent;

import java.util.List;

/**
 * The one CSV this capability writes: audit events in a window.
 *
 * <h2>RFC 4180 QUOTING ON EVERY FIELD, AND HERE IT IS NOT THEORETICAL</h2>
 *
 * {@code reason} is genuinely free text -- a suspension note a human typed -- so it can hold a
 * comma, a quote or a newline. Reporting's CSV quotes every field defensively even though none of
 * its columns needs it today; this one needs it, and the same code covers both. Losing the quoting
 * bet is not an exception, it is a row that parses into the wrong number of columns.
 *
 * <h2>HASHES, NOT VALUES</h2>
 *
 * {@code beforeHash}, {@code afterHash} and {@code ipHash} are exported as they are stored -- hashes.
 * A CSV of the audit log is still a privileged read, but it carries no plaintext secret or address,
 * because the table never held one.
 */
public final class AuditCsv {

    /** The header, pinned. A reviewer's importer is mapped against these names. */
    static final String HEADER =
        "auditEventId,occurredAt,actorType,actorId,merchantId,action,"
            + "resourceType,resourceId,reason,beforeHash,afterHash,ipHash";

    private AuditCsv() {
    }

    public static String render(List<AuditEvent> events) {
        StringBuilder csv = new StringBuilder(HEADER).append('\n');

        for (AuditEvent event : events) {
            csv.append(field(event.id().value())).append(',')
                .append(field(event.occurredAt().toString())).append(',')
                .append(field(event.actorType().name())).append(',')
                .append(field(event.actorId())).append(',')
                .append(field(event.merchantId() == null ? null : event.merchantId().value())).append(',')
                .append(field(event.action())).append(',')
                .append(field(event.resourceType())).append(',')
                .append(field(event.resourceId())).append(',')
                .append(field(event.reason())).append(',')
                .append(field(event.beforeHash())).append(',')
                .append(field(event.afterHash())).append(',')
                .append(field(event.ipHash()))
                .append('\n');
        }

        return csv.toString();
    }

    /** null becomes a bare empty field (absent), not {@code ""} (a zero-length string). */
    private static String field(String value) {
        if (value == null) {
            return "";
        }

        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
