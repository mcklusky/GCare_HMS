package com.gcare.hms.reporting;

import com.gcare.hms.reporting.ReportApi.Column;
import com.gcare.hms.reporting.ReportApi.ReportView;
import java.math.BigDecimal;
import java.util.Map;

/**
 * CSV for a report (RFC 4180, CRLF). Metadata lines first (PRD: date range, source, filters,
 * generation time, requesting user), prefixed with {@code #}. Text that a spreadsheet could run as
 * a formula is prefixed with an apostrophe.
 */
final class ReportCsv {

  private ReportCsv() {}

  static String write(ReportView v) {
    StringBuilder out = new StringBuilder();
    line(out, "# GCare HMS report: " + v.title());
    line(out, "# Facility: " + v.facilityName() + " (time zone " + v.timezone() + ")");
    line(out, "# Period: " + v.from() + " to " + v.to() + " (facility-local days)");
    line(out, "# Generated: " + v.generatedAt() + " by " + v.generatedBy().displayName());
    for (String note : v.notes()) {
      line(out, "# Note: " + note);
    }
    StringBuilder header = new StringBuilder();
    for (Column c : v.columns()) {
      header.append(header.isEmpty() ? "" : ",").append(cell(c.label()));
    }
    line(out, header.toString());
    for (Map<String, Object> row : v.rows()) {
      StringBuilder r = new StringBuilder();
      boolean first = true;
      for (Column c : v.columns()) {
        r.append(first ? "" : ",").append(cell(row.get(c.key())));
        first = false;
      }
      line(out, r.toString());
    }
    return out.toString();
  }

  private static void line(StringBuilder out, String text) {
    out.append(text).append("\r\n");
  }

  static String cell(Object value) {
    if (value == null) {
      return "";
    }
    if (value instanceof BigDecimal b) {
      return b.toPlainString();
    }
    if (value instanceof Number) {
      return value.toString();
    }
    String s = value.toString();
    if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
      s = "'" + s;
    }
    if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
      s = "\"" + s.replace("\"", "\"\"") + "\"";
    }
    return s;
  }
}
