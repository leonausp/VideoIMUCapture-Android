package se.lth.math.videoimucapture;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.preference.PreferenceFragmentCompat;
import androidx.recyclerview.widget.RecyclerView;

public class CaptureSettings extends PreferenceFragmentCompat {
    public static final String TAG = "VIMUC-CaptureSettings";

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Log.d(TAG, "View created -- setting up actionbar");
        super.onViewCreated(view, savedInstanceState);

        Toolbar toolbar = (Toolbar) getView().findViewById(R.id.topAppBar);

        // Show every setting's full description. The preference layout caps the summary at 10
        // lines and titles at one, with no "more" -- long explanations were simply cut off.
        // Applied when a row is attached, i.e. after the preference has bound its text (and
        // re-applied whenever a recycled row is reused).
        getListView().addOnChildAttachStateChangeListener(
                new RecyclerView.OnChildAttachStateChangeListener() {
                    @Override
                    public void onChildViewAttachedToWindow(@NonNull View row) {
                        TextView summary = row.findViewById(android.R.id.summary);
                        if (summary != null) {
                            summary.setMaxLines(Integer.MAX_VALUE);
                            summary.setEllipsize(null);
                        }
                        TextView title = row.findViewById(android.R.id.title);
                        if (title != null) {
                            title.setSingleLine(false);
                            title.setMaxLines(Integer.MAX_VALUE);
                            title.setEllipsize(null);
                        }
                    }

                    @Override
                    public void onChildViewDetachedFromWindow(@NonNull View row) {
                    }
                });

        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // back button pressed
                getActivity().getSupportFragmentManager().popBackStackImmediate();
            }
        });
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        /* This will somehow override default values we set when starting the activity.
           Therefore we set them as not persistent in the XML file.
         */
        setPreferencesFromResource(R.xml.settings, rootKey);
        if (!BuildConfig.STILLS_UI) {
            // Periodic stereo pairs are a stills feature; hidden with the rest of them.
            androidx.preference.Preference stereo = findPreference("stereo_interval_s");
            if (stereo != null) {
                stereo.setVisible(false);
            }
        }

        CameraSettingsManager cameraSettingsManager = ((CameraCaptureActivity) getActivity()).getmCameraSettingsManager();
        cameraSettingsManager.updatePreferences(getPreferenceScreen());
        setUpLens();

        // Basic vs expert: the expert categories are shown only with the switch on. Visibility is
        // set on the CATEGORIES, so settings the camera code hides inside them stay hidden.
        androidx.preference.SwitchPreferenceCompat expert = findPreference("show_expert_settings");
        if (expert != null) {
            setExpertVisible(expert.isChecked());
            expert.setOnPreferenceChangeListener((pref, value) -> {
                setExpertVisible((Boolean) value);
                return true;
            });
        }
        androidx.preference.Preference reset = findPreference("reset_defaults");
        if (reset != null && cameraSettingsManager != null) {
            // Not a fixed text: what the reset gives depends on what this camera offers.
            reset.setSummary(cameraSettingsManager.describeRecommended());
        }
        if (reset != null) {
            reset.setOnPreferenceClickListener(pref -> {
                confirmReset();
                return true;
            });
        }

    }


    /**
     * "Lens": one choice for "which lens records", whatever the phone does underneath -- zoom
     * below 1 on a logical multi-camera (Pixel 6 ultrawide), a separate camera id (Galaxy S21
     * ultrawide). It writes the two underlying settings, rear_camera and zoom_ratio, and reopens
     * the camera. Digital zoom stays in the expert settings.
     */
    private void setUpLens() {
        androidx.preference.ListPreference p = findPreference("lens");
        if (p == null) {
            return;
        }
        android.hardware.camera2.CameraManager cm = (android.hardware.camera2.CameraManager)
                requireContext().getSystemService(android.content.Context.CAMERA_SERVICE);
        java.util.List<String[]> lenses = CameraUtils.listLenses(cm);
        if (lenses.isEmpty()) {
            p.setVisible(false);
            return;
        }
        String[] keys = new String[lenses.size()];
        String[] labels = new String[lenses.size()];
        for (int i = 0; i < lenses.size(); i++) {
            keys[i] = lenses.get(i)[0];
            labels[i] = lenses.get(i)[1];
        }
        p.setEntries(labels);
        p.setEntryValues(keys);
        android.content.SharedPreferences prefs =
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext());
        // Current lens: the open camera, and the option whose zoom is closest to the current zoom.
        String id = CameraUtils.getRearCameraId(cm, prefs.getString("rear_camera", null));
        float zoom = prefs.getFloat("zoom_ratio", RecommendedDefaults.ZOOM_RATIO);
        String current = null;
        float best = Float.MAX_VALUE;
        for (String k : keys) {
            String[] kz = k.split(":");
            if (kz[0].equals(id)) {
                float d = Math.abs(Float.parseFloat(kz[1]) - zoom);
                if (d < best) {
                    best = d;
                    current = k;
                }
            }
        }
        if (current != null) {
            p.setValue(current);
        }
        p.setVisible(true);
        p.setOnPreferenceChangeListener((pref, value) -> {
            String[] kz = ((String) value).split(":");
            prefs.edit().putString("rear_camera", kz[0])
                    .putFloat("zoom_ratio", Float.parseFloat(kz[1])).commit();
            android.widget.Toast.makeText(requireContext(), "Switching lens…",
                    android.widget.Toast.LENGTH_SHORT).show();
            androidx.fragment.app.FragmentActivity a = requireActivity();
            a.getSupportFragmentManager().popBackStackImmediate();
            a.recreate();   // reopens the camera with the new id and zoom
            return false;   // already stored
        });
    }

    private static final String[] EXPERT_CATEGORIES = {
            "general_category", "stabilization_category",
            "exposure_category", "recording_category",
    };

    private void setExpertVisible(boolean visible) {
        for (String key : EXPERT_CATEGORIES) {
            androidx.preference.Preference c = findPreference(key);
            if (c != null) {
                c.setVisible(visible);
            }
        }
    }

    /**
     * Back to RecommendedDefaults: every setting on this screen is removed from the stored
     * preferences, then the camera screen is rebuilt, which writes each default afresh (the
     * settings classes store their default when a key is missing). Test-matrix history and
     * other non-setting state are kept.
     */
    private void confirmReset() {
        new android.app.AlertDialog.Builder(requireContext())
                .setTitle("Reset to recommended settings?")
                .setMessage("All camera settings return to the recommended values. Recordings are not affected.")
                .setPositiveButton("Reset", (d, w) -> resetToDefaults())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void resetToDefaults() {
        android.content.SharedPreferences prefs =
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext());
        android.content.SharedPreferences.Editor e = prefs.edit();
        java.util.ArrayDeque<androidx.preference.Preference> todo = new java.util.ArrayDeque<>();
        todo.add(getPreferenceScreen());
        while (!todo.isEmpty()) {
            androidx.preference.Preference p = todo.poll();
            if (p.getKey() != null && !"reset_defaults".equals(p.getKey())) {
                e.remove(p.getKey());
            }
            if (p instanceof androidx.preference.PreferenceGroup) {
                androidx.preference.PreferenceGroup g = (androidx.preference.PreferenceGroup) p;
                for (int i = 0; i < g.getPreferenceCount(); i++) {
                    todo.add(g.getPreference(i));
                }
            }
        }
        e.remove("rear_camera");   // behind the Lens setting, not on the screen itself
        e.commit();   // synchronously: the rebuilt screen must not read the old values
        android.widget.Toast.makeText(requireContext(), "Settings reset to recommended values",
                android.widget.Toast.LENGTH_SHORT).show();
        androidx.fragment.app.FragmentActivity a = requireActivity();
        a.getSupportFragmentManager().popBackStackImmediate();
        a.recreate();
    }

}
