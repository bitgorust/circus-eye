package sh.diqi.circustrainer;

import android.content.Context;
import android.hardware.Camera;
import android.util.AttributeSet;
import android.widget.SeekBar;

import org.opencv.android.JavaCameraView;

/**
 * Created by kingnez on 2017/10/20.
 */

public class ZoomCameraView extends JavaCameraView {

    protected SeekBar mSeekBar;

    public ZoomCameraView(Context context, int cameraId) {
        super(context, cameraId);
    }

    public ZoomCameraView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setZoomControls(SeekBar seekBar) {
        mSeekBar = seekBar;
    }

    protected void enableZoomControls(Camera.Parameters params) {
        if (mSeekBar == null) {
            return;
        }
        final int maxZoom = params.getMaxZoom();
        mSeekBar.setMax(maxZoom);
        mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                Camera.Parameters params = mCamera.getParameters();
                params.setZoom(progress);
                mCamera.setParameters(params);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });
    }

    @Override
    protected boolean initializeCamera(int width, int height) {
        boolean ok = super.initializeCamera(width, height);
        if (ok) {
            Camera.Parameters params = mCamera.getParameters();
            if (params.isZoomSupported()) {
                enableZoomControls(params);
            }
            mCamera.setParameters(params);
        }
        return ok;
    }
}
