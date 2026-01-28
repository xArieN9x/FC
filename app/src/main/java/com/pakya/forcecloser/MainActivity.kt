package com.pakya.forcecloser;

import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity {
    private static final String TARGET_PACKAGE = "com.example.cedokbooster";
    Button btnForceClose;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        btnForceClose = findViewById(R.id.btnForceClose);
        btnForceClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                forceCloseApp();
            }
        });
    }

    private void forceCloseApp() {
        try {
            android.os.Process.killProcess(getPackagePid());
            Toast.makeText(this, "App ditutup paksa", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            try {
                Runtime.getRuntime().exec("am force-stop " + TARGET_PACKAGE);
                Toast.makeText(this, "Force stop berjaya", Toast.LENGTH_SHORT).show();
            } catch (Exception ex) {
                Toast.makeText(this, "Gagal: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private int getPackagePid() {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            for (android.app.ActivityManager.RunningAppProcessInfo proc : am.getRunningAppProcesses()) {
                if (proc.processName.equals(TARGET_PACKAGE)) {
                    return proc.pid;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return -1;
    }
}
