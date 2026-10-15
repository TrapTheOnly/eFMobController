package com.padlayer;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * On-phone switch for the translator. Wireless debugging starts it, so a
 * computer does not have to stay connected.
 */
public final class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private TextView statusView;
    private TextView detailView;
    private EditText codeField;
    private EditText pairPortField;
    private EditText connectPortField;
    private Button pairButton;
    private Button actionButton;
    private boolean busy;
    private boolean translatorRunning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF10140F);
        root.setPadding(48, 72, 48, 48);
        root.setFocusableInTouchMode(true);

        TextView title = text(28, 0xFFE7F6E4);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setText("Padlayer");

        statusView = text(20, 0xFF9BD18A);
        statusView.setPadding(0, 28, 0, 12);

        codeField = field("Pairing code");
        pairPortField = field("Pairing port, if Pair cannot find it");
        connectPortField = field("Connection port, if Start cannot find it");
        String savedPort = prefs().getString("connectPort", "");
        if (!savedPort.isEmpty()) {
            connectPortField.setText(savedPort);
        }

        pairButton = button("Pair");
        pairButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onPair();
            }
        });
        actionButton = button("Start");
        actionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onAction();
            }
        });

        detailView = text(16, 0xFFB7C4B4);
        detailView.setText(help());

        root.addView(title);
        root.addView(statusView);
        root.addView(label("Pairing code"));
        root.addView(codeField);
        root.addView(label("Pairing port"));
        root.addView(pairPortField);
        root.addView(pairButton);
        root.addView(label("Connection port"));
        root.addView(connectPortField);
        root.addView(actionButton);
        root.addView(detailView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        root.requestFocus();
        showIdle();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void onPair() {
        if (busy) {
            return;
        }
        final String code = codeField.getText().toString().trim();
        final String portText = pairPortField.getText().toString().trim();
        if (!code.matches("\\d{6}")) {
            statusView.setText("Enter the 6-digit pairing code");
            return;
        }
        setBusy("Pairing…");
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    int port;
                    try {
                        port = portText.isEmpty()
                                ? AdbShell.findPairingPort(MainActivity.this)
                                : Integer.parseInt(portText.trim());
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("The pairing port is the number on the pairing screen.");
                    }
                    AdbShell.pair(MainActivity.this, port, code);
                    prefs().edit().putBoolean("paired", true).apply();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isDestroyed()) {
                                return;
                            }
                            clearBusy();
                            statusView.setText("Paired. Tap Start.");
                            detailView.setText(help());
                        }
                    });
                } catch (Exception e) {
                    showFailure(e);
                }
            }
        });
    }

    private void onAction() {
        if (busy) {
            return;
        }
        if (translatorRunning) {
            stopBridge();
        } else {
            startBridge();
        }
    }

    private void startBridge() {
        final String connectPort = connectPortField.getText().toString().trim();
        prefs().edit().putString("connectPort", connectPort).apply();
        setBusy("Starting the translator…");
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final String message = BridgeControl.start(MainActivity.this, connectPort);
                    translatorRunning = true;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isDestroyed()) {
                                return;
                            }
                            clearBusy();
                            statusView.setText("Translator is running");
                            actionButton.setText("Stop");
                            detailView.setText(message + "\n\n" + help());
                        }
                    });
                    watch(connectPort);
                } catch (Exception e) {
                    translatorRunning = false;
                    showFailure(e);
                }
            }
        });
    }

    private void stopBridge() {
        final String connectPort = connectPortField.getText().toString().trim();
        setBusy("Stopping the translator…");
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    BridgeControl.stop(MainActivity.this, connectPort);
                    translatorRunning = false;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isDestroyed()) {
                                return;
                            }
                            clearBusy();
                            statusView.setText("Translator is stopped");
                            actionButton.setText("Start");
                            detailView.setText(help());
                        }
                    });
                } catch (Exception e) {
                    showFailure(e);
                }
            }
        });
    }

    private void watch(String connectPort) throws Exception {
        for (int i = 0; i < 40 && translatorRunning; i++) {
            String status = BridgeControl.status(MainActivity.this, connectPort);
            final String tail = tailOf(status);
            final boolean grabbed = tail.contains("Grabbed the controller");
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (isDestroyed() || !translatorRunning || tail.isEmpty()) {
                        return;
                    }
                    statusView.setText(grabbed ? "Controller connected" : "Waiting for the Xbox pad");
                    detailView.setText(tail + "\n\n" + help());
                }
            });
            if (grabbed) {
                return;
            }
            Thread.sleep(1000);
        }
    }

    private void showIdle() {
        statusView.setText(prefs().getBoolean("paired", false)
                ? "Translator is stopped"
                : "Pair Wireless debugging once");
        actionButton.setText("Start");
        detailView.setText(help());
    }

    private void setBusy(String status) {
        busy = true;
        statusView.setText(status);
        pairButton.setEnabled(false);
        actionButton.setEnabled(false);
    }

    private void clearBusy() {
        busy = false;
        pairButton.setEnabled(true);
        actionButton.setEnabled(true);
    }

    private void showFailure(final Exception error) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isDestroyed()) {
                    return;
                }
                clearBusy();
                statusView.setText("Could not update the translator");
                actionButton.setText(translatorRunning ? "Stop" : "Start");
                String message = error.getMessage() == null ? error.toString() : error.getMessage();
                detailView.setText(message + "\n\n" + help());
            }
        });
    }

    private static String tailOf(String status) {
        int newline = status.indexOf('\n');
        if (newline < 0 || newline >= status.length() - 1) {
            return "";
        }
        return status.substring(newline + 1);
    }

    private static String help() {
        return "A normal app cannot open the controller devices. This app starts the translator on the phone through Wireless debugging. A computer is not required.\n\n"
                + "1. Settings, Developer options, Wireless debugging, turn it on.\n"
                + "2. Tap Pair device with pairing code and leave that screen open.\n"
                + "3. Type the 6-digit code here and tap Pair. Type the pairing port too if Pair asks for it.\n"
                + "4. Tap Start. Turn the Xbox pad on, then open a VS AI or friend match.\n\n"
                + "After Start, you can turn Wireless debugging off and unplug the cable. The translator keeps running until you tap Stop or reboot the phone. After a reboot, turn Wireless debugging on and tap Start again. You do not have to pair again.\n\n"
                + "If the pad was already on, Bluetooth turns off for a few seconds so the translator is the first controller, then turns back on. Earbuds reconnect on their own.";
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("padlayer", MODE_PRIVATE);
    }

    private TextView label(String value) {
        TextView view = text(14, 0xFF7E8B7A);
        view.setText(value);
        view.setPadding(0, 24, 0, 8);
        return view;
    }

    private EditText field(String hint) {
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setHintTextColor(0xFF7E8B7A);
        view.setTextColor(0xFFE7F6E4);
        view.setBackgroundColor(0xFF1C2418);
        view.setPadding(24, 20, 24, 20);
        view.setInputType(InputType.TYPE_CLASS_NUMBER);
        view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        view.setSingleLine(true);
        return view;
    }

    private Button button(String label) {
        Button view = new Button(this);
        view.setAllCaps(false);
        view.setTextSize(18);
        view.setText(label);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = 24;
        view.setLayoutParams(params);
        return view;
    }

    private TextView text(int sizeSp, int color) {
        TextView view = new TextView(this);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setGravity(Gravity.START);
        return view;
    }
}
