package com.reactlibrary;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.RemoteException;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import javax.annotation.Nullable;

import de.blinkt.openvpn.DisconnectVPNActivity;
import de.blinkt.openvpn.OpenVpnApi;
import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.core.Connection;
import de.blinkt.openvpn.core.ConnectionStatus;
import de.blinkt.openvpn.core.DeviceStateReceiver;
import de.blinkt.openvpn.core.IOpenVPNServiceInternal;
import de.blinkt.openvpn.core.OpenVPNManagement;
import de.blinkt.openvpn.core.OpenVPNService;
import de.blinkt.openvpn.core.OpenVPNStatusService;
import de.blinkt.openvpn.core.OpenVPNThread;
import de.blinkt.openvpn.core.OpenVpnManagementThread;
import de.blinkt.openvpn.core.ProfileManager;
import de.blinkt.openvpn.core.VpnStatus;

import static android.app.Activity.RESULT_OK;

public class OpenvpnModule extends ReactContextBaseJavaModule {

    protected OpenVPNService mService;
    private final ReactApplicationContext reactContext;
    private boolean vpnReady = false;
    public ActivityEventListener listener;
    public OpenVPNService vpnService = new OpenVPNService();

    public OpenvpnModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
    }

    @Override
    public String getName() {
        return "Openvpn";
    }

    @ReactMethod
    public void connect(final String config, final String country, final String username, final String password, Promise promise) {
        try {
            if (reactContext.getApplicationContext() != null) {
                LocalBroadcastManager.getInstance(reactContext.getApplicationContext()).registerReceiver(broadcastReceiver,new IntentFilter("connectionState"));
                OpenVPNService.setNotificationActivityClass(reactContext.getCurrentActivity().getClass());
            }
            OpenVpnApi.startVpn(reactContext, config, country, username, password);
        } catch (RemoteException e) {

        }

        promise.resolve(null);
    }

    @ReactMethod
    public void status(Promise promise) {
        if (OpenVPNService.getStatus().endsWith("CONNECTED")) {
            if (reactContext.getApplicationContext() != null) {
                OpenVPNService.setNotificationActivityClass(reactContext.getCurrentActivity().getClass());
                LocalBroadcastManager.getInstance(reactContext.getApplicationContext()).registerReceiver(broadcastReceiver,new IntentFilter("connectionState"));
            }
        }
        WritableMap params = Arguments.createMap();
        params.putString("state",OpenVPNService.getStatus());
        params.putString("localizedState",reactContext.getResources().getString(getLocalizedState(vpnService.getStatus())));
        promise.resolve(params);
    }

    @Override
    public void onCatalystInstanceDestroy() {
        super.onCatalystInstanceDestroy();
        LocalBroadcastManager.getInstance(reactContext.getApplicationContext()).unregisterReceiver(broadcastReceiver);

    }

    @ReactMethod
    public void disconnect(Promise promise) {
        Intent disconnectVPN = new Intent(reactContext, DisconnectVPNActivity.class);
        disconnectVPN.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        reactContext.startActivity(disconnectVPN);
        promise.resolve(true);

    }

    @ReactMethod
    private void prepare(Promise promise) {
        Log.d("TAG","preparing vpn");
        Log.d("TAG",String.valueOf(vpnReady));
        if (!vpnReady) {
            Intent intent = VpnService.prepare(reactContext);

            if (listener != null) {
                reactContext.removeActivityEventListener(listener);
                listener = null;
            }

            ActivityEventListener listener = new ActivityEventListener() {
                @Override
                public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
                    if (resultCode == RESULT_OK) {
                        promise.resolve(true);
                        vpnReady = true;
                    } else {
                        promise.resolve(false);
                        vpnReady = false;

                    }
                }

                @Override
                public void onNewIntent(Intent intent) {

                }
            };

            if (intent != null) {
                reactContext.addActivityEventListener(listener);
                Log.d("TAG","starting activity");
                reactContext.startActivityForResult(intent, 1, null);
            } else {
                promise.resolve(true);
                vpnReady = true;
            }
        } else {
            promise.resolve(true);
            vpnReady = true;
        }

    }

    BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            try {

                String duration = intent.getStringExtra("duration");
                String lastPacketReceive = intent.getStringExtra("lastPacketReceive");
                String byteIn = intent.getStringExtra("byteIn");
                String byteOut = intent.getStringExtra("byteOut");

                if (duration == null) duration = "00:00:00";
                if (lastPacketReceive == null) lastPacketReceive = "0";
                if (byteIn == null) byteIn = "Wait";
                if (byteOut == null) byteOut = "Wait";
                String byteinKb = byteIn.split("-")[0];
                String byteoutKb = byteOut.split("-")[0];

                WritableMap params = Arguments.createMap();
                params.putString("download",byteinKb);
                params.putString("upload",byteoutKb);
                params.putString("state",vpnService.getStatus());
                params.putString("duration",duration);
                params.putString("localizedState", reactContext.getResources().getString(getLocalizedState(vpnService.getStatus())));
                params.putString("packet",lastPacketReceive);
               if ( ProfileManager.getLastConnectedVpn() != null ) {
                   params.putString("serverName",ProfileManager.getLastConnectedVpn().mName);
               }
                sendEvent(reactContext,"onStateChange",params);


            } catch (Exception e) {
                e.printStackTrace();
            }

        }
    };


    private void sendEvent(ReactContext reactContext,
                           String eventName,
                           @Nullable WritableMap params) {
        reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(eventName, params);
    }


    private static int getLocalizedState(String state) {
        switch (state) {
            case "CONNECTING":
                return R.string.state_connecting;
            case "WAIT":
                return R.string.state_wait;
            case "AUTH":
                return R.string.state_auth;
            case "GET_CONFIG":
                return R.string.state_get_config;
            case "ASSIGN_IP":
                return R.string.state_assign_ip;
            case "ADD_ROUTES":
                return R.string.state_add_routes;
            case "CONNECTED":
                return R.string.state_connected;
            case "DISCONNECTED":
                return R.string.state_disconnected;
            case "RECONNECTING":
                return R.string.state_reconnecting;
            case "EXITING":
                return R.string.state_exiting;
            case "RESOLVE":
                return R.string.state_resolve;
            case "TCP_CONNECT":
                return R.string.state_tcp_connect;
            case "AUTH_PENDING":
                return R.string.state_auth_pending;
            default:
                return R.string.unknown_state;
        }

    }

}
