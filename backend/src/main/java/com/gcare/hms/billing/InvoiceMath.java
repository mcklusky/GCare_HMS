package com.gcare.hms.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.List;

/**
 * Invoice arithmetic (docs/19 §3, decided 2026-11-23): net = unit price × quantity (exact); tax is
 * computed per line and rounded half-up to the currency's decimals; totals are sums of the rounded
 * lines, so every line reconciles exactly with the total.
 */
final class InvoiceMath {

  private InvoiceMath() {}

  record LineAmounts(BigDecimal net, BigDecimal tax, BigDecimal total) {}

  record Totals(BigDecimal subtotal, BigDecimal tax, BigDecimal total) {}

  /** Decimals of an ISO 4217 currency, or -1 if it cannot be charged. */
  static int decimals(String currency) {
    try {
      return Currency.getInstance(currency).getDefaultFractionDigits();
    } catch (IllegalArgumentException e) {
      return -1;
    }
  }

  static LineAmounts line(
      BigDecimal unitPrice, int quantity, BigDecimal ratePercent, String currency) {
    int digits = decimals(currency);
    if (digits < 0) {
      throw new IllegalArgumentException("Not a chargeable currency: " + currency);
    }
    BigDecimal net = unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(digits);
    BigDecimal tax =
        ratePercent == null
            ? BigDecimal.ZERO.setScale(digits)
            : net.multiply(ratePercent)
                .divide(BigDecimal.valueOf(100))
                .setScale(digits, RoundingMode.HALF_UP);
    return new LineAmounts(net, tax, net.add(tax));
  }

  static Totals totals(List<LineAmounts> lines, String currency) {
    BigDecimal zero = BigDecimal.ZERO.setScale(Math.max(decimals(currency), 0));
    BigDecimal net = zero;
    BigDecimal tax = zero;
    for (LineAmounts l : lines) {
      net = net.add(l.net());
      tax = tax.add(l.tax());
    }
    return new Totals(net, tax, net.add(tax));
  }
}
