package digital7.tracker;

import android.app.*;
import android.content.*;
import android.os.*;
import android.util.*;
import android.view.*;
import android.widget.*;

public class MainActivity extends Activity {

    String mTrackerID;
    public static final String SERVER_SCHEME = "http";  // or https if SSL is set up
    //public static final String SERVER_ADDRESS = "192.168.1.65:8060";  // local dev
    public static final String SERVER_ADDRESS = "138.68.166.82";
    public static final String TAG = "TrackerActivity";
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.setContentView(R.layout.activity_main);
        Button toggleTrackBtn = (Button)findViewById(R.id.trackingBtn);
        if (toggleTrackBtn != null)
            toggleTrackBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleTracking();
                }
            });

        this.mTrackerID = TrackerPrefs.getTrackerID(this);
        TextView trackerID = (TextView)findViewById(R.id.trackerid);
        if (trackerID != null)
            trackerID.setText(this.mTrackerID);
        TextView clientInfo = (TextView)findViewById(R.id.clientinfo);
        if (clientInfo != null)
            clientInfo.setText(String.format("%s://%s", SERVER_SCHEME, SERVER_ADDRESS));
    }

    @Override
    public void onPause() {
        super.onPause();
        TrackerPrefs.unregisterChangeListener(this, onPrefsChanged);
    }
    
    @Override
    public void onResume() {
        super.onResume();
        // monitor changes to tracking status via shared prefs
        TrackerPrefs.registerChangeListener(this, onPrefsChanged);
    }

    private SharedPreferences.OnSharedPreferenceChangeListener onPrefsChanged = new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if (key.equals(TrackerPrefs.SHARED_PREFS_TRACKING_ENABLED_KEY)) {
                final boolean enabled = TrackerPrefs.isTrackingEnabled(MainActivity.this);
                MainActivity.this.runOnUiThread(new Runnable() {
                    public void run() {
                        Toast.makeText(MainActivity.this, enabled ? "Tracking enabled" : "Tracking disabled", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }
    };

    private void toggleTracking() {
        Intent i = new Intent(this, BGTrackerService.class);
        i.putExtra("update-url", String.format("%s://%s/v1/locations/%s/update", SERVER_SCHEME, SERVER_ADDRESS, mTrackerID));
        try {
            startService(i);
        } catch (Exception e) {
            reportProblem(e);
        }
    }

    protected void reportProblem(Exception e) {
        Log.e(TAG, e.getMessage());
    }
}
