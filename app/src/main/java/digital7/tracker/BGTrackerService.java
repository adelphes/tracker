package digital7.tracker;

import android.app.*;
import android.content.*;
import android.location.*;
import android.os.*;
import android.util.Log;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.json.*;

public class BGTrackerService extends Service {

    public static final String TAG = "BGTrackerService";
    // how often we retrieve location updates (seconds)
    static final int POSITION_UPDATE_RATE_SECS = 10;
    // how often we sync location data with the server (minutes)
    static final int SERVER_SYNC_RATE_MINS = 1;

    // instance of the tracker service and service ID doing the tracking
    static BGTrackerService TrackerService;
    static final AtomicLong TrackingServiceID = new AtomicLong(0);

    // for simplicity, the locations are stored as an list of JSON objects: {"lat":nnn, "long":nnn, "time":"<ISO8601>"}
    static final ArrayList<JSONObject> mCachedLocations = new ArrayList<>();
    String mUpdateURL; // the API update URL

    @Override
    public IBinder onBind(Intent intent) {
        // not bindable
        return null;
    }

    public void onCreate() {
    }

    public void onDestroy() {
        NotificationManager notmgr = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notmgr != null) notmgr.cancelAll();
        super.onDestroy();
    }
    
    private void saveCurrentLocation() {
        LocationManager locationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null)
            return; // just in case

        Location location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        // update to the GPS location if it's available (and more recent)
        Location gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if (gps != null) {
            if (location == null || (gps.getTime() >= location.getTime()))
                location = gps; // better match
        }
        if (location == null)
            return; // no location info

        // ISO-8601's are easier to comprehend than UTC millis
        Calendar timepoint = new GregorianCalendar(TimeZone.getTimeZone("Z"));
        timepoint.setTimeInMillis(location.getTime());
        // gotta love SO: http://stackoverflow.com/a/3914546/1132806
        String isodate = String.format("%tFT%<tT.%<tLZ",timepoint);

        JSONObject o = new JSONObject();
        try {
            o.put("lat", location.getLatitude());
            o.put("long", location.getLongitude());
            o.put("time", isodate);
            synchronized(mCachedLocations) {
                // add it to the local collection
                mCachedLocations.add(o);
                // save it to the shared preference cache
                TrackerPrefs.saveLocationData(BGTrackerService.this, new JSONArray(mCachedLocations));
            }
            Log.d(TAG, "Location: "+o);
        } catch (Exception e) {
            Log.w(TAG, e.getMessage());
        }
    }

    /**
     * Begin monitoring location updates
     * @return true if location updates were started, false if already started or if an error occurs
    */
    private boolean startTracking(LocationManager locationManager, final long serviceID) {
        if (!TrackingServiceID.compareAndSet(0, serviceID))
            return false; // already tracking
        TrackerService = this;

        new Thread(new Runnable() {
            public void run() {
                // send any pending updates when we first run
                sendToServer();

                int mins = 0;
                for (;;) {
                    // check we're still actually tracking
                    if (TrackingServiceID.get() != serviceID) 
                        break;  // no longer tracking

                    saveCurrentLocation();

                    // send the updates to the server every 10 minutes
                    if ((++mins % SERVER_SYNC_RATE_MINS) == 0) {
                        sendToServer();
                    }

                    try { Thread.currentThread().sleep(POSITION_UPDATE_RATE_SECS * 1000); }
                    catch (Exception e) { break; }
                }
                Log.d(TAG, "Tracking thread finished");
            }
        }).start();

        // show a notification in the status bar while tracking is active
        setNotification("");
        TrackerPrefs.markTrackingEnabled(this, true);
        Log.i(TAG, "Tracking started");
        return true;
    }

    private void stopTracking(LocationManager locationManager) {
        if (TrackingServiceID.getAndSet(0) == 0)
            return; // already stopped
        if (TrackerService != null)
            TrackerService.stopSelf();
        TrackerService = null;
        TrackerPrefs.markTrackingEnabled(this, false);
        Log.i(TAG, "Tracking stopped");
    }

    private void setNotification(String subtext) {
        Notification noti = new Notification.Builder(this)
                .setContentTitle("Tracker")
                .setContentText("Tracker is monitoring your device location")
                .setSubText(subtext)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .build();
        NotificationManager notmgr = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notmgr != null) notmgr.notify(1, noti);
    }

    private synchronized boolean sendToServerSync() {
        try {
            JSONArray locations = TrackerPrefs.getLocationData(this);
            if (locations.length() == 0)
                return true; // nothing to send - treat as a success
            byte[] data = locations.toString().getBytes();

            Log.d(TAG, "Sending update: " + locations.toString());
            URL u = new URL(mUpdateURL);
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Content-Length", ""+data.length);
            OutputStream os = conn.getOutputStream();
            os.write(data);
            os.close();
            int result = conn.getResponseCode();
            if (result != 200) {
                Log.d(TAG, "Update returned error: " + result);
                return false;
            }
            // if we successfully updated, remove the entries from the local shared prefs cache
            String lastLocationTime = ((JSONObject)locations.get(locations.length()-1)).getString("time");
            synchronized(mCachedLocations) {
                for (int i = mCachedLocations.size()-1; i >= 0; i--) {
                    // we can use String.compareTo to compare times because the value is stored as ISO8601
                    if (((JSONObject)mCachedLocations.get(i)).getString("time").compareTo(lastLocationTime) <= 0)
                        mCachedLocations.remove(i);
                }
                TrackerPrefs.saveLocationData(BGTrackerService.this, new JSONArray(mCachedLocations));
            }
            setNotification("");
            return true;
        } catch(Exception e) {
            setNotification("Last server sync failed. " + e.getMessage());
            Log.w(TAG, e.getMessage());
        }
        return false;
    }

    private void sendToServer() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                sendToServerSync();
            }
        }).start();
    }

    @Override
    public void onStart(Intent intent,int startid) {
        // Acquire a reference to the system Location Manager
        LocationManager locationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            // if there's no location support, there's not much we can do
            Log.w(TAG, "Location support is not available");
            this.stopSelf();
            return;
        }

        String updateURL = intent.getStringExtra("update-url");
        boolean isTracking = TrackingServiceID.get() != 0;

        if (!isTracking) {
            mUpdateURL = updateURL;
            if (!startTracking(locationManager, System.currentTimeMillis()))
                this.stopSelf();
        } else {
            stopTracking(locationManager);
            this.stopSelf();
        }
    }
}

