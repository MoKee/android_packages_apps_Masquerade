
package masquerade.substratum.services;

import android.app.ActivityManagerNative;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.om.IOverlayManager;
import android.content.pm.IPackageDeleteObserver2;
import android.content.pm.IPackageInstallObserver2;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.os.Binder;
import android.os.Bundle;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class JobService extends Service {
    private static final String TAG = JobService.class.getSimpleName();
    private static final boolean DEBUG = true;

    private static final String MASQUERADE_TOKEN = "masquerade_token";
    private static final String SUBSTRATUM_PACKAGE = "projekt.substratum";
    private static final String[] AUTHORIZED_CALLERS = new String[] {
            SUBSTRATUM_PACKAGE,
            "masquerade.substratum"
    };

    public static final String PRIMARY_COMMAND_KEY = "primary_command_key";
    public static final String JOB_TIME_KEY = "job_time_key";
    public static final String INSTALL_LIST_KEY = "install_list";
    public static final String UNINSTALL_LIST_KEY = "uninstall_list";
    public static final String WITH_RESTART_UI_KEY = "with_restart_ui";
    public static final String BOOTANIMATION_PID_KEY = "bootanimation_pid";
    public static final String BOOTANIMATION_FILE_NAME = "bootanimation_file_name";
    public static final String FONTS_RESET = "fonts_reset";
    public static final String COMMAND_VALUE_INSTALL = "install";
    public static final String COMMAND_VALUE_UNINSTALL = "uninstall";
    public static final String COMMAND_VALUE_RESTART_UI = "restart_ui";
    public static final String COMMAND_VALUE_CONFIGURATION_SHIM = "configuration_shim";
    public static final String COMMAND_VALUE_BOOTANIMATION = "bootanimation";
    public static final String COMMAND_VALUE_FONTS = "fonts";
    public static final String COMMAND_VALUE_AUDIO = "audio";

    public static final String SYSTEM_THEME_PATH = "/data/system/theme";
    public static final String SYSTEM_THEME_FONT_PATH = SYSTEM_THEME_PATH + File.separator
            + "fonts";
    public static final String SYSTEM_THEME_RINGTONE_PATH = SYSTEM_THEME_PATH
            + File.separator + "ringtones";
    public static final String SYSTEM_THEME_NOTIFICATION_PATH = SYSTEM_THEME_PATH
            + File.separator + "notifications";
    public static final String SYSTEM_THEME_ALARM_PATH = SYSTEM_THEME_PATH
            + File.separator + "alarms";
    public static final String SYSTEM_THEME_ICON_CACHE_DIR = SYSTEM_THEME_PATH
            + File.separator + "icons";
    public static final String SYSTEM_THEME_BOOTANIMATION_PATH = SYSTEM_THEME_PATH + File.separator
            + "bootanimation.zip";

    private static IOverlayManager mOMS;
    private static IPackageManager mPM;

    private HandlerThread mWorker;
    private JobHandler mJobHandler;
    private MainHandler mMainHandler;
    private final List<Runnable> mJobQueue = new ArrayList<>(0);
    private long mLastJobTime;

    @Override
    public void onCreate() {
        mWorker = new HandlerThread("BackgroundWorker", Process.THREAD_PRIORITY_BACKGROUND);
        mWorker.start();
        mJobHandler = new JobHandler(mWorker.getLooper());
        mMainHandler = new MainHandler(Looper.getMainLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // verify identity
        if (!isCallerAuthorized(intent)) {
            log("caller not authorized, aborting");
            return START_NOT_STICKY;
        }
        // one job at a time please
        if (isProcessing()) {
            log("Got start command while still processing last job, aborting");
            return START_NOT_STICKY;
        }
        // filter out duplicate intents
        long jobTime = intent.getLongExtra(JOB_TIME_KEY, 1);
        if (jobTime == 1 || jobTime == mLastJobTime) {
            log("got empty jobtime or duplicate job time, aborting");
            return START_NOT_STICKY;
        }
        mLastJobTime = jobTime;

        // must have a primary command
        String command = intent.getStringExtra(PRIMARY_COMMAND_KEY);
        if (TextUtils.isEmpty(command)) {
            log("Got empty primary command, aborting");
            return START_NOT_STICKY;
        }

        // queue up the job
        log("Starting job with primart command " + command + " With job time " + jobTime);
        if (TextUtils.equals(command, COMMAND_VALUE_INSTALL)) {
            List<String> paths = intent.getStringArrayListExtra(INSTALL_LIST_KEY);
            for (String path : paths) {
                mJobQueue.add(new Installer(path));
            }
        } else if (TextUtils.equals(command, COMMAND_VALUE_UNINSTALL)) {
            List<String> packages = intent.getStringArrayListExtra(UNINSTALL_LIST_KEY);
            for (String _package : packages) {
                mJobQueue.add(new Remover(_package));
            }
            if (intent.getBooleanExtra(WITH_RESTART_UI_KEY, false)) {
                mJobQueue.add(new UiResetJob());
            }
        } else if (TextUtils.equals(command, COMMAND_VALUE_RESTART_UI)) {
            mJobQueue.add(new UiResetJob());
        } else if (TextUtils.equals(command, COMMAND_VALUE_CONFIGURATION_SHIM)) {
            mJobQueue.add(new LocaleChanger(getApplicationContext(), mMainHandler));
        } else if (TextUtils.equals(command, COMMAND_VALUE_BOOTANIMATION)) {
            String pid = intent.getStringExtra(BOOTANIMATION_PID_KEY);
            if (TextUtils.isEmpty(pid)) {
                mJobQueue.add(new BootAnimationJob(true));
            } else {
                String fileName = intent.getStringExtra(BOOTANIMATION_FILE_NAME);
                mJobQueue.add(new BootAnimationJob(fileName, pid));
            }
        } else if (TextUtils.equals(command, COMMAND_VALUE_FONTS)) {
            boolean doClear = intent.getBooleanExtra(FONTS_RESET, false);
            mJobQueue.add(new FontsJob(doClear));
        } else if (TextUtils.equals(command, COMMAND_VALUE_AUDIO)) {

        }

        if (isProcessing()) {
            log("Starting job queue");
            mJobHandler.sendEmptyMessage(JobHandler.MESSAGE_CHECK_QUEUE);
        }

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {

    }

    private boolean isProcessing() {
        return mJobQueue.size() > 0;
    }

    private class LocalService extends Binder {
        public JobService getService() {
            return JobService.this;
        }
    }

    private static IOverlayManager getOMS() {
        if (mOMS == null) {
            mOMS = IOverlayManager.Stub.asInterface(
                    ServiceManager.getService("overlay"));
        }
        return mOMS;
    }

    private static IPackageManager getPM() {
        if (mPM == null) {
            mPM = IPackageManager.Stub.asInterface(
                    ServiceManager.getService("package"));
        }
        return mPM;
    }

    private void install(String path, IPackageInstallObserver2 observer) {
        try {
            getPM().installPackageAsUser(path, observer, PackageManager.INSTALL_REPLACE_EXISTING,
                    null,
                    UserHandle.USER_SYSTEM);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void uninstall(String packageName, IPackageDeleteObserver2 observer) {
        try {
            getPM().deletePackage(packageName, observer, UserHandle.USER_SYSTEM, 0);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void disableOverlay(String packageName) {
        try {
            getOMS().setEnabled(packageName, false, UserHandle.USER_SYSTEM, false);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void copyFonts() {
        createFontDirIfNotExists();
        copyFolder("/system/fonts", SYSTEM_THEME_FONT_PATH);
        copyFolder(getSubsContext().getCacheDir().getAbsolutePath() +
                "/FontCache/FontCreator", SYSTEM_THEME_FONT_PATH);
        SystemProperties.set("sys.refresh_theme", "1");
        float fontSize = Float.valueOf(Settings.System.getString(
                getContentResolver(), Settings.System.FONT_SCALE));
        Settings.System.putString(getContentResolver(),
                Settings.System.FONT_SCALE, String.valueOf(fontSize + 0.0000001));
        restartUi();
    }

    private void clearFonts() {
        try {
            File f = new File(SYSTEM_THEME_FONT_PATH);
            if (f.exists()) {
                f.delete();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        SystemProperties.set("sys.refresh_theme", "1");
        Typeface.recreateDefaults();
        restartUi();
    }

    private void copyAudio() {

    }

    private void clearAudio() {

    }

    private void copyBootAnimation(String theme_pid, String fileName) {
        try {
            clearBootAnimation();
            File source = new File(getSubsContext().getCacheDir().getAbsoluteFile() +
                    "/SubstratumBuilder/" + theme_pid + "/assets/bootanimation/" + fileName);
            File dest = new File(SYSTEM_THEME_BOOTANIMATION_PATH);
            bufferedCopy(source, dest);
            FileUtils.setPermissions(SYSTEM_THEME_BOOTANIMATION_PATH,
                    FileUtils.S_IRWXU | FileUtils.S_IRGRP | FileUtils.S_IROTH, -1, -1);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void clearBootAnimation() {
        try {
            File f = new File(SYSTEM_THEME_BOOTANIMATION_PATH);
            if (f.exists()) {
                f.delete();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void restartUi() {
        killPackage("com.android.systemui");
    }

    private void killPackage(String packageName) {
        try {
            ActivityManagerNative.getDefault().forceStopPackage(packageName,
                    UserHandle.USER_SYSTEM);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private Context getSubsContext() {
        return getAppContext(SUBSTRATUM_PACKAGE);
    }

    private Context getAppContext(String packageName) {
        Context ctx = null;
        try {
            ctx = getApplicationContext().createPackageContext(packageName,
                    Context.CONTEXT_IGNORE_SECURITY);
        } catch (NameNotFoundException e) {
            e.printStackTrace();
        }
        return ctx;
    }

    static void log(String msg) {
        if (DEBUG) {
            Log.e(TAG, msg);
        }
    }

    private static boolean dirExists(String dirPath) {
        final File dir = new File(dirPath);
        return dir.exists() && dir.isDirectory();
    }

    private static void createDirIfNotExists(String dirPath) {
        if (!dirExists(dirPath)) {
            File dir = new File(dirPath);
            if (dir.mkdir()) {
                FileUtils.setPermissions(dir, FileUtils.S_IRWXU |
                        FileUtils.S_IRWXG | FileUtils.S_IROTH | FileUtils.S_IXOTH, -1, -1);
            }
        }
    }

    /**
     * Create SYSTEM_THEME_PATH directory if it does not exist
     */
    public static void createThemeDirIfNotExists() {
        createDirIfNotExists(SYSTEM_THEME_PATH);
    }

    /**
     * Create SYSTEM_FONT_PATH directory if it does not exist
     */
    public static void createFontDirIfNotExists() {
        createDirIfNotExists(SYSTEM_THEME_FONT_PATH);
    }

    /**
     * Create SYSTEM_THEME_RINGTONE_PATH directory if it does not exist
     */
    public static void createRingtoneDirIfNotExists() {
        createDirIfNotExists(SYSTEM_THEME_RINGTONE_PATH);
    }

    /**
     * Create SYSTEM_THEME_NOTIFICATION_PATH directory if it does not exist
     */
    public static void createNotificationDirIfNotExists() {
        createDirIfNotExists(SYSTEM_THEME_NOTIFICATION_PATH);
    }

    /**
     * Create SYSTEM_THEME_ALARM_PATH directory if it does not exist
     */
    public static void createAlarmDirIfNotExists() {
        createDirIfNotExists(SYSTEM_THEME_ALARM_PATH);
    }

    private static void copyFolder(String source, String dest) {
        File dir = new File(source);
        File[] files = dir.listFiles();
        for (File file : files) {
            try {
                String sourceFile = dir + File.separator + file.getName();
                String destinationFile = dest + File.separator + file.getName();
                bufferedCopy(new File(sourceFile), new File(destinationFile));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static void unzip(String source, String destination) {
        try (ZipInputStream inputStream = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(source)))) {
            ZipEntry zipEntry;
            int count;
            byte[] buffer = new byte[8192];
            while ((zipEntry = inputStream.getNextEntry()) != null) {
                File file = new File(destination, zipEntry.getName());
                File dir = zipEntry.isDirectory() ? file : file.getParentFile();
                if (!dir.isDirectory() && !dir.mkdirs())
                    throw new FileNotFoundException("Failed to ensure directory: " +
                            dir.getAbsolutePath());
                if (zipEntry.isDirectory())
                    continue;
                try (FileOutputStream outputStream = new FileOutputStream(file)) {
                    while ((count = inputStream.read(buffer)) != -1)
                        outputStream.write(buffer, 0, count);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            Log.e("FontHandler",
                    "An issue has occurred while attempting to decompress this archive.");
        }
    }

    private static void bufferedCopy(File source, File dest) {
        try {
            BufferedInputStream in = new BufferedInputStream(new FileInputStream(source));
            BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(dest));
            byte[] buff = new byte[32 * 1024];
            int len;
            while ((len = in.read(buff)) > 0) {
                out.write(buff, 0, len);
            }
            in.close();
            out.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean isCallerAuthorized(Intent intent) {
        PendingIntent token = null;
        try {
            token = (PendingIntent) intent.getParcelableExtra(MASQUERADE_TOKEN);
        } catch (Exception e) {
            log("Attempt to start serivce without a token, unauthorized");
        }
        if (token == null) {
            return false;
        }
        // SECOND: we got a token, validate originating package
        // if not in our whitelist, return null
        String callingPackage = token.getCreatorPackage();
        boolean isValidPackage = false;
        for (int i = 0; i < AUTHORIZED_CALLERS.length; i++) {
            if (TextUtils.equals(callingPackage, AUTHORIZED_CALLERS[i])) {
                log(callingPackage
                        + " is an authorized calling package, next validate calling package perms");
                isValidPackage = true;
                break;
            }
        }
        if (!isValidPackage) {
            log(callingPackage + " is not an authorized calling package");
            return false;
        }
        return true;
    }

    private class MainHandler extends Handler {
        public static final int MSG_JOB_QUEUE_EMPTY = 1;

        public MainHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_JOB_QUEUE_EMPTY:
                    break;
            }
        }
    }

    private class JobHandler extends Handler {
        private static final int MESSAGE_CHECK_QUEUE = 1;
        private static final int MESSAGE_DEQUEUE = 2;

        public JobHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_CHECK_QUEUE:
                    Runnable job;
                    synchronized (mJobQueue) {
                        job = mJobQueue.get(0);
                    }
                    if (job != null) {
                        job.run();
                    }
                    break;
                case MESSAGE_DEQUEUE:
                    Runnable toRemove = (Runnable) msg.obj;
                    synchronized (mJobQueue) {
                        mJobQueue.remove(toRemove);
                        if (mJobQueue.size() > 0) {
                            this.sendEmptyMessage(MESSAGE_CHECK_QUEUE);
                        } else {
                            log("Job queue empty! All done");
                            mMainHandler.sendEmptyMessage(MainHandler.MSG_JOB_QUEUE_EMPTY);
                        }
                    }
                    break;
                default:
                    log("Unknown message " + msg.what);
                    break;
            }
        }
    }

    private class StopPackageJob implements Runnable {
        String mPackage;

        public void StopPackageJob(String _package) {
            mPackage = _package;
        }

        @Override
        public void run() {
            killPackage(mPackage);
            log("Killed package " + mPackage);
            Message message = mJobHandler.obtainMessage(JobHandler.MESSAGE_DEQUEUE,
                    StopPackageJob.this);
            mJobHandler.sendMessage(message);
        }
    }

    private class UiResetJob implements Runnable {
        @Override
        public void run() {
            restartUi();
            log("Restarting SystemUI...");
            Message message = mJobHandler.obtainMessage(JobHandler.MESSAGE_DEQUEUE,
                    UiResetJob.this);
            mJobHandler.sendMessage(message);
        }
    }

    private class FontsJob implements Runnable {
        final boolean mClear;

        public FontsJob(boolean clear) {
            mClear = clear;
        }

        @Override
        public void run() {
            if (mClear) {
                log("Resetting system font");
                clearFonts();
            } else {
                log("Setting theme font");
                copyFonts();
            }
            Message message = mJobHandler.obtainMessage(JobHandler.MESSAGE_DEQUEUE,
                    FontsJob.this);
            mJobHandler.sendMessage(message);
        }
    }

    private class BootAnimationJob implements Runnable {
        String mName;
        String mThemePid;
        final boolean mClear;

        public BootAnimationJob(boolean clear) {
            mClear = true;
        }

        public BootAnimationJob(String name, String themePid) {
            mName = name;
            mThemePid = themePid;
            mClear = false;
        }

        @Override
        public void run() {
            if (mClear) {
                log("Resetting system boot animation");
                clearBootAnimation();
            } else {
                log("Setting themed boot animation");
                copyBootAnimation(mThemePid, mName);
            }
            Message message = mJobHandler.obtainMessage(JobHandler.MESSAGE_DEQUEUE,
                    BootAnimationJob.this);
            mJobHandler.sendMessage(message);
        }
    }

    private class Installer implements Runnable, IPackageInstallObserver2 {
        String mPath;

        public Installer(String path) {
            mPath = path;
        }

        @Override
        public IBinder asBinder() {
            return null;
        }

        @Override
        public void onUserActionRequired(Intent intent) throws RemoteException {
            log("Installer - user action required callback with " + mPath);
        }

        @Override
        public void onPackageInstalled(String basePackageName, int returnCode, String msg,
                Bundle extras) throws RemoteException {
            log("Installer - successfully installed " + basePackageName + " from " + mPath);
            Message message = mJobHandler.obtainMessage(JobHandler.MESSAGE_DEQUEUE, Installer.this);
            mJobHandler.sendMessage(message);
        }

        @Override
        public void run() {
            log("Installer - installing " + mPath);
            install(mPath, this);
        }
    }

    private class Remover implements Runnable, IPackageDeleteObserver2 {
        String mPackage;
        boolean mDisableOverlay;

        public Remover(String _package) {
            this(_package, false);
        }

        public Remover(String _package, boolean disableOverlay) {
            mPackage = _package;
            mDisableOverlay = disableOverlay;
        }

        @Override
        public void run() {
            if (mDisableOverlay) {
                log("Remover - disabling overlay for " + mPackage);
                disableOverlay(mPackage);
            }
            log("Remover - uninstalling " + mPackage);
            uninstall(mPackage, this);
        }

        @Override
        public IBinder asBinder() {
            return null;
        }

        @Override
        public void onUserActionRequired(Intent intent) throws RemoteException {
            log("Remover - got user action required callback for " + mPackage);
        }

        @Override
        public void onPackageDeleted(String packageName, int returnCode, String msg)
                throws RemoteException {
            log("Remover - successfully uninstalled " + mPackage);
            Message message = mJobHandler.obtainMessage(JobHandler.MESSAGE_DEQUEUE, Remover.this);
            mJobHandler.sendMessage(message);
        }
    }

    private class LocaleChanger extends BroadcastReceiver implements Runnable {
        private boolean mIsRegistered;
        private boolean mDoRestore;
        private Context mContext;
        private Handler mHandler;
        private Locale mCurrentLocale;

        public LocaleChanger(Context context, Handler mainHandler) {
            mContext = context;
            mHandler = mainHandler;
        }

        @Override
        public void run() {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            mContext.startActivity(i);
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    spoofLocale();
                }
            }, 500);
        }

        private void register() {
            if (!mIsRegistered) {
                IntentFilter filter = new IntentFilter();
                filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
                mContext.registerReceiver(LocaleChanger.this, filter);
                mIsRegistered = true;
            }
        }

        private void unregister() {
            if (mIsRegistered) {
                mContext.unregisterReceiver(LocaleChanger.this);
                mIsRegistered = false;
            }
        }

        private void spoofLocale() {
            Configuration config;
            log("LocaleChanger - spoofing locale for configuation change shim");
            try {
                register();
                config = ActivityManagerNative.getDefault().getConfiguration();
                mCurrentLocale = config.locale;
                Locale toSpoof = Locale.JAPAN;
                if (mCurrentLocale == Locale.JAPAN) {
                    toSpoof = Locale.CHINA;
                }
                config.setLocale(toSpoof);
                config.userSetLocale = true;
                ActivityManagerNative.getDefault().updateConfiguration(config);
            } catch (RemoteException e) {
                e.printStackTrace();
                return;
            }
        }

        private void restoreLocale() {
            Configuration config;
            log("LocaleChanger - restoring original locale for configuation change shim");
            try {
                unregister();
                config = ActivityManagerNative.getDefault().getConfiguration();
                config.setLocale(mCurrentLocale);
                config.userSetLocale = true;
                ActivityManagerNative.getDefault().updateConfiguration(config);
            } catch (RemoteException e) {
                e.printStackTrace();
                return;
            }
            Message message = mJobHandler.obtainMessage(JobHandler.MESSAGE_DEQUEUE,
                    LocaleChanger.this);
            mJobHandler.sendMessage(message);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    restoreLocale();
                }
            }, 500);
        }
    }
}
