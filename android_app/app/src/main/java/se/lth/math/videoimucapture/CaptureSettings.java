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

        CameraSettingsManager cameraSettingsManager = ((CameraCaptureActivity) getActivity()).getmCameraSettingsManager();
        cameraSettingsManager.updatePreferences(getPreferenceScreen());

    }

}
