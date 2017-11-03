package sh.diqi.circustrainer;

import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Toast;

import com.qiniu.android.common.AutoZone;
import com.qiniu.android.http.ResponseInfo;
import com.qiniu.android.storage.Configuration;
import com.qiniu.android.storage.UpCompletionHandler;
import com.qiniu.android.storage.UploadManager;
import com.qiniu.android.utils.StringUtils;

import org.json.JSONException;
import org.json.JSONObject;
import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame;
import org.opencv.android.JavaCameraView;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfInt;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.video.BackgroundSubtractor;
import org.opencv.video.BackgroundSubtractorKNN;
import org.opencv.video.BackgroundSubtractorMOG2;
import org.opencv.video.Video;

import java.io.File;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity implements CameraBridgeViewBase.CvCameraViewListener2 {

    static {
        OpenCVLoader.initDebug();
    }

    private static final String TAG = "CircusTrainer:Main";

    private static final int TASK_CAPTURE = 1;
    private static final int BG_SUBTRACTOR_HISTORY = 50;
    private static final int BG_SUBTRACTOR_THRESHOLD = 500;

    private static final String HIAR_CID = "9237";
    private static final String HIAR_APPKEY = "rKsiUFBaXZ";
    private static final String HIAR_SECRET = "1afd77ba2a542e2bf26fd644baf6fd7a";
    private static final String HIAR_ACCOUNT = "zengjing@diqi.sh";
    private static final String HIAR_PASSWORD = "K!wqGTof1DIN";

    private static final String QINIU_TOKEN = "K3koHJdGYn5fddYxcBY0Ujai-deaAJQuSzvbmlCQ:EGgBk9r6BO6yOJa1gLcQzCHoOkE=:eyJzY29wZSI6ImZhZGFvamlhIiwiZGVhZGxpbmUiOjE1Mzk4NTI1NDN9";

    private ZoomCameraView mOpenCvCameraView;
    private Button mResetButton;
    private Button mCaptureButton;
    private EditText mEditTextIndex;
    private EditText mEditTextName;

    private Handler mHandler;

    private File mMediaStorageDir;

    private boolean mIsResetting = false;
    private boolean mIsCapturing = false;
    private Mat mBaseGray = null;
    private Mat mCurrentGray;
    private Mat mOriginRgba;
    private Mat mMarkedRgba;

    private BackgroundSubtractor mBgSubtractor;
    private Mat mfgMask;
    private int mHistoryFrames;

    private OkHttpClient mHttpClient;
    private UploadManager mUploadManager;

    private String mHiARToken = null;

    private boolean mIsOpenCVReady = false;

    private String mUniqueID = null;

    private BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {
            switch (status) {
                case LoaderCallbackInterface.SUCCESS: {
                    Log.i(TAG, "OpenCV loaded successfully");
                    mOpenCvCameraView.enableView();
                }
                default: {
                    super.onManagerConnected(status);
                }
            }
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mOpenCvCameraView = (ZoomCameraView) findViewById(R.id.OpenCvView);
        mOpenCvCameraView.setZoomControls((SeekBar) findViewById(R.id.seekBarZoom));
        mEditTextIndex = (EditText) findViewById(R.id.editTextIndex);
        mEditTextName = (EditText) findViewById(R.id.editTextName);
        mResetButton = (Button) findViewById(R.id.buttonReset);
        mCaptureButton = (Button) findViewById(R.id.buttonCapture);

        mHandler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case TASK_CAPTURE: {
                        if (msg.obj != null) {
                            Toast.makeText(getBaseContext(), (String) msg.obj, Toast.LENGTH_SHORT).show();
                        }
                    }
                    default: {
                        super.handleMessage(msg);
                    }
                }
            }
        };

        mOpenCvCameraView.setFocusable(true);
        mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);
        mOpenCvCameraView.setCvCameraViewListener(this);

        mResetButton.setOnClickListener(v -> mIsResetting = true);

        mCaptureButton.setOnClickListener(v -> mIsCapturing = mIsOpenCVReady);

        mUniqueID = ((TelephonyManager) getSystemService(TELEPHONY_SERVICE)).getDeviceId();
        mMediaStorageDir = ensureDir("CircusTrainer/alpha");
        mEditTextIndex.setText(mUniqueID);
//        ensureDir("CircusTrainer/" + mUniqueID);

//        mHttpClient = new OkHttpClient();
//        getHiARToken(HIAR_ACCOUNT, HIAR_PASSWORD);

        Configuration config = new Configuration.Builder().useHttps(true).zone(AutoZone.autoZone).build();
        mUploadManager = new UploadManager(config);
    }

    @Override
    protected void onResume() {
        super.onResume();
        OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_3_0, this, mLoaderCallback);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mOpenCvCameraView != null) {
            mOpenCvCameraView.disableView();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mOpenCvCameraView != null) {
            mOpenCvCameraView.disableView();
        }
    }

    @Override
    public void onCameraViewStarted(int width, int height) {
        mIsOpenCVReady = true;
//        resetBackgroundSubtractor();
    }

    @Override
    public void onCameraViewStopped() {
        if (mBaseGray != null) {
            mBaseGray.release();
        }
        if (mCurrentGray != null) {
            mCurrentGray.release();
        }
        if (mOriginRgba != null) {
            mOriginRgba.release();
        }
        if (mMarkedRgba != null) {
            mMarkedRgba.release();
        }
        mIsOpenCVReady = false;
    }

    @Override
    public Mat onCameraFrame(CvCameraViewFrame inputFrame) {
        if (mIsOpenCVReady) {
//            Mat gray = inputFrame.gray();
//            Imgproc.GaussianBlur(gray, gray, new Size(3, 3), 0);
//            Imgproc.Canny(gray, gray, 35, 35 * 3);
//            if (mBgSubtractor != null && mfgMask != null) {
//                if (mHistoryFrames-- > 0) {
//                    mBgSubtractor.apply(inputFrame.gray(), mfgMask);
//                } else {
//                    mBgSubtractor.apply(inputFrame.gray(), mfgMask, 0);
//                }
//            }
//            gray.release();
        }

        if (mIsResetting) {
//            resetBackgroundSubtractor();
//            mBaseGray = inputFrame.gray();
//            Imgproc.Canny(mBaseGray, mBaseGray, 80, 255);
//            Imgproc.GaussianBlur(mBaseGray, mBaseGray, new Size(7, 7), 0);
            mIsResetting = false;
        } else if (mIsCapturing) {
            final Mat originRgba = inputFrame.rgba();
            new Thread() {
                @Override
                public void run() {
                    mHandler.obtainMessage(TASK_CAPTURE, "开始保存").sendToTarget();
//                    String index = mEditTextIndex.getText().toString();
                    String name = mEditTextName.getText().toString();
                    if (/*StringUtils.isNullOrEmpty(index) || */StringUtils.isNullOrEmpty(name)) {
                        mHandler.obtainMessage(TASK_CAPTURE, "信息不全").sendToTarget();
                        return;
                    }

                    if (originRgba != null) {
                        Mat outputMat = new Mat(originRgba.size(), originRgba.type());
                        MatOfInt params = new MatOfInt(Imgcodecs.IMWRITE_PNG_COMPRESSION, 9);
                        File imageDir = ensureDir("CircusTrainer/alpha/" + name);
                        int index = imageDir.listFiles().length;
                        String fileName = index + ".png";

                        File originFile = new File(imageDir.getAbsolutePath(), fileName);
                        Imgproc.cvtColor(originRgba, outputMat, Imgproc.COLOR_RGBA2BGR);
                        Imgcodecs.imwrite(originFile.getAbsolutePath(), outputMat, params);
                        putInFeiniu(originFile, "tensorflow/images/" + mUniqueID + "/" + name + "/", fileName);
//                        putInHiAR(originFile, HIAR_CID, fileName);

//                        if (mMarkedRgba != null && mMarkedRgba != originRgba) {
//                            File markedFile = new File(mMediaStorageDir.getAbsolutePath(), "marked/" + fileName);
//                            Imgproc.cvtColor(mMarkedRgba, outputMat, Imgproc.COLOR_RGBA2BGR);
//                            Imgcodecs.imwrite(markedFile.getAbsolutePath(), outputMat, params);
//                            putInFeiniu(markedFile, "tensorflow/images/", fileName);
//                            // TODO: put xml
//                            mMarkedRgba.release();
//                        }

                        outputMat.release();
                        originRgba.release();
                    }
                    mHandler.obtainMessage(TASK_CAPTURE, "保存成功").sendToTarget();
                }
            }.start();
            mIsCapturing = false;
        }
//        if (mfgMask != null) {
//            if (mHistoryFrames <= 0) {
//                mBgSubtractor.getBackgroundImage(mfgMask);
//            }
//            return mfgMask;
//        }
        return inputFrame.rgba();
//        mOriginRgba = inputFrame.rgba();
//        mMarkedRgba = mOriginRgba.clone();

//        mCurrentGray = inputFrame.gray();
//        if (mBaseGray != null) {
////            Imgproc.Canny(mCurrentGray, mCurrentGray, 80, 255);
////            Imgproc.GaussianBlur(mCurrentGray, mCurrentGray, new Size(5, 5), 0);
//            Mat diff = mCurrentGray.clone();
//            Core.absdiff(mBaseGray, mCurrentGray, diff);
//            Imgproc.threshold(diff, diff, 10, 255, Imgproc.THRESH_BINARY);
//            Imgproc.dilate(diff, diff, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(3, 3)), new Point(-1, -1), 2);
//
//            List<MatOfPoint> contours = new ArrayList<>();
//            Imgproc.findContours(diff, contours, new Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
//            diff.release();
//            if (contours.size() > 0) {
//                List<Point> points = new ArrayList<>();
//                for (int i = 0; i < contours.size(); i++) {
//                    if (Imgproc.contourArea(contours.get(i)) > 50) {
////                        Log.d(TAG, String.valueOf(Imgproc.contourArea(contours.get(i))));
//                        points.addAll(contours.get(i).toList());
//                    }
//                }
//                if (points.size() > 0) {
//                    MatOfPoint matPoints = new MatOfPoint();
//                    matPoints.fromList(points);
//                    Rect rect = Imgproc.boundingRect(matPoints);
//                    matPoints.release();
//                    Imgproc.rectangle(mMarkedRgba, rect.tl(), rect.br(), new Scalar(0, 255, 0, 255), 3);
//                }
//            }
//        }
//        return mMarkedRgba;
    }

    private File ensureDir(String path) {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), path);
        if (!dir.exists()) {
            if (!dir.mkdirs()) {
                Log.e(TAG, "failed to create directory: " + path);
            }
        }
        return dir;
    }

    private void resetBackgroundSubtractor() {
        mHistoryFrames = BG_SUBTRACTOR_HISTORY;
        mBgSubtractor = Video.createBackgroundSubtractorKNN(BG_SUBTRACTOR_HISTORY, BG_SUBTRACTOR_THRESHOLD, false);
        if (mfgMask != null) {
            mfgMask.release();
        } else {
            mfgMask = new Mat();
        }
    }

    private String hiarSignature() {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        List<String> params = new ArrayList<>();
        params.add(HIAR_APPKEY);
        params.add("1234abcd");
        params.add(timestamp);
        params.add(HIAR_SECRET);
        Collections.sort(params);
        String sortedParams = TextUtils.join("", params);
        SecretKeySpec signingKey = new SecretKeySpec(HIAR_SECRET.getBytes(), "HmacSHA1");
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(signingKey);
            return Base64.encodeToString(mac.doFinal(sortedParams.getBytes()), Base64.NO_WRAP);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            e.printStackTrace();
        }
        return null;
    }

    private void getHiARToken(String account, String password) {
        String signature = hiarSignature();
        if (signature == null) {
            Log.e(TAG, "signature == null");
            return;
        }
        RequestBody body = new FormBody.Builder()
                .addEncoded("account", account)
                .addEncoded("password", password)
                .build();
        Request request = new Request.Builder()
                .url("https://api.hiar.io/v1/account/signin")
                .addHeader("HiARAuthorization", signature)
                .post(body)
                .build();
        mHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                e.printStackTrace();
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful()) {
                    try {
                        JSONObject jsonObject = new JSONObject(response.body().string());
                        mHiARToken = jsonObject.getString("token");
                        Log.d(TAG, "HiAR token = " + mHiARToken);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
    }

    private void putInHiAR(File file, String cid, String name) {
        if (mHiARToken == null) {
            return;
        }
        String signature = hiarSignature();
        if (signature == null) {
            Log.e(TAG, "signature == null");
            return;
        }
        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", name, RequestBody.create(MediaType.parse("image/png"), file))
                .build();
        Request request = new Request.Builder()
                .url("https://api.hiar.io/v1/collection/" + cid + "/target")
                .addHeader("token", mHiARToken)
                .addHeader("HiARAuthorization", signature)
                .post(body)
                .build();
        mHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                e.printStackTrace();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                Log.d(TAG, response.body().string());
            }
        });
    }

    private void putInFeiniu(final File file, String path, String name) {
        mUploadManager.put(file, path + name, QINIU_TOKEN,
                (key, info, res) -> {
                    //res包含hash、key等信息，具体字段取决于上传策略的设置
                    if (info.isOK()) {
                        Log.i(TAG, "Upload Success");
                    } else {
                        Log.i(TAG, "Upload Fail");
                        //如果失败，这里可以把info信息上报自己的服务器，便于后面分析上传错误原因
                    }
                    Log.i(TAG, key + ",\r\n " + info + ",\r\n " + res);
                }, null);
    }
}
