package de.blinkt.openvpn;

import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.os.RemoteException;
import androidx.appcompat.app.AppCompatActivity;
import com.facebook.react.bridge.ReactContext;

public class VpnAuthActivity extends AppCompatActivity {
    public static final String KEY_CONFIG = "config";
    public static final String KEY_USERNAME = "username";
    public static final String KEY_PASSWORD = "password";
    public static final String KEY_COUNTRY = "country";
    private String mConfig;
    private String mUsername;
    private String mPw;
    private String mCountry = "USA";
    ReactContext reactContext;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mConfig = getIntent().getStringExtra(KEY_CONFIG);
        mUsername = getIntent().getStringExtra(KEY_USERNAME);
        mPw = getIntent().getStringExtra(KEY_PASSWORD);
        if (getIntent().getStringExtra(KEY_COUNTRY) != null) {
            mCountry = getIntent().getStringExtra(KEY_COUNTRY);
        }

        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            startActivityForResult(intent, 0);
        } else {
            startVpn();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK) {
            startVpn();
        }
        finish();
    }

    private void startVpn() {
        try {
            de.blinkt.openvpn.OpenVpnApi.startVpnInternal(reactContext,mConfig,mCountry,mUsername,mPw);
        } catch (RemoteException e) {

        }
    }
}
