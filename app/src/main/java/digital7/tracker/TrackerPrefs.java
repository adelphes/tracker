package digital7.tracker;

import android.content.*;
import android.util.*;
import org.json.*;

/**
* Simple class to manage the shared preferences for the App
 */
public class TrackerPrefs {

    static final String TAG = "TrackerPrefs";

    // shared preference (settings) ids
    static final String SHARED_PREFS_LOCATIONS_KEY = "locations";
    static final String SHARED_PREFS_TRACKERID_KEY = "trackerid";
    public static final String SHARED_PREFS_TRACKING_ENABLED_KEY = "tracking-enabled";
    public static final String SHARED_PREFS_NAME = "trackerprefs";

    private static SharedPreferences getSharedPreferences(Context context) {
        return context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isTrackingEnabled(Context context) {
        SharedPreferences sharedPrefs = TrackerPrefs.getSharedPreferences(context);
        return sharedPrefs.getBoolean(SHARED_PREFS_TRACKING_ENABLED_KEY, false);
    }

    public static void markTrackingEnabled(Context context, boolean enabled) {
        SharedPreferences sharedPrefs = TrackerPrefs.getSharedPreferences(context);
        SharedPreferences.Editor editor = sharedPrefs.edit();
        editor.putBoolean(SHARED_PREFS_TRACKING_ENABLED_KEY, enabled);
        editor.commit();
    }

    public static String getTrackerID(Context context) {
        SharedPreferences sharedPrefs = TrackerPrefs.getSharedPreferences(context);
        String id = sharedPrefs.getString(SHARED_PREFS_TRACKERID_KEY, "");
        if (id.length() == 0) {
            // generate a new id (and save it) - this is designed to be simple, not secure!
            id = String.format("%06d", (int)(new java.util.Random().nextDouble()*1e6));
            SharedPreferences.Editor editor = sharedPrefs.edit();
            editor.putString(SHARED_PREFS_TRACKERID_KEY, id);
            editor.commit();
        }
        return id;
    }

    public static void registerChangeListener (Context context, SharedPreferences.OnSharedPreferenceChangeListener listener) {
        SharedPreferences sharedPrefs = TrackerPrefs.getSharedPreferences(context);
        sharedPrefs.registerOnSharedPreferenceChangeListener(listener);
    }

    public static void unregisterChangeListener (Context context, SharedPreferences.OnSharedPreferenceChangeListener listener) {
        SharedPreferences sharedPrefs = TrackerPrefs.getSharedPreferences(context);
        sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener);
    }

    public static JSONArray getLocationData(Context context) {
        SharedPreferences sharedPrefs = TrackerPrefs.getSharedPreferences(context);
        try { 
            JSONArray locations = new JSONArray(sharedPrefs.getString(SHARED_PREFS_LOCATIONS_KEY, "[]"));
            return locations;
        } catch(Exception e) {
            Log.e(TAG, e.getMessage());
            return new JSONArray();
        }
    }

    public static boolean saveLocationData(Context context, JSONArray locations) {
        try {
            SharedPreferences sharedPrefs = TrackerPrefs.getSharedPreferences(context);
            SharedPreferences.Editor editor = sharedPrefs.edit();
            editor.putString(SHARED_PREFS_LOCATIONS_KEY, locations.toString());
            editor.commit();
            return true;
        }catch(Exception e) {
            Log.e(TAG, e.getMessage());
            return false;
        }
    }

}
