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
    // how often we retrieve location updates (minutes)
    static final int POSITION_UPDATE_RATE_MINS = 1;
    // how often we sync location data with the server (minutes)
    static final int SERVER_SYNC_RATE_MINS = 10;

    // instance of the tracker service
    static BGTrackerService TrackerService;
    static final AtomicBoolean Tracking = new AtomicBoolean(false);

    // for simplicity, the locations are stored as an list of JSON objects: {"lat":nnn, "long":nnn, "time":"<ISO8601>"}
    static final ArrayList<JSONObject> mCachedLocations = new ArrayList<>();
    PendingIntent mUpdateIntent;
    LocationListener mLocationListener;
    int mMinsUntilNextPositionUpdate;
    int mMinsUntilNextServerSync;
    String mUpdateURL; // the API update URL

    @Override
    public IBinder onBind(Intent intent) {
        // not bindable
        return null;
    }

    public void onCreate() {
    }

    public void onDestroy() {
    }
    
    private void saveCurrentLocation(Location location) {
        if (location == null) {
            // use last known good
            LocationManager locationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);
            if (locationManager == null)
                return; // just in case

            location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            // update to the GPS location if it's available (and more recent)
            Location gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (gps != null) {
                if (location == null || (gps.getTime() >= location.getTime()))
                    location = gps; // better match
            }
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
    private boolean startTracking() {
        if (Tracking.getAndSet(true))
            return false; // already tracking
        TrackerService = this;
        Log.i(TAG, "Tracking started");

        Intent ssIntent = new Intent(this, BGTrackerService.class);
        ssIntent.putExtra("action", "update");
        mUpdateIntent = PendingIntent.getService(this, 0, ssIntent, 0);

        // force an immediate update of the position and sync with server
        saveCurrentLocation(null);
        sendToServer();

        // set up the (wakable) alarm
        mMinsUntilNextPositionUpdate = POSITION_UPDATE_RATE_MINS;
        mMinsUntilNextServerSync = SERVER_SYNC_RATE_MINS;
        AlarmManager am = (AlarmManager)this.getSystemService(Context.ALARM_SERVICE);
        am.setRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime(), 60*1000, mUpdateIntent);

        // show a notification in the status bar while tracking is active
        setNotification("");
        TrackerPrefs.markTrackingEnabled(this, true);
        return true;
    }

    private void stopTracking() {
        if (!Tracking.getAndSet(false))
            return; // already stopped
        TrackerPrefs.markTrackingEnabled(this, false);
        NotificationManager notmgr = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notmgr != null) notmgr.cancelAll();
        if (TrackerService != null) {
            TrackerService.cancelTrackingUpdates();
            TrackerService.stopSelf();
            TrackerService = null;
        }
        Log.i(TAG, "Tracking stopped");
    }

    private void cancelTrackingUpdates() {
        if (mUpdateIntent != null) {
            AlarmManager am = (AlarmManager)this.getSystemService(Context.ALARM_SERVICE);
            am.cancel(mUpdateIntent);
            mUpdateIntent = null;
        }

        if (mLocationListener != null) {
            LocationManager lm = (LocationManager)this.getSystemService(Context.LOCATION_SERVICE);
            lm.removeUpdates(mLocationListener);
            mLocationListener = null;
        }

        // do a final send of any pending data
        sendToServer();
    }

    private void updateTracking() {
        Log.i(TAG, "updateTracking");

        if (--mMinsUntilNextPositionUpdate <= 0) {
            mMinsUntilNextPositionUpdate = POSITION_UPDATE_RATE_MINS;
            // retrieve the any current GPS location - we could do this via last known good, but we get a better result
            // by requesting a single update
            //saveCurrentLocation(null);
            if (mLocationListener == null) {
                final LocationManager lm = (LocationManager)this.getSystemService(Context.LOCATION_SERVICE);
                if (lm != null) {
                    mLocationListener = new LocationListener() {
                        public void onLocationChanged(Location location) {
                            // turn off updates when we've got the position
                            lm.removeUpdates(mLocationListener);
                            mLocationListener = null;

                            saveCurrentLocation(location);
                        }

                        public void onStatusChanged(String provider, int status, Bundle extras) {}

                        public void onProviderEnabled(String provider) {}

                        public void onProviderDisabled(String provider) {}
                    };
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, mLocationListener);
                }
            }
        }

        if (--mMinsUntilNextServerSync <= 0) {
            mMinsUntilNextServerSync = SERVER_SYNC_RATE_MINS;
            sendToServer();
        }
    }

    private void setNotification(String subtext) {
        if (!Tracking.get()) 
            return;
        Notification noti = new Notification.Builder(this)
                .setContentTitle("Tracker: " + TrackerPrefs.getTrackerID(this))
                .setContentText("Tracker is monitoring your device location")
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), 0))
                .setSubText(subtext)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .build();
        NotificationManager notmgr = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notmgr != null) notmgr.notify(1, noti);
    }

    private synchronized void sendToServerSyncWithRetry() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        PowerManager.WakeLock wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,TAG);
        wakeLock.acquire();
        try {
            for (int i=0; i < 3; i++) {
                if (sendToServerSync())
                    break;
            }
        } finally {
            wakeLock.release();
        }
    }

    private synchronized boolean sendToServerSync() {
        try {
            JSONArray locations = TrackerPrefs.getLocationData(this);
            if (locations.length() == 0)
                return true; // nothing to send - treat as a success
            byte[] data = locations.toString().getBytes();

            Log.d(TAG, "Sending updates: " + locations.length());
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
                Log.d(TAG, "Update returned server response: " + result);
                return false;
            }
            // if we successfully updated, remove the entries from the local shared prefs cache
            String lastLocationTime = ((JSONObject)locations.get(locations.length()-1)).getString("time");
            synchronized(mCachedLocations) {
                for (int i = mCachedLocations.size()-1; i >= 0; i--) {
                    // we can use String.compareTo to compare times because the value is stored as ISO8601
                    if (mCachedLocations.get(i).getString("time").compareTo(lastLocationTime) <= 0)
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
                sendToServerSyncWithRetry();
            }
        }).start();
    }

    @Override
    public void onStart(Intent intent,int startid) {
        if ("update".equals(intent.getStringExtra("action"))) {
            if (TrackerService != null)
                TrackerService.updateTracking();
            this.stopSelf();
            return;
        }

        String updateURL = intent.getStringExtra("update-url");
        boolean isTracking = Tracking.get();

        if (!isTracking) {
            this.mUpdateURL = updateURL;
            if (!this.startTracking())
                this.stopSelf();
        } else {
            this.stopTracking();
            this.stopSelf();
        }
    }
}

