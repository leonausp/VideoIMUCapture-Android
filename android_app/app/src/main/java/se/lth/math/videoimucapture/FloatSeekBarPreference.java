package se.lth.math.videoimucapture;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.TypedArray;
import android.text.InputType;
import android.util.AttributeSet;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.preference.PreferenceViewHolder;
import androidx.preference.R;
import androidx.preference.SeekBarPreference;

import java.text.DecimalFormat;
import java.util.Locale;

/**
 * A slider for a number, with two ways to set it exactly:
 *  - the slider snaps to sensible positions: a grid (multiples of the resolution) or an explicit
 *    list such as ISO/shutter 1/3 stops, always including the device's min and max (SliderSteps);
 *  - tapping the row or the value opens a dialog to type any value in [min, max]. A typed value
 *    is stored exactly (ISO 1000, 5 ms); the slider then shows the nearest position.
 * Integer mode (ISO) stores an int, as SeekBarPreference did before, so existing settings read
 * back unchanged.
 */
public class FloatSeekBarPreference extends SeekBarPreference implements SeekBar.OnSeekBarChangeListener {

    float mResolution = 0.1f;
    DecimalFormat mDecFormat = new DecimalFormat("#.#");
    float mMax = 1;
    float mMin = 0;
    float[] mStepValues = null;   // explicit slider positions, or null for the grid
    boolean mInteger = false;     // persist as int (ISO)
    SeekBar mSeekBar = null;
    TextView mTextView = null;
    float mDefaultValue = 0.0f;

    //TODO: Implement setting from attributes
    public FloatSeekBarPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * The slider's range. Deliberately NOT called setMin/setMax: SeekBarPreference has final
     * setMin(int)/setMax(int), and Java resolves setMax(Integer) -- an ISO range from
     * Range<Integer> -- to those instead of a float overload. That left this slider at its
     * default 0..1 (ISO showed only 0 or 1). Distinct names make the wrong call impossible.
     */
    public void setRange(float min, float max) {
        mMin = min;
        mMax = max;
    }

    /** Slider positions at these values (within [min, max]) instead of the resolution grid. */
    public void setStepValues(float[] values) {
        mStepValues = values;
    }

    /** Store and show whole numbers (e.g. ISO); persisted as an int. */
    public void setIntegerMode(boolean integer) {
        mInteger = integer;
        if (integer) {
            mDecFormat = new DecimalFormat("#");
        }
    }

    private String format(float value) {
        return mDecFormat.format(value);
    }

    private void updateText(float newValue) {
        if (mTextView == null) {
            return;
        }
        mTextView.setText(format(newValue));
    }

    private SliderSteps steps() {
        return mStepValues != null
                ? SliderSteps.ofValues(mMin, mMax, mStepValues)
                : new SliderSteps(mMin, mMax, mResolution);
    }

    private float progressToValue(int progress) {
        float v = steps().valueAt(progress);
        return mInteger ? Math.round(v) : v;
    }

    private int valueToProgress(float value) {
        return steps().indexOf(value);
    }

    public void setResolution(float res) {
        mResolution = res;
        if (mInteger) {
            return;
        }
        // One more digit than the step size, so an off-grid value (a typed one, or the Pixel 6's
        // widest zoom 0.6704) is shown as it is, not rounded to a step.
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
        // The number is a button too: tap it to type an exact value.
        if (mTextView != null) {
            mTextView.setOnClickListener(v -> showInputDialog());
        }

        mSeekBar.setOnSeekBarChangeListener(this);
    }

    /** Tapping the row (title or summary) also opens the input dialog. */
    @Override
    protected void onClick() {
        showInputDialog();
    }

    private void showInputDialog() {
        if (!isEnabled()) {
            return;
        }
        Context ctx = getContext();
        EditText input = new EditText(ctx);
        input.setInputType(mInteger ? InputType.TYPE_CLASS_NUMBER
                : InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(format(getFloatValue()));
        input.setSelectAllOnFocus(true);
        String range = String.format(Locale.US, "Range %s – %s%s", format(mMin), format(mMax),
                getSummary() == null ? "" : "  (" + getSummary() + ")");
        new AlertDialog.Builder(ctx)
                .setTitle(getTitle())
                .setMessage(range)
                .setView(input)
                .setPositiveButton("OK", (d, w) -> {
                    float v;
                    try {
                        v = Float.parseFloat(input.getText().toString().trim().replace(',', '.'));
                    } catch (NumberFormatException e) {
                        return;   // nothing sensible typed: keep the current value
                    }
                    v = Math.max(mMin, Math.min(mMax, v));
                    if (mInteger) {
                        v = Math.round(v);
                    }
                    if (callChangeListener(v)) {
                        setNumber(v);
                        updateText(v);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
        input.requestFocus();
    }

    /** Store and show a value. Deliberately not named setValue: see setRange(). */
    public void setNumber(float value) {
        if (shouldPersist()) {
            if (mInteger) {
                persistInt(Math.round(value));
            } else {
                persistFloat(value);
            }
        }

        if (mSeekBar == null) {
            return;
        }

        if (valueToProgress(value) != mSeekBar.getProgress()) {
            mSeekBar.setProgress(valueToProgress(value));
        }
        notifyChanged();
    }

    private float getFloatValue() {
        return mInteger ? getPersistedInt(Math.round(mDefaultValue))
                : getPersistedFloat(mDefaultValue);
    }

    @Override
    protected void onSetInitialValue(Object defaultValue) {
        float myDef = defaultValue instanceof Float ? (float) defaultValue
                : defaultValue instanceof Integer ? (float) (Integer) defaultValue : mDefaultValue;
        float myValue = mInteger ? getPersistedInt(Math.round(myDef)) : getPersistedFloat(myDef);
        setNumber(myValue);
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
        setNumber(v);
    }

}
