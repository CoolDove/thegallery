package com.cooldove.thegallery;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private TextView message;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        message = new TextView(this);
        message.setText("The Gallery\n\nChoose a folder to browse images");
        message.setTextSize(24);
        message.setGravity(Gravity.CENTER);
        message.setFocusable(true);
        message.setFocusableInTouchMode(true);
        message.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_BUTTON_A)) {
                openFolderPicker();
                return true;
            }
            return false;
        });

        setContentView(message, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        message.requestFocus();
    }

    private void openFolderPicker() {
        android.content.Intent intent = new android.content.Intent(
                android.content.Intent.ACTION_OPEN_DOCUMENT_TREE);
        startActivityForResult(intent, 1);
    }
}
