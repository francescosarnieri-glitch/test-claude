package com.example.calculator;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private TextView display;
    private double firstOperand = 0;
    private String pendingOp = null;
    private boolean startNew = true;

    private static final int BG = 0xFF1C1C1E;
    private static final int DISPLAY_BG = 0xFF000000;
    private static final int NUM_BG = 0xFF333336;
    private static final int OP_BG = 0xFFFF9500;
    private static final int FUNC_BG = 0xFFA5A5A5;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(24, 24, 24, 24);

        display = new TextView(this);
        display.setText("0");
        display.setTextColor(0xFFFFFFFF);
        display.setTextSize(56);
        display.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        display.setBackgroundColor(DISPLAY_BG);
        display.setPadding(32, 48, 32, 48);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 2f);
        display.setLayoutParams(dp);
        root.addView(display);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(4);
        grid.setRowCount(5);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 5f);
        grid.setLayoutParams(gp);
        root.addView(grid);

        String[] labels = {
                "C", "±", "%", "÷",
                "7", "8", "9", "×",
                "4", "5", "6", "−",
                "1", "2", "3", "+",
                "0", ".", "⌫", "="
        };

        for (String label : labels) {
            Button b = new Button(this);
            b.setText(label);
            b.setTextSize(26);
            b.setAllCaps(false);
            int bg = NUM_BG;
            if (label.equals("÷") || label.equals("×") || label.equals("−")
                    || label.equals("+") || label.equals("=")) {
                bg = OP_BG;
                b.setTextColor(0xFFFFFFFF);
            } else if (label.equals("C") || label.equals("±") || label.equals("%")) {
                bg = FUNC_BG;
                b.setTextColor(0xFF000000);
            } else {
                b.setTextColor(0xFFFFFFFF);
            }
            b.setBackgroundColor(bg);

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = 0;
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
            lp.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
            lp.setMargins(8, 8, 8, 8);
            b.setLayoutParams(lp);

            b.setOnClickListener(this::onButton);
            grid.addView(b);
        }

        setContentView(root);
    }

    private void onButton(View v) {
        String t = ((Button) v).getText().toString();

        switch (t) {
            case "C":
                firstOperand = 0;
                pendingOp = null;
                startNew = true;
                display.setText("0");
                break;
            case "⌫": {
                String cur = display.getText().toString();
                if (cur.length() > 1) {
                    display.setText(cur.substring(0, cur.length() - 1));
                } else {
                    display.setText("0");
                    startNew = true;
                }
                break;
            }
            case "±": {
                double val = current();
                display.setText(format(-val));
                break;
            }
            case "%": {
                double val = current();
                display.setText(format(val / 100.0));
                break;
            }
            case "+": case "−": case "×": case "÷":
                applyPending();
                pendingOp = t;
                startNew = true;
                break;
            case "=":
                applyPending();
                pendingOp = null;
                startNew = true;
                break;
            case ".":
                if (startNew) {
                    display.setText("0.");
                    startNew = false;
                } else if (!display.getText().toString().contains(".")) {
                    display.append(".");
                }
                break;
            default: // digits
                if (startNew) {
                    display.setText(t);
                    startNew = false;
                } else {
                    String cur = display.getText().toString();
                    if (cur.equals("0")) {
                        display.setText(t);
                    } else {
                        display.append(t);
                    }
                }
                break;
        }
    }

    private void applyPending() {
        double val = current();
        if (pendingOp == null) {
            firstOperand = val;
            return;
        }
        switch (pendingOp) {
            case "+": firstOperand += val; break;
            case "−": firstOperand -= val; break;
            case "×": firstOperand *= val; break;
            case "÷":
                if (val == 0) {
                    display.setText("Errore");
                    firstOperand = 0;
                    pendingOp = null;
                    return;
                }
                firstOperand /= val;
                break;
        }
        display.setText(format(firstOperand));
    }

    private double current() {
        try {
            return Double.parseDouble(display.getText().toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String format(double d) {
        if (d == Math.floor(d) && !Double.isInfinite(d)) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }
}
