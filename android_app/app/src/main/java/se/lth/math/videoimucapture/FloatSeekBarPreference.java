package se.lth.math.videoimucapture;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.preference.PreferenceViewHolder;
import androidx.preference.R;
import androidx.preference.SeekBarPreference;

import java.text.DecimalFormat;

public class FloatSeekBarPreference extends SeekBarPreference implements SeekBar.OnSeekBarChangeListener {

    float mResolution = 0.1f;
    DecimalFormat mDecFormat = new DecimalFormat("#.#");
    float mMax = 1;
    float mMin = 0;
    SeekBar mSeekBar = null;
    TextView mTextView = null;
    float mDefaultValue = 0.0f;

    //TODO: Implement setting from attributes
    public FloatSeekBarPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setMax(float max) {
        mMax = max;
    }

    public void setMin(float min) {
        mMin = min;
    }

    private void updateText(float newValue) {
        if (mTextView == null) {
            return;
        }
        String floatString = mDecFormat.format(newValue);
        mTextView.setText(floatString);
    }

    // Steps anchored to multiples of the resolution, with both endpoints reachable. See
    // SliderSteps for why (zoom 1.0 used to be unselectable on the Pixel 6).
    private SliderSteps steps() {
        return new SliderSteps(mMin, mMax, mResolution);
    }

    private float progressToValue(int progress) {
        return steps().valueAt(progress);
    }

    private int valueToProgress(float value) {
        return steps().indexOf(value);
    }


    public void setResolution(float res) {
        mResolution = res;
        // One more digit than the step size, so an off-grid value (e.g. 0.6704, the Pixel 6's
        // widest zoom, or an old stored 0.9704) is shown as it is, not rounded to a step.
        mDecFormat.setMinimumFractionDigits(1);
        mDecFormat.setMaximumFractionDigits(
                (int) Math.round(-Math.log10(res)) + 1
        );
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder view) {
        super.onBindViewHolder(view);

        // Non-transitive R classes (AGP 8): these ids live in the preference library's R.
        mSeekBar = (SeekBar) view.findViewById(R.id.seekbar);
        mSeekBar.setMax(steps().count() - 1);
        mSeekBar.setProgress(valueToProgress(getFloatValue()));

        mTextView = (TextView) view.findViewById(R.id.seekbar_value);
        updateText(getFloatValue());

        mSeekBar.setOnSeekBarChangeListener(this);
    }

    public void setValue(float value) {
        if (shouldPersist()) {
            persistFloat(value);
        }

        if (mSeekBar == null) {
            return;
        }

        if (valueToProgress(value) != mSeekBar.getProgress()) {
            mSeekBar.setProgress(valueToProgress(value));
            notifyChanged();
        }
    }

    private float getFloatValue() {
        return getPersistedFloat(mDefaultValue);
    }

    @Override
    protected void onSetInitialValue(Object defaultValue) {

        float myDef = defaultValue instanceof Float ? (float) defaultValue : mDefaultValue;
        float myValue = getPersistedFloat(myDef);
        setValue(myValue);
    }

    @Override
    protected Object onGetDefaultValue(TypedArray a, int index) {
        return a.getFloat(index, mDefaultValue);
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if(!fromUser) {
            return;
        }
        updateText(progressToValue(progress));
    }
    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {}

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        float v = progressToValue(seekBar.getProgress());
        setValue(v);
    }

}
