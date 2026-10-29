package com.bridge.filt3r;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.widget.Button;

public class MainActivity extends Activity {

    private static final int REQUEST_VPN = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Button start = new Button(this);
        start.setText("Start");
        start.setOnClickListener(v -> {
            Intent consent = VpnService.prepare(this);
            if (consent != null) {
                startActivityForResult(consent, REQUEST_VPN);
            } else {
                startVpn();
            }
        });
        setContentView(start);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_VPN && resultCode == RESULT_OK) {
            startVpn();
        }
    }

    private void startVpn() {
        startService(new Intent(this, Filt3rVpnService.class));
    }
}
