

package de.blinkt.openvpn.core;

import android.Manifest.permission;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.UiModeManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.VpnService;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Handler.Callback;
import android.os.IBinder;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.system.OsConstants;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.Calendar;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Vector;

import de.blinkt.openvpn.DisconnectVPNActivity;
import de.blinkt.openvpn.LaunchVPN;
import com.reactlibrary.R;
import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.api.ExternalAppDatabase;
import de.blinkt.openvpn.core.VpnStatus.ByteCountListener;
import de.blinkt.openvpn.core.VpnStatus.StateListener;
import de.blinkt.openvpn.utils.TotalTraffic;

import static de.blinkt.openvpn.core.ConnectionStatus.LEVEL_CONNECTED;
import static de.blinkt.openvpn.core.ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT;
import static de.blinkt.openvpn.core.NetworkSpace.IpAddress;

public class OpenVPNService extends VpnService implements StateListener, Callback, ByteCountListener, IOpenVPNServiceInternal {

    private String byteIn, byteOut;
    private String duration;

    public static final String START_SERVICE = "de.blinkt.openvpn.START_SERVICE";
    public static final String START_SERVICE_STICKY = "de.blinkt.openvpn.START_SERVICE_STICKY";
    public static final String ALWAYS_SHOW_NOTIFICATION = "de.blinkt.openvpn.NOTIFICATION_ALWAYS_VISIBLE";
    public static final String DISCONNECT_VPN = "de.blinkt.openvpn.DISCONNECT_VPN";
    public static final String NOTIFICATION_CHANNEL_BG_ID = "openvpn_bg";
    public static final String NOTIFICATION_CHANNEL_NEWSTATUS_ID = "openvpn_newstat";
    public static final String NOTIFICATION_CHANNEL_USERREQ_ID = "openvpn_userreq";

    public static final String VPNSERVICE_TUN = "vpnservice-tun";
    public final static String ORBOT_PACKAGE_NAME = "org.torproject.android";
    private static final String PAUSE_VPN = "de.blinkt.openvpn.PAUSE_VPN";
    private static final String RESUME_VPN = "com.reactlibrary.RESUME_VPN";

    public static final String EXTRA_CHALLENGE_TXT = "de.blinkt.openvpn.core.CR_TEXT_CHALLENGE";
    public static final String EXTRA_CHALLENGE_OPENURL = "de.blinkt.openvpn.core.OPENURL_CHALLENGE";

    private static final int PRIORITY_MIN = -2;
    private static final int PRIORITY_DEFAULT = 0;
    private static final int PRIORITY_MAX = 2;
    private static boolean mNotificationAlwaysVisible = false;
    private static Class<? extends Activity> mNotificationActivityClass;
    private final Vector<String> mDnslist = new Vector<>();
    private final NetworkSpace mRoutes = new NetworkSpace();
    private final NetworkSpace mRoutesv6 = new NetworkSpace();
    private final Object mProcessLock = new Object();
    private String lastChannel;
    private Thread mProcessThread = null;
    private VpnProfile mProfile;
    private String mDomain = null;
    private CIDRIP mLocalIP = null;
    private int mMtu;
    private String mLocalIPv6 = null;
    private DeviceStateReceiver mDeviceStateReceiver;
    private boolean mDisplayBytecount = false;
    private boolean mStarting = false;
    private long mConnecttime;
    private OpenVPNManagement mManagement;


    private final IBinder mBinder = new LocalBinder();
    private static String state = "";
    boolean flag = false;
    private String mLastTunCfg;
    private String mRemoteGW;
    private Handler guiHandler;
    private Toast mlastToast;
    private Runnable mOpenVPNThread;

    public static String humanReadableByteCount(long bytes, boolean speed, Resources res) {
        if (speed)
            bytes = bytes * 8;
        int unit = speed ? 1000 : 1024;


        int exp = Math.max(0, Math.min((int) (Math.log(bytes) / Math.log(unit)), 3));

        float bytesUnit = (float) (bytes / Math.pow(unit, exp));

        if (speed)
            switch (exp) {
                case 0:
                    return res.getString(R.string.bits_per_second, bytesUnit);
                case 1:
                    return res.getString(R.string.kbits_per_second, bytesUnit);
                case 2:
                    return res.getString(R.string.mbits_per_second, bytesUnit);
                default:
                    return res.getString(R.string.gbits_per_second, bytesUnit);
            }
        else
            switch (exp) {
                case 0:
                    return res.getString(R.string.volume_byte, bytesUnit);
                case 1:
                    return res.getString(R.string.volume_kbyte, bytesUnit);
                case 2:
                    return res.getString(R.string.volume_mbyte, bytesUnit);
                default:
                    return res.getString(R.string.volume_gbyte, bytesUnit);

            }
    }


    public static void setNotificationActivityClass(Class<? extends Activity> activityClass) {
        mNotificationActivityClass = activityClass;
    }

    PendingIntent getContentIntent() {
        try {
            if (mNotificationActivityClass != null) {
                Intent intent = new Intent(getBaseContext(), mNotificationActivityClass);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                        Intent.FLAG_ACTIVITY_SINGLE_TOP);
                return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
            }
        } catch (Exception e) {
            Log.e(this.getClass().getCanonicalName(), "Build detail intent error", e);
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public void addAllowedExternalApp(String packagename) throws RemoteException {
        ExternalAppDatabase extapps = new ExternalAppDatabase(OpenVPNService.this);
        extapps.addApp(packagename);
    }

    @Override
    public boolean isAllowedExternalApp(String packagename) throws RemoteException {
        ExternalAppDatabase extapps = new ExternalAppDatabase(OpenVPNService.this);
        return extapps.checkRemoteActionPermission(this, packagename);
    }

    @Override
    public void challengeResponse(String response) throws RemoteException {
        if (mManagement != null) {
            String b64response = Base64.encodeToString(response.getBytes(Charset.forName("UTF-8")), Base64.DEFAULT);
            mManagement.sendCRResponse(b64response);
        }
    }


    @Override
    public IBinder onBind(Intent intent) {
        String action = intent.getAction();
        if (action != null && action.equals(START_SERVICE))
            return mBinder;
        else
            return super.onBind(intent);
    }

    @Override
    public void onRevoke() {
        VpnStatus.logError(R.string.permission_revoked);
        mManagement.stopVPN(false);
        endVpnService();
    }

    public void openvpnStopped() {
        endVpnService();
    }

    public void endVpnService() {
        synchronized (mProcessLock) {
            mProcessThread = null;
        }
        VpnStatus.removeByteCountListener(this);
        unregisterDeviceStateReceiver();
        ProfileManager.setConntectedVpnProfileDisconnected(this);
        mOpenVPNThread = null;
        if (!mStarting) {
            stopForeground(!mNotificationAlwaysVisible);

            if (!mNotificationAlwaysVisible) {
                stopSelf();
                VpnStatus.removeStateListener(this);
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private String createNotificationChannel(String channelId, String channelName) {
        NotificationChannel chan = new NotificationChannel(channelId,
                channelName, NotificationManager.IMPORTANCE_NONE);
        chan.setLightColor(Color.BLUE);
        chan.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        NotificationManager service = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        service.createNotificationChannel(chan);
        return channelId;
    }

    private void showNotification(final String msg, String tickerText, @NonNull String channel,
                                  long when, ConnectionStatus status, Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            channel = createNotificationChannel(channel, channel + " Name");
        } else {
            channel = "";
        }

        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        android.app.Notification.Builder nbuilder = new Notification.Builder(this);

        int priority;
        if (channel.equals(NOTIFICATION_CHANNEL_BG_ID))
            priority = PRIORITY_MIN;
        else if (channel.equals(NOTIFICATION_CHANNEL_USERREQ_ID))
            priority = PRIORITY_MAX;
        else
            priority = PRIORITY_DEFAULT;

        if (mProfile != null)
            nbuilder.setContentTitle(getString(R.string.notifcation_title, mProfile.mName));
        else
            nbuilder.setContentTitle(getString(R.string.notifcation_title_notconnect));

        nbuilder.setContentText(msg);
        nbuilder.setOnlyAlertOnce(true);
        nbuilder.setOngoing(true);
        nbuilder.setSmallIcon(R.drawable.ic_notification);
        if (status == LEVEL_WAITING_FOR_USER_INPUT) {

            PendingIntent pIntent = PendingIntent.getActivity(this, 0, intent, 0);
            nbuilder.setContentIntent(pIntent);
        } else {
            PendingIntent contentPendingIntent = getContentIntent();
            if (contentPendingIntent != null) {
                nbuilder.setContentIntent(contentPendingIntent);
            } else {
                nbuilder.setContentIntent(getGraphPendingIntent());
            }
        }

        if (when != 0)
            nbuilder.setWhen(when);


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            jbNotificationExtras(priority, nbuilder);
            addVpnActionsToNotification(nbuilder);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            lpNotificationExtras(nbuilder, Notification.CATEGORY_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            nbuilder.setChannelId(channel);
            if (mProfile != null)

                nbuilder.setShortcutId(mProfile.getUUIDString());

        }

        if (tickerText != null && !tickerText.equals(""))
            nbuilder.setTicker(tickerText);
        try {
            Notification notification = nbuilder.build();

            int notificationId = channel.hashCode();

            mNotificationManager.notify(notificationId, notification);

            startForeground(notificationId, notification);

            if (lastChannel != null && !channel.equals(lastChannel)) {

                mNotificationManager.cancel(lastChannel.hashCode());
            }
        } catch (Throwable th) {
            Log.e(getClass().getCanonicalName(), "Error when show notification", th);
        }

        if (runningOnAndroidTV() && !(priority < 0))
            guiHandler.post(new Runnable() {

                @Override
                public void run() {

                    if (mlastToast != null)
                        mlastToast.cancel();
                    String toastText = String.format(Locale.getDefault(), "%s - %s", mProfile.mName, msg);
                    mlastToast = Toast.makeText(getBaseContext(), toastText, Toast.LENGTH_SHORT);
                    mlastToast.show();
                }
            });
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void lpNotificationExtras(Notification.Builder nbuilder, String category) {
        nbuilder.setCategory(category);
        nbuilder.setLocalOnly(true);

    }

    private boolean runningOnAndroidTV() {
        UiModeManager uiModeManager = (UiModeManager) getSystemService(UI_MODE_SERVICE);
        return uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private void jbNotificationExtras(int priority,
                                      android.app.Notification.Builder nbuilder) {
        try {
            if (priority != 0) {
                Method setpriority = nbuilder.getClass().getMethod("setPriority", int.class);
                setpriority.invoke(nbuilder, priority);

                Method setUsesChronometer = nbuilder.getClass().getMethod("setUsesChronometer", boolean.class);
                setUsesChronometer.invoke(nbuilder, true);

            }


        } catch (NoSuchMethodException | IllegalArgumentException |
                InvocationTargetException | IllegalAccessException e) {
            VpnStatus.logException(e);
        }

    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN)
    private void addVpnActionsToNotification(Notification.Builder nbuilder) {
        Intent disconnectVPN = new Intent(this, DisconnectVPNActivity.class);
        disconnectVPN.setAction(DISCONNECT_VPN);
        PendingIntent disconnectPendingIntent = PendingIntent.getActivity(this, 0, disconnectVPN, 0);

        nbuilder.addAction(R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.cancel_connection), disconnectPendingIntent);

        Intent pauseVPN = new Intent(this, OpenVPNService.class);
        if (mDeviceStateReceiver == null || !mDeviceStateReceiver.isUserPaused()) {
            pauseVPN.setAction(PAUSE_VPN);
            PendingIntent pauseVPNPending = PendingIntent.getService(this, 0, pauseVPN, 0);
            nbuilder.addAction(R.drawable.ic_menu_pause,
                    getString(R.string.pauseVPN), pauseVPNPending);

        } else {
            pauseVPN.setAction(RESUME_VPN);
            PendingIntent resumeVPNPending = PendingIntent.getService(this, 0, pauseVPN, 0);
            nbuilder.addAction(R.drawable.ic_menu_play,
                    getString(R.string.resumevpn), resumeVPNPending);
        }
    }

    PendingIntent getUserInputIntent(String needed) {
        Intent intent = new Intent(getApplicationContext(), LaunchVPN.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        intent.putExtra("need", needed);
        Bundle b = new Bundle();
        b.putString("need", needed);
        PendingIntent pIntent = PendingIntent.getActivity(this, 12, intent, 0);
        return pIntent;
    }

    PendingIntent getGraphPendingIntent() {



        Intent intent = new Intent();
        intent.setComponent(new ComponentName(this, getPackageName() + ".view.MainActivity"));

        intent.putExtra("PAGE", "graph");
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent startLW = PendingIntent.getActivity(this, 0, intent, 0);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        return startLW;

    }

    synchronized void registerDeviceStateReceiver(OpenVPNManagement magnagement) {

        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        mDeviceStateReceiver = new DeviceStateReceiver(magnagement);

        mDeviceStateReceiver.networkStateChange(this);

        registerReceiver(mDeviceStateReceiver, filter);
        VpnStatus.addByteCountListener(mDeviceStateReceiver);


    }

    synchronized void unregisterDeviceStateReceiver() {
        if (mDeviceStateReceiver != null)
            try {
                VpnStatus.removeByteCountListener(mDeviceStateReceiver);
                this.unregisterReceiver(mDeviceStateReceiver);
            } catch (IllegalArgumentException iae) {

                iae.printStackTrace();
            }
        mDeviceStateReceiver = null;


    }

    public void userPause(boolean shouldBePaused) {
        if (mDeviceStateReceiver != null)
            mDeviceStateReceiver.userPause(shouldBePaused);
    }

    @Override
    public boolean stopVPN(boolean replaceConnection) throws RemoteException {
        if (getManagement() != null)
            return getManagement().stopVPN(replaceConnection);
        else
            return false;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent != null && intent.getBooleanExtra(ALWAYS_SHOW_NOTIFICATION, false))
            mNotificationAlwaysVisible = true;

        VpnStatus.addStateListener(this);
        VpnStatus.addByteCountListener(this);

        guiHandler = new Handler(getMainLooper());

        if (intent != null && DISCONNECT_VPN.equals(intent.getAction())) {
            try {
                stopVPN(false);
            } catch (RemoteException e) {
                VpnStatus.logException(e);
            }
            return START_NOT_STICKY;
        }

        if (intent != null && PAUSE_VPN.equals(intent.getAction())) {
            if (mDeviceStateReceiver != null)
                mDeviceStateReceiver.userPause(true);
            return START_NOT_STICKY;
        }

        if (intent != null && RESUME_VPN.equals(intent.getAction())) {
            if (mDeviceStateReceiver != null)
                mDeviceStateReceiver.userPause(false);
            return START_NOT_STICKY;
        }


        if (intent != null && START_SERVICE.equals(intent.getAction()))
            return START_NOT_STICKY;
        if (intent != null && START_SERVICE_STICKY.equals(intent.getAction())) {
            return START_REDELIVER_INTENT;
        }

        VpnStatus.logInfo(R.string.building_configration);
        VpnStatus.updateStateString("VPN_GENERATE_CONFIG", "", R.string.building_configration, ConnectionStatus.LEVEL_START);
        showNotification(VpnStatus.getLastCleanLogMessage(this),
                VpnStatus.getLastCleanLogMessage(this), NOTIFICATION_CHANNEL_NEWSTATUS_ID, 0, ConnectionStatus.LEVEL_START, null);

        if (intent != null && intent.hasExtra(getPackageName() + ".profileUUID")) {
            String profileUUID = intent.getStringExtra(getPackageName() + ".profileUUID");
            int profileVersion = intent.getIntExtra(getPackageName() + ".profileVersion", 0);
            mProfile = ProfileManager.get(this, profileUUID, profileVersion, 100);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                updateShortCutUsage(mProfile);
            }

        } else {
            mProfile = ProfileManager.getLastConnectedProfile(this);
            VpnStatus.logInfo(R.string.service_restarted);


            if (mProfile == null) {
                Log.d("OpenVPN", "Got no last connected profile on null intent. Assuming always on.");
                mProfile = ProfileManager.getAlwaysOnVPN(this);

                if (mProfile == null) {
                    stopSelf(startId);
                    return START_NOT_STICKY;
                }
            }
            mProfile.checkForRestart(this);
        }

        if (mProfile == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }


        new Thread(new Runnable() {
            @Override
            public void run() {
                startOpenVPN();
            }
        }).start();


        ProfileManager.setConnectedVpnProfile(this, mProfile);
        VpnStatus.setConnectedVPNProfile(mProfile.getUUIDString());

        return START_STICKY;
    }

    @RequiresApi(Build.VERSION_CODES.N_MR1)
    private void updateShortCutUsage(VpnProfile profile) {
        if (profile == null)
            return;
        ShortcutManager shortcutManager = getSystemService(ShortcutManager.class);
        shortcutManager.reportShortcutUsed(profile.getUUIDString());
    }

    private void startOpenVPN() {
        try {
            mProfile.writeConfigFile(this);
        } catch (IOException e) {
            VpnStatus.logException("Error writing config file", e);
            endVpnService();
            return;
        }
        String nativeLibraryDirectory = getApplicationInfo().nativeLibraryDir;
        String tmpDir;
        try {
            tmpDir = getApplication().getCacheDir().getCanonicalPath();
        } catch (IOException e) {
            e.printStackTrace();
            tmpDir = "/tmp";
        }

        String[] argv = VPNLaunchHelper.buildOpenvpnArgv(this);


        mStarting = true;


        stopOldOpenVPNProcess();

        mStarting = false;


        boolean useOpenVPN3 = VpnProfile.doUseOpenVPN3(this);


        if (!useOpenVPN3) {

            OpenVpnManagementThread ovpnManagementThread = new OpenVpnManagementThread(mProfile, this);
            if (ovpnManagementThread.openManagementInterface(this)) {
                Thread mSocketManagerThread = new Thread(ovpnManagementThread, "OpenVPNManagementThread");
                mSocketManagerThread.start();
                mManagement = ovpnManagementThread;
                VpnStatus.logInfo("started Socket Thread");
            } else {
                endVpnService();
                return;
            }
        }

        Runnable processThread;
        if (useOpenVPN3) {
            OpenVPNManagement mOpenVPN3 = instantiateOpenVPN3Core();
            processThread = (Runnable) mOpenVPN3;
            mManagement = mOpenVPN3;
        } else {
            processThread = new OpenVPNThread(this, argv, nativeLibraryDirectory, tmpDir);
            mOpenVPNThread = processThread;
        }

        synchronized (mProcessLock) {
            mProcessThread = new Thread(processThread, "OpenVPNProcessThread");
            mProcessThread.start();
        }

        new Handler(getMainLooper()).post(new Runnable() {
                                              @Override
                                              public void run() {
                                                  if (mDeviceStateReceiver != null)
                                                      unregisterDeviceStateReceiver();

                                                  registerDeviceStateReceiver(mManagement);
                                              }
                                          }

        );
    }


    private void stopOldOpenVPNProcess() {
        if (mManagement != null) {
            if (mOpenVPNThread != null)
                ((OpenVPNThread) mOpenVPNThread).setReplaceConnection();
            if (mManagement.stopVPN(true)) {

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {

                }
            }
        }

        forceStopOpenVpnProcess();
    }

    public void forceStopOpenVpnProcess() {
        synchronized (mProcessLock) {
            if (mProcessThread != null) {
                mProcessThread.interrupt();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {

                }
            }
        }
    }

    private OpenVPNManagement instantiateOpenVPN3Core() {
        try {
            Class cl = Class.forName("de.blinkt.openvpn.core.OpenVPNThreadv3");
            return (OpenVPNManagement) cl.getConstructor(OpenVPNService.class, VpnProfile.class).newInstance(this, mProfile);
        } catch (IllegalArgumentException | InstantiationException | InvocationTargetException |
                NoSuchMethodException | ClassNotFoundException | IllegalAccessException e) {
            e.printStackTrace();
        }
        return null;
    }


    @Override
    public IBinder asBinder() {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        sendMessage("DISCONNECTED");
        synchronized (mProcessLock) {
            if (mProcessThread != null) {
                mManagement.stopVPN(true);
            }
        }

        if (mDeviceStateReceiver != null) {
            this.unregisterReceiver(mDeviceStateReceiver);
        }

        VpnStatus.removeStateListener(this);
        VpnStatus.flushLog();
    }

    private String getTunConfigString() {

        String cfg = "TUNCFG UNQIUE STRING ips:";

        if (mLocalIP != null)
            cfg += mLocalIP.toString();
        if (mLocalIPv6 != null)
            cfg += mLocalIPv6;


        cfg += "routes: " + TextUtils.join("|", mRoutes.getNetworks(true)) + TextUtils.join("|", mRoutesv6.getNetworks(true));
        cfg += "excl. routes:" + TextUtils.join("|", mRoutes.getNetworks(false)) + TextUtils.join("|", mRoutesv6.getNetworks(false));
        cfg += "dns: " + TextUtils.join("|", mDnslist);
        cfg += "domain: " + mDomain;
        cfg += "mtu: " + mMtu;
        return cfg;
    }

    public ParcelFileDescriptor openTun() {



        Builder builder = new Builder();

        VpnStatus.logInfo(R.string.last_openvpn_tun_config);

        boolean allowUnsetAF = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && !mProfile.mBlockUnusedAddressFamilies;
        if (allowUnsetAF) {
            allowAllAFFamilies(builder);
        }

        if (mLocalIP == null && mLocalIPv6 == null) {
            VpnStatus.logError(getString(R.string.opentun_no_ipaddr));
            return null;
        }

        if (mLocalIP != null) {

            if (!VpnProfile.doUseOpenVPN3(this))
                addLocalNetworksToRoutes();
            try {
                builder.addAddress(mLocalIP.mIp, mLocalIP.len);
            } catch (IllegalArgumentException iae) {
                VpnStatus.logError(R.string.dns_add_error, mLocalIP, iae.getLocalizedMessage());
                return null;
            }
        }

        if (mLocalIPv6 != null) {
            String[] ipv6parts = mLocalIPv6.split("/");
            try {
                builder.addAddress(ipv6parts[0], Integer.parseInt(ipv6parts[1]));
            } catch (IllegalArgumentException iae) {
                VpnStatus.logError(R.string.ip_add_error, mLocalIPv6, iae.getLocalizedMessage());
                return null;
            }

        }


        for (String dns : mDnslist) {
            try {
                builder.addDnsServer(dns);
            } catch (IllegalArgumentException iae) {
                VpnStatus.logError(R.string.dns_add_error, dns, iae.getLocalizedMessage());
            }
        }

        String release = Build.VERSION.RELEASE;
        if ((Build.VERSION.SDK_INT == Build.VERSION_CODES.KITKAT && !release.startsWith("4.4.3")
                && !release.startsWith("4.4.4") && !release.startsWith("4.4.5") && !release.startsWith("4.4.6"))
                && mMtu < 1280) {
            VpnStatus.logInfo(String.format(Locale.US, "Forcing MTU to 1280 instead of %d to workaround Android Bug #70916", mMtu));
            builder.setMtu(1280);
        } else {
            builder.setMtu(mMtu);
        }

        Collection<IpAddress> positiveIPv4Routes = mRoutes.getPositiveIPList();
        Collection<IpAddress> positiveIPv6Routes = mRoutesv6.getPositiveIPList();

        if ("samsung".equals(Build.BRAND) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mDnslist.size() >= 1) {

            try {
                IpAddress dnsServer = new IpAddress(new CIDRIP(mDnslist.get(0), 32), true);
                boolean dnsIncluded = false;
                for (IpAddress net : positiveIPv4Routes) {
                    if (net.containsNet(dnsServer)) {
                        dnsIncluded = true;
                    }
                }
                if (!dnsIncluded) {
                    String samsungwarning = String.format("Warning Samsung Android 5.0+ devices ignore DNS servers outside the VPN range. To enable DNS resolution a route to your DNS Server (%s) has been added.", mDnslist.get(0));
                    VpnStatus.logWarning(samsungwarning);
                    positiveIPv4Routes.add(dnsServer);
                }
            } catch (Exception e) {

                if (!mDnslist.get(0).contains(":"))
                    VpnStatus.logError("Error parsing DNS Server IP: " + mDnslist.get(0));
            }
        }

        IpAddress multicastRange = new IpAddress(new CIDRIP("224.0.0.0", 3), true);

        for (IpAddress route : positiveIPv4Routes) {
            try {

                if (multicastRange.containsNet(route))
                    VpnStatus.logDebug(R.string.ignore_multicast_route, route.toString());
                else
                    builder.addRoute(route.getIPv4Address(), route.networkMask);
            } catch (IllegalArgumentException ia) {
                VpnStatus.logError(getString(R.string.route_rejected) + route + " " + ia.getLocalizedMessage());
            }
        }

        for (IpAddress route6 : positiveIPv6Routes) {
            try {
                builder.addRoute(route6.getIPv6Address(), route6.networkMask);
            } catch (IllegalArgumentException ia) {
                VpnStatus.logError(getString(R.string.route_rejected) + route6 + " " + ia.getLocalizedMessage());
            }
        }


        if (mDomain != null)
            builder.addSearchDomain(mDomain);

        String ipv4info;
        String ipv6info;
        if (allowUnsetAF) {
            ipv4info = "(not set, allowed)";
            ipv6info = "(not set, allowed)";
        } else {
            ipv4info = "(not set)";
            ipv6info = "(not set)";
        }

        int ipv4len;
        if (mLocalIP != null) {
            ipv4len = mLocalIP.len;
            ipv4info = mLocalIP.mIp;
        } else {
            ipv4len = -1;
        }

        if (mLocalIPv6 != null) {
            ipv6info = mLocalIPv6;
        }

        if ((!mRoutes.getNetworks(false).isEmpty() || !mRoutesv6.getNetworks(false).isEmpty()) && isLockdownEnabledCompat()) {
            VpnStatus.logInfo("VPN lockdown enabled (do not allow apps to bypass VPN) enabled. Route exclusion will not allow apps to bypass VPN (e.g. bypass VPN for local networks)");
        }
        if (mDomain != null) builder.addSearchDomain(mDomain);
        VpnStatus.logInfo(R.string.local_ip_info, ipv4info, ipv4len, ipv6info, mMtu);
        VpnStatus.logInfo(R.string.dns_server_info, TextUtils.join(", ", mDnslist), mDomain);
        VpnStatus.logInfo(R.string.routes_info_incl, TextUtils.join(", ", mRoutes.getNetworks(true)), TextUtils.join(", ", mRoutesv6.getNetworks(true)));
        VpnStatus.logInfo(R.string.routes_info_excl, TextUtils.join(", ", mRoutes.getNetworks(false)), TextUtils.join(", ", mRoutesv6.getNetworks(false)));
        VpnStatus.logDebug(R.string.routes_debug, TextUtils.join(", ", positiveIPv4Routes), TextUtils.join(", ", positiveIPv6Routes));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            setAllowedVpnPackages(builder);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            builder.setUnderlyingNetworks(null);
        }


        String session = mProfile.mName;
        if (mLocalIP != null && mLocalIPv6 != null)
            session = getString(R.string.session_ipv6string, session, mLocalIP, mLocalIPv6);
        else if (mLocalIP != null)
            session = getString(R.string.session_ipv4string, session, mLocalIP);
        else
            session = getString(R.string.session_ipv4string, session, mLocalIPv6);

        builder.setSession(session);


        if (mDnslist.size() == 0)
            VpnStatus.logInfo(R.string.warn_no_dns);

        mLastTunCfg = getTunConfigString();


        mDnslist.clear();
        mRoutes.clear();
        mRoutesv6.clear();
        mLocalIP = null;
        mLocalIPv6 = null;
        mDomain = null;

        builder.setConfigureIntent(getGraphPendingIntent());

        try {

            ParcelFileDescriptor tun = builder.establish();
            if (tun == null)
                throw new NullPointerException("Android establish() method returned null (Really broken network configuration?)");
            return tun;
        } catch (Exception e) {
            VpnStatus.logError(R.string.tun_open_error);
            VpnStatus.logError(getString(R.string.error) + e.getLocalizedMessage());
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                VpnStatus.logError(R.string.tun_error_helpful);
            }
            return null;
        }

    }

    private boolean isLockdownEnabledCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return isLockdownEnabled();
        } else {

            return false;
        }

    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void allowAllAFFamilies(Builder builder) {
        builder.allowFamily(OsConstants.AF_INET);
        builder.allowFamily(OsConstants.AF_INET6);
    }

    private void addLocalNetworksToRoutes() {
        for (String net : NetworkUtils.getLocalNetworks(this, false)) {
            String[] netparts = net.split("/");
            String ipAddr = netparts[0];
            int netMask = Integer.parseInt(netparts[1]);
            if (ipAddr.equals(mLocalIP.mIp))
                continue;

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT && !mProfile.mAllowLocalLAN) {
                mRoutes.addIPSplit(new CIDRIP(ipAddr, netMask), true);

            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && mProfile.mAllowLocalLAN)
                mRoutes.addIP(new CIDRIP(ipAddr, netMask), false);
        }


        if (mProfile.mAllowLocalLAN) {
            for (String net : NetworkUtils.getLocalNetworks(this, true)) {
                addRoutev6(net, false);
            }
        }


    }


    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void setAllowedVpnPackages(Builder builder) {
        boolean profileUsesOrBot = false;

        for (Connection c : mProfile.mConnections) {
            if (c.mProxyType == Connection.ProxyType.ORBOT)
                profileUsesOrBot = true;
        }

        if (profileUsesOrBot)
            VpnStatus.logDebug("VPN Profile uses at least one server entry with Orbot. Setting up VPN so that OrBot is not redirected over VPN.");


        boolean atLeastOneAllowedApp = false;

        if (mProfile.mAllowedAppsVpnAreDisallowed && profileUsesOrBot) {
            try {
                builder.addDisallowedApplication(ORBOT_PACKAGE_NAME);
            } catch (PackageManager.NameNotFoundException e) {
                VpnStatus.logDebug("Orbot not installed?");
            }
        }

        for (String pkg : mProfile.mAllowedAppsVpn) {
            try {
                if (mProfile.mAllowedAppsVpnAreDisallowed) {
                    builder.addDisallowedApplication(pkg);
                } else {
                    if (!(profileUsesOrBot && pkg.equals(ORBOT_PACKAGE_NAME))) {
                        builder.addAllowedApplication(pkg);
                        atLeastOneAllowedApp = true;
                    }
                }
            } catch (PackageManager.NameNotFoundException e) {
                mProfile.mAllowedAppsVpn.remove(pkg);
                VpnStatus.logInfo(R.string.app_no_longer_exists, pkg);
            }
        }

        if (!mProfile.mAllowedAppsVpnAreDisallowed && !atLeastOneAllowedApp) {
            VpnStatus.logDebug(R.string.no_allowed_app, getPackageName());
            try {
                builder.addAllowedApplication(getPackageName());
            } catch (PackageManager.NameNotFoundException e) {
                VpnStatus.logError("This should not happen: " + e.getLocalizedMessage());
            }
        }

        if (mProfile.mAllowedAppsVpnAreDisallowed) {
            VpnStatus.logDebug(R.string.disallowed_vpn_apps_info, TextUtils.join(", ", mProfile.mAllowedAppsVpn));
        } else {
            VpnStatus.logDebug(R.string.allowed_vpn_apps_info, TextUtils.join(", ", mProfile.mAllowedAppsVpn));
        }

        if (mProfile.mAllowAppVpnBypass) {
            builder.allowBypass();
            VpnStatus.logDebug("Apps may bypass VPN");
        }
    }

    public void addDNS(String dns) {
        mDnslist.add(dns);
    }

    public void setDomain(String domain) {
        if (mDomain == null) {
            mDomain = domain;
        }
    }


    public void addRoute(CIDRIP route, boolean include) {
        mRoutes.addIP(route, include);
    }

    public void addRoute(String dest, String mask, String gateway, String device) {
        CIDRIP route = new CIDRIP(dest, mask);
        boolean include = isAndroidTunDevice(device);

        IpAddress gatewayIP = new IpAddress(new CIDRIP(gateway, 32), false);

        if (mLocalIP == null) {
            VpnStatus.logError("Local IP address unset and received. Neither pushed server config nor local config specifies an IP addresses. Opening tun device is most likely going to fail.");
            return;
        }
        IpAddress localNet = new IpAddress(mLocalIP, true);
        if (localNet.containsNet(gatewayIP))
            include = true;

        if (gateway != null &&
                (gateway.equals("255.255.255.255") || gateway.equals(mRemoteGW)))
            include = true;


        if (route.len == 32 && !mask.equals("255.255.255.255")) {
            VpnStatus.logWarning(R.string.route_not_cidr, dest, mask);
        }

        if (route.normalise())
            VpnStatus.logWarning(R.string.route_not_netip, dest, route.len, route.mIp);

        mRoutes.addIP(route, include);
    }

    public void addRoutev6(String network, String device) {

        boolean included = isAndroidTunDevice(device);
        addRoutev6(network, included);
    }

    public void addRoutev6(String network, boolean included) {
        String[] v6parts = network.split("/");

        try {
            Inet6Address ip = (Inet6Address) InetAddress.getAllByName(v6parts[0])[0];
            int mask = Integer.parseInt(v6parts[1]);
            mRoutesv6.addIPv6(ip, mask, included);

        } catch (UnknownHostException e) {
            VpnStatus.logException(e);
        }


    }

    private boolean isAndroidTunDevice(String device) {
        return device != null &&
                (device.startsWith("tun") || "(null)".equals(device) || VPNSERVICE_TUN.equals(device));
    }

    public void setMtu(int mtu) {
        mMtu = mtu;
    }

    public void setLocalIP(CIDRIP cdrip) {
        mLocalIP = cdrip;
    }

    public void setLocalIP(String local, String netmask, int mtu, String mode) {
        mLocalIP = new CIDRIP(local, netmask);
        mMtu = mtu;
        mRemoteGW = null;

        long netMaskAsInt = CIDRIP.getInt(netmask);

        if (mLocalIP.len == 32 && !netmask.equals("255.255.255.255")) {


            int masklen;
            long mask;
            if ("net30".equals(mode)) {
                masklen = 30;
                mask = 0xfffffffc;
            } else {
                masklen = 31;
                mask = 0xfffffffe;
            }


            if ((netMaskAsInt & mask) == (mLocalIP.getInt() & mask)) {
                mLocalIP.len = masklen;
            } else {
                mLocalIP.len = 32;
                if (!"p2p".equals(mode))
                    VpnStatus.logWarning(R.string.ip_not_cidr, local, netmask, mode);
            }
        }
        if (("p2p".equals(mode) && mLocalIP.len < 32) || ("net30".equals(mode) && mLocalIP.len < 30)) {
            VpnStatus.logWarning(R.string.ip_looks_like_subnet, local, netmask, mode);
        }



        if (mLocalIP.len <= 31 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CIDRIP interfaceRoute = new CIDRIP(mLocalIP.mIp, mLocalIP.len);
            interfaceRoute.normalise();
            addRoute(interfaceRoute, true);
        }



        mRemoteGW = netmask;
    }

    public void setLocalIPv6(String ipv6addr) {
        mLocalIPv6 = ipv6addr;
    }

    @Override
    public void updateState(String state, String logmessage, int resid, ConnectionStatus level, Intent intent) {

        doSendBroadcast(state, level);
        if (mProcessThread == null && !mNotificationAlwaysVisible)
            return;

        String channel = NOTIFICATION_CHANNEL_NEWSTATUS_ID;


        {
            if (level == LEVEL_CONNECTED) {
                mDisplayBytecount = true;
                mConnecttime = System.currentTimeMillis();
                if (!runningOnAndroidTV())
                    channel = NOTIFICATION_CHANNEL_BG_ID;
            } else {
                mDisplayBytecount = false;
            }


            String msg = getString(resid);
            showNotification(VpnStatus.getLastCleanLogMessage(this),
                    VpnStatus.getLastCleanLogMessage(this), channel, 0, level, intent);

        }
    }

    @Override
    public void setConnectedVPN(String uuid) {
    }

    private void doSendBroadcast(String state, ConnectionStatus level) {
        Intent vpnstatus = new Intent();
        vpnstatus.setAction("de.blinkt.openvpn.VPN_STATUS");
        vpnstatus.putExtra("status", level.toString());
        vpnstatus.putExtra("detailstatus", state);
        sendBroadcast(vpnstatus, permission.ACCESS_NETWORK_STATE);
        sendMessage(state);
    }

    long c = Calendar.getInstance().getTimeInMillis();
    long time;
    int lastPacketReceive = 0;
    String seconds = "0", minutes, hours;

    @Override
    public void updateByteCount(long in, long out, long diffIn, long diffOut) {
        TotalTraffic.calcTraffic(this, in, out, diffIn, diffOut);
        if (mDisplayBytecount) {
            String netstat = String.format(getString(R.string.statusline_bytecount),
                    humanReadableByteCount(in, false, getResources()),
                    humanReadableByteCount(diffIn / OpenVPNManagement.mBytecountInterval, true, getResources()),
                    humanReadableByteCount(out, false, getResources()),
                    humanReadableByteCount(diffOut / OpenVPNManagement.mBytecountInterval, true, getResources()));


            showNotification(netstat, null, NOTIFICATION_CHANNEL_BG_ID, mConnecttime, LEVEL_CONNECTED, null);
            byteIn = String.format("↓%2$s", getString(R.string.statusline_bytecount),
                    humanReadableByteCount(in,false, getResources())) + " - " + humanReadableByteCount(diffIn / OpenVPNManagement.mBytecountInterval, false, getResources()) + "/s";
            byteOut = String.format("↑%2$s", getString(R.string.statusline_bytecount),
                    humanReadableByteCount(out, false,getResources())) + " - " + humanReadableByteCount(diffOut / OpenVPNManagement.mBytecountInterval, false, getResources()) + "/s";
            time = Calendar.getInstance().getTimeInMillis() - c;
            lastPacketReceive = Integer.parseInt(convertTwoDigit((int) (time / 1000) % 60)) - Integer.parseInt(seconds);
            seconds = convertTwoDigit((int) (time / 1000) % 60);
            minutes = convertTwoDigit((int) ((time / (1000 * 60)) % 60));
            hours = convertTwoDigit((int) ((time / (1000 * 60 * 60)) % 24));
            duration = hours + ":" + minutes + ":" + seconds;
            lastPacketReceive = checkPacketReceive(lastPacketReceive);
            sendMessage(duration, String.valueOf(lastPacketReceive), byteIn, byteOut);
        }

    }

    public int checkPacketReceive(int value) {
        value -= 2;
        if (value < 0) return 0;
        else return value;
    }
    public String convertTwoDigit(int value) {
        if (value < 10) return "0" + value;
        else return value + "";
    }

    @Override
    public boolean handleMessage(Message msg) {
        Runnable r = msg.getCallback();
        if (r != null) {
            r.run();
            return true;
        } else {
            return false;
        }
    }

    public OpenVPNManagement getManagement() {
        return mManagement;
    }

    public String getTunReopenStatus() {
        String currentConfiguration = getTunConfigString();
        if (currentConfiguration.equals(mLastTunCfg)) {
            return "NOACTION";
        } else {
            String release = Build.VERSION.RELEASE;
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.KITKAT && !release.startsWith("4.4.3")
                    && !release.startsWith("4.4.4") && !release.startsWith("4.4.5") && !release.startsWith("4.4.6"))
                return "OPEN_AFTER_CLOSE";
            else
                return "OPEN_BEFORE_CLOSE";
        }
    }

    public void requestInputFromUser(int resid, String needed) {
        VpnStatus.updateStateString("NEED", "need " + needed, resid, LEVEL_WAITING_FOR_USER_INPUT);
        showNotification(getString(resid), getString(resid), NOTIFICATION_CHANNEL_NEWSTATUS_ID, 0, LEVEL_WAITING_FOR_USER_INPUT, null);
    }


    public void trigger_sso(String info) {
        String channel = NOTIFICATION_CHANNEL_USERREQ_ID;
        String method = info.split(":", 2)[0];

        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        Notification.Builder nbuilder = new Notification.Builder(this);
        nbuilder.setAutoCancel(true);
        int icon = android.R.drawable.ic_dialog_info;
        nbuilder.setSmallIcon(icon);

        Intent intent;

        int reason;
        if (method.equals("CR_TEXT")) {
            String challenge = info.split(":", 2)[1];
            reason = R.string.crtext_requested;
            nbuilder.setContentTitle(getString(reason));
            nbuilder.setContentText(challenge);

            intent = new Intent();
            intent.setComponent(new ComponentName(this, getPackageName() + ".activities.CredentialsPopup"));

            intent.putExtra(EXTRA_CHALLENGE_TXT, challenge);

        } else {
            VpnStatus.logError("Unknown SSO method found: " + method);
            return;
        }


        PendingIntent pIntent = PendingIntent.getActivity(this, 0, intent, 0);
        VpnStatus.updateStateString("USER_INPUT", "waiting for user input", reason, LEVEL_WAITING_FOR_USER_INPUT, intent);
        nbuilder.setContentIntent(pIntent);


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN)
            jbNotificationExtras(PRIORITY_MAX, nbuilder);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            lpNotificationExtras(nbuilder, Notification.CATEGORY_STATUS);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            nbuilder.setChannelId(channel);
        }

        @SuppressWarnings("deprecation")
        Notification notification = nbuilder.getNotification();


        int notificationId = channel.hashCode();

        mNotificationManager.notify(notificationId, notification);
    }

    private void sendMessage(String state) {
        Intent intent = new Intent("connectionState");
        intent.putExtra("state", state);
        this.state = state;
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
    }
    private void sendMessage(String duration, String lastPacketReceive, String byteIn, String byteOut) {
        Intent intent = new Intent("connectionState");
        intent.putExtra("duration", duration);
        intent.putExtra("lastPacketReceive", lastPacketReceive);
        intent.putExtra("byteIn", byteIn);
        intent.putExtra("byteOut", byteOut);
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
    }
    public class LocalBinder extends Binder {
        public OpenVPNService getService() {
            return OpenVPNService.this;
        }
    }
    public static String getStatus() {
        return state;
    }
    public static void setDefaultStatus() {
        state = "";
    }
    public boolean isConnected() {
        return flag;
    }
}
