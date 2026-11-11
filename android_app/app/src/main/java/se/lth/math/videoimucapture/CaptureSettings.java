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
        if (reset != null) {
            reset.setOnPreferenceClickListener(pref -> {
                confirmReset();
                return true;
            });
        }

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
        e.commit();   // synchronously: the rebuilt screen must not read the old values
        android.widget.Toast.makeText(requireContext(), "Settings reset to recommended values",
                android.widget.Toast.LENGTH_SHORT).show();
        androidx.fragment.app.FragmentActivity a = requireActivity();
        a.getSupportFragmentManager().popBackStackImmediate();
        a.recreate();
    }

}
