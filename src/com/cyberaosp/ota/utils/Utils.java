/*
 * Copyright (C) 2013 The CyanogenMod Project
 *
 * * Licensed under the GNU GPLv2 license
 *
 * The text of the license can be found in the LICENSE file
 * or at https://www.gnu.org/licenses/gpl-2.0.txt
 */

package com.cyberaosp.ota.utils;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.util.Log;
import android.os.Environment;

import com.cyberaosp.ota.R;
import com.cyberaosp.ota.misc.Constants;
import com.cyberaosp.ota.service.UpdateCheckService;

import java.io.BufferedWriter;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class Utils {

    private static final String TAG = "Utils";

    private Utils() {
        // this class is not supposed to be instantiated
    }

    public static File makeUpdateFolder() {
        File updatesFolder = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + Constants.UPDATES_FOLDER);
        if (!updatesFolder.exists()){
            try{
                updatesFolder.mkdir();
            }catch(Exception ex){
            }
        }
        return updatesFolder;
    }

    public static void writeMD5File(String fileName,String md5) {
        String path = makeUpdateFolder().getPath() + "/" + fileName + ".md5";
        File md5File = new File(path);

        if (md5File.exists()){
            try{
                md5File.delete();
            }catch(Exception ex){
            }
        }

        writeStringAsFile(path, md5);
    }

    public static String readMD5File(String fileName) {
        String path = makeUpdateFolder().getPath() + "/" + fileName + ".md5";
        File md5File = new File(path);
        if (!md5File.exists()){
            return "";
        }

        return readFileAsString(path);
    }

    public static void writeStringAsFile(String filePath, String fileContents) {
        try {
            FileWriter out = new FileWriter(new File(filePath));
            out.write(fileContents);
            out.close();
        } catch (Exception e) {
            Log.e(TAG, "Error in writeStringAsFile");
        }
    }

    public static String readFileAsString(String filePath) {
        StringBuilder stringBuilder = new StringBuilder();
        String line;
        BufferedReader in = null;

        try {
            in = new BufferedReader(new FileReader(new File(filePath)));
            while ((line = in.readLine()) != null) stringBuilder.append(line);

        } catch (Exception e) {
            Log.e(TAG, "Error in readFileAsString");
        } 

        return stringBuilder.toString();
    }

    public static void cancelNotification(Context context) {
        final NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(R.string.not_new_updates_found_title);
        nm.cancel(R.string.not_download_success);
    }

    public static String getDeviceType() {
        return SystemProperties.get(Constants.CURRENT_DEVICE_NAME);
    }

    public static String getInstalledVersion() {
        return SystemProperties.get(Constants.CURRENT_VERSION);
    }

    public static long getInstalledBuildDate() {
        return SystemProperties.getLong("ro.build.date.utc", 0);
    }

    public static String getDateLocalized(Context context, long unixTimestamp) {
        DateFormat f = DateFormat.getDateInstance(DateFormat.LONG, getCurrentLocale(context));
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        Date date = new Date(unixTimestamp * 1000);
        return f.format(date);
    }

    public static String getDateLocalizedFromFileName(Context context, String fileName) {
        return getDateLocalized(context, getTimestampFromFileName(fileName));
    }

    public static long getTimestampFromFileName(String fileName) {
        String[] subStrings = fileName.split("-");
        if (subStrings.length < 3 || subStrings[2].length() < 8) {
            Log.e(TAG, "The given filename is not valid: " + fileName);
            return 0;
        }
        try {
            int year = Integer.parseInt(subStrings[2].substring(0, 4));
            int month = Integer.parseInt(subStrings[2].substring(4, 6)) - 1;
            int day = Integer.parseInt(subStrings[2].substring(6, 8));
            Calendar cal = Calendar.getInstance();
            cal.set(year, month, day);
            return cal.getTimeInMillis() / 1000;
        } catch (NumberFormatException e) {
            Log.e(TAG, "The given filename is not valid: " + fileName);
            return 0;
        }
    }

    public static String getAndroidVersion(String versionName) {
        return versionName.split("-")[1];
    }

    public static String getUserAgentString(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(context.getPackageName(), 0);
            return pi.packageName + "/" + pi.versionName;
        } catch (PackageManager.NameNotFoundException nnfe) {
            return null;
        }
    }

    public static boolean isOnline(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm.getActiveNetworkInfo();
        if (netInfo != null && netInfo.isConnected()) {
            return true;
        }
        return false;
    }

    public static void scheduleUpdateService(Context context, int updateFrequency) {
        // Load the required settings from preferences
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        long lastCheck = prefs.getLong(Constants.LAST_UPDATE_CHECK_PREF, 0);

        // Get the intent ready
        Intent i = new Intent(context, UpdateCheckService.class);
        i.setAction(UpdateCheckService.ACTION_CHECK);
        PendingIntent pi = PendingIntent.getService(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);

        // Clear any old alarms and schedule the new alarm
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        am.cancel(pi);

        if (updateFrequency != Constants.UPDATE_FREQ_NONE) {
            am.setRepeating(AlarmManager.RTC_WAKEUP, lastCheck + updateFrequency, updateFrequency, pi);
        }
    }

    public static void triggerUpdate(Context context, String updateFileName) throws IOException {
        // Create the path for the update package
        String updatePackagePath = makeUpdateFolder().getPath() + "/" + updateFileName;

        // Reboot into recovery and trigger the update
        android.os.RecoverySystem.installPackage(context, new File(updatePackagePath));
    }

    public static Locale getCurrentLocale(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return context.getResources().getConfiguration().getLocales()
                    .getFirstMatch(context.getResources().getAssets().getLocales());
        } else {
            return context.getResources().getConfiguration().locale;
        }
    }

}
