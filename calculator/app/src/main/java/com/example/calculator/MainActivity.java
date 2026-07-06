package com.example.calculator;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.TextViewCompat;

public class MainActivity extends AppCompatActivity {

    // Simboli condivisi col motore di calcolo (U+2212 per il meno)
    private static final char MINUS = CalcEngine.MINUS;
    private static final char MUL = CalcEngine.MUL;
    private static final char DIV = CalcEngine.DIV;

    private static final int BG = 0xFF141419;
    private static final int NUM_BG = 0xFF2C2C31;
    private static final int FUNC_BG = 0xFF3A3A40;
    private static final int OP_BG = 0xFFFF9F0A;
    private static final int TXT_MAIN = 0xFFFFFFFF;
    private static final int TXT_DIM = 0xFF9A9AA0;
    private static final int TXT_CLEAR = 0xFFFF6B60;

    private final StringBuilder expr = new StringBuilder();
    private TextView history;   // riga piccola in alto (espressione valutata / anteprima)
    private TextView display;   // riga grande (espressione corrente o risultato)
    private boolean justEvaluated = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        float dp = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        int pad = (int) (12 * dp);
        root.setPadding(pad, pad, pad, pad);

        // --- Area display ---
        LinearLayout displayArea = new LinearLayout(this);
        displayArea.setOrientation(LinearLayout.VERTICAL);
        displayArea.setGravity(Gravity.BOTTOM | Gravity.END);
        LinearLayout.LayoutParams dap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 2f);
        displayArea.setLayoutParams(dap);

        history = new TextView(this);
        history.setTextColor(TXT_DIM);
        history.setTextSize(22);
        history.setGravity(Gravity.END);
        history.setSingleLine(true);
        displayArea.addView(history);

        display = new TextView(this);
        display.setText("0");
        display.setTextColor(TXT_MAIN);
        display.setGravity(Gravity.END);
        display.setSingleLine(true);
        display.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                display, 24, 60, 2, TypedValue.COMPLEX_UNIT_SP);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        mp.topMargin = (int) (8 * dp);
        display.setLayoutParams(mp);
        displayArea.addView(display);

        root.addView(displayArea);

        // --- Tastiera ---
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(4);
        grid.setRowCount(5);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 5f);
        grid.setLayoutParams(gp);
        root.addView(grid);

        String[] labels = {
                "C", "⌫", "%", "÷",
                "7", "8", "9", "×",
                "4", "5", "6", "−",
                "1", "2", "3", "+",
                "0", ".", "="
        };

        float radius = 26 * dp;
        for (String label : labels) {
            Button b = new Button(this);
            b.setText(label);
            b.setAllCaps(false);
            b.setTextSize(26);

            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(radius);

            boolean isOp = label.equals("÷") || label.equals("×")
                    || label.equals("−") || label.equals("+") || label.equals("=");
            boolean isFunc = label.equals("C") || label.equals("⌫") || label.equals("%");

            if (isOp) {
                shape.setColor(OP_BG);
                b.setTextColor(TXT_MAIN);
                b.setTextSize(30);
            } else if (isFunc) {
                shape.setColor(FUNC_BG);
                b.setTextColor(label.equals("C") ? TXT_CLEAR : TXT_MAIN);
            } else {
                shape.setColor(NUM_BG);
                b.setTextColor(TXT_MAIN);
            }
            b.setBackground(shape);

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = 0;
            int span = label.equals("0") ? 2 : 1;
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, span, (float) span);
            lp.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
            int m = (int) (5 * dp);
            lp.setMargins(m, m, m, m);
            b.setLayoutParams(lp);

            b.setOnClickListener(this::onButton);
            grid.addView(b);
        }

        setContentView(root);
    }

    // ------------------- Gestione input -------------------

    private void onButton(View v) {
        String t = ((Button) v).getText().toString();
        char c = t.charAt(0);

        switch (t) {
            case "C":
                expr.setLength(0);
                history.setText("");
                justEvaluated = false;
                render();
                return;
            case "⌫":
                if (justEvaluated) {
                    history.setText("");
                    justEvaluated = false;
                }
                if (expr.length() > 0) expr.deleteCharAt(expr.length() - 1);
                render();
                return;
            case "=":
                onEquals();
                return;
            case "%":
                if (expr.length() > 0 && Character.isDigit(last())) {
                    if (justEvaluated) { history.setText(""); justEvaluated = false; }
                    expr.append('%');
                    render();
                }
                return;
            case ".":
                onDot();
                return;
        }

        if (isOperator(c)) {
            onOperator(c);
        } else {
            onDigit(t);
        }
    }

    private void onDigit(String d) {
        if (justEvaluated) {
            // dopo "=", un numero inizia un calcolo nuovo
            expr.setLength(0);
            history.setText("");
            justEvaluated = false;
        }
        if (expr.length() >= 64) return;
        if (last() == '%') return; // dopo % serve un operatore
        expr.append(d);
        render();
    }

    private void onDot() {
        if (justEvaluated) {
            expr.setLength(0);
            history.setText("");
            justEvaluated = false;
        }
        // il numero corrente ha già una virgola?
        for (int i = expr.length() - 1; i >= 0; i--) {
            char ch = expr.charAt(i);
            if (isOperator(ch) || ch == '%') break;
            if (ch == '.') return;
        }
        if (expr.length() == 0 || isOperator(last()) ) {
            expr.append("0.");
        } else if (last() == '%') {
            return;
        } else {
            expr.append('.');
        }
        render();
    }

    private void onOperator(char op) {
        if (justEvaluated) {
            // dopo "=", un operatore continua dal risultato
            history.setText("");
            justEvaluated = false;
        }
        if (expr.length() == 0) {
            if (op == MINUS) { expr.append(MINUS); render(); }
            return;
        }
        if (last() == '.') expr.deleteCharAt(expr.length() - 1);
        if (isOperator(last())) {
            // "5×" + "−" → meno unario; altrimenti sostituisce l'operatore
            if (op == MINUS && (last() == MUL || last() == DIV)) {
                expr.append(MINUS);
            } else {
                while (expr.length() > 0 && isOperator(last())) {
                    expr.deleteCharAt(expr.length() - 1);
                }
                if (expr.length() > 0) expr.append(op);
                else if (op == MINUS) expr.append(MINUS);
            }
        } else {
            expr.append(op);
        }
        render();
    }

    private void onEquals() {
        String trimmed = trimTrailingOperators(expr.toString());
        if (trimmed.isEmpty()) return;
        Double res = evaluate(trimmed);
        if (res == null) return;
        String out = format(res);
        if (out == null) {
            history.setText(trimmed + " =");
            display.setText("Errore");
            expr.setLength(0);
            justEvaluated = true;
            return;
        }
        history.setText(trimmed + " =");
        display.setText(out);
        expr.setLength(0);
        expr.append(out);
        justEvaluated = true;
    }

    /** Aggiorna display principale e anteprima live. */
    private void render() {
        display.setText(expr.length() == 0 ? "0" : expr.toString());
        if (justEvaluated) return;
        String trimmed = trimTrailingOperators(expr.toString());
        if (hasOperatorOrPercent(trimmed)) {
            Double res = evaluate(trimmed);
            String out = (res != null) ? format(res) : null;
            history.setText(out != null ? "= " + out : "");
        } else {
            history.setText("");
        }
    }

    // ------------------- Collegamento al motore -------------------

    private static boolean isOperator(char c) { return CalcEngine.isOperator(c); }

    private static boolean hasOperatorOrPercent(String s) { return CalcEngine.hasOperatorOrPercent(s); }

    private static String trimTrailingOperators(String s) { return CalcEngine.trimTrailingOperators(s); }

    private static Double evaluate(String s) { return CalcEngine.evaluate(s); }

    private static String format(double d) { return CalcEngine.format(d); }

    private char last() {
        return expr.length() == 0 ? ' ' : expr.charAt(expr.length() - 1);
    }
}
