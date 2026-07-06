package com.example.calculator;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;

/**
 * Motore di calcolo puro, senza dipendenze Android (testabile da solo).
 * Simboli attesi: cifre, '.', '%', '+', '−' (U+2212), '×', '÷'.
 */
public final class CalcEngine {

    public static final char MINUS = '−';
    public static final char PLUS = '+';
    public static final char MUL = '×';
    public static final char DIV = '÷';

    private CalcEngine() {}

    public static boolean isOperator(char c) {
        return c == PLUS || c == MINUS || c == MUL || c == DIV;
    }

    public static boolean hasOperatorOrPercent(String s) {
        for (int i = 1; i < s.length(); i++) { // salta l'eventuale meno iniziale
            char c = s.charAt(i);
            if (isOperator(c) || c == '%') return true;
        }
        return false;
    }

    public static String trimTrailingOperators(String s) {
        int end = s.length();
        while (end > 0 && (isOperator(s.charAt(end - 1)) || s.charAt(end - 1) == '.')) end--;
        return s.substring(0, end);
    }

    /**
     * Valuta l'espressione con precedenza (× ÷ prima di + −).
     * La percentuale con + e − è relativa al totale a sinistra:
     * 10 − 10% = 9. Con × e ÷ vale come divisione per 100: 50 × 10% = 5.
     * Ritorna null se l'espressione è incompleta o malformata.
     */
    public static Double evaluate(String s) {
        int n = s.length();
        List<Double> vals = new ArrayList<>();
        List<Boolean> pct = new ArrayList<>();
        List<Character> ops = new ArrayList<>();
        boolean expectNumber = true;
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (isOperator(c) && !(c == MINUS && expectNumber)) {
                if (expectNumber) return null;
                ops.add(c);
                expectNumber = true;
                i++;
            } else {
                int sign = 1;
                while (i < n && s.charAt(i) == MINUS) { sign = -sign; i++; }
                int start = i;
                while (i < n && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
                if (start == i) return null;
                double v;
                try {
                    v = Double.parseDouble(s.substring(start, i));
                } catch (NumberFormatException e) {
                    return null;
                }
                boolean isPct = false;
                if (i < n && s.charAt(i) == '%') { isPct = true; i++; }
                vals.add(sign * v);
                pct.add(isPct);
                expectNumber = false;
            }
        }
        if (expectNumber || vals.isEmpty()) return null;

        // Passata 1: risolve × e ÷ (il % qui vale /100)
        List<Double> terms = new ArrayList<>();
        List<Boolean> termPct = new ArrayList<>();
        List<Character> addOps = new ArrayList<>();
        double curVal = vals.get(0);
        boolean curPct = pct.get(0);
        for (int k = 0; k < ops.size(); k++) {
            char op = ops.get(k);
            double nextVal = vals.get(k + 1);
            boolean nextPct = pct.get(k + 1);
            if (op == MUL || op == DIV) {
                double a = curPct ? curVal / 100.0 : curVal;
                double b = nextPct ? nextVal / 100.0 : nextVal;
                curVal = (op == MUL) ? a * b : a / b;
                curPct = false;
            } else {
                terms.add(curVal);
                termPct.add(curPct);
                addOps.add(op);
                curVal = nextVal;
                curPct = nextPct;
            }
        }
        terms.add(curVal);
        termPct.add(curPct);

        // Passata 2: somma e sottrazione (il % è relativo al totale corrente)
        double total = termPct.get(0) ? terms.get(0) / 100.0 : terms.get(0);
        for (int k = 0; k < addOps.size(); k++) {
            double operand = termPct.get(k + 1)
                    ? total * terms.get(k + 1) / 100.0
                    : terms.get(k + 1);
            total = (addOps.get(k) == PLUS) ? total + operand : total - operand;
        }
        return total;
    }

    /** Formatta senza code binarie (0.30000000000000004 → 0.3). Null se non rappresentabile. */
    public static String format(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return null;
        BigDecimal bd = new BigDecimal(d, new MathContext(12)).stripTrailingZeros();
        if (bd.scale() < 0) bd = bd.setScale(0);
        String plain = bd.toPlainString();
        if (plain.length() > 24) return String.valueOf(d).replace('-', MINUS);
        return plain.replace('-', MINUS);
    }
}
