package com.nekocyte.companion;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;

/** 应用内相机：OEM 系统相机不写 EXTRA_OUTPUT 时使用，保证拍照结果可发送。 */
public class CameraCaptureActivity extends BaseActivity implements SurfaceHolder.Callback {
    private SurfaceView preview;
    private SurfaceHolder holder;
    private Camera camera;
    private int cameraId = 0;
    private boolean surfaceReady = false;
    private boolean taking = false;
    private boolean opened = false;
    private View shutter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        preview = new SurfaceView(this);
        root.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        holder = preview.getHolder();
        holder.addCallback(this);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        top.setBackgroundColor(0x66000000);
        TextView close = Ui.button(this, "×", false);
        close.setTextSize(24);
        close.setTextColor(Color.WHITE);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        top.addView(close);
        TextView title = Ui.tv(this, "拍摄", 16, Color.WHITE, true);
        title.setPadding(Ui.dp(this, 8), 0, 0, 0);
        top.addView(title);
        root.addView(top, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56), Gravity.TOP));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 26));
        bottom.setBackgroundColor(0x66000000);
        TextView cancel = Ui.button(this, "取消", false);
        cancel.setTextColor(Color.WHITE);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        bottom.addView(cancel);
        shutter = new View(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.WHITE);
        ring.setStroke(Ui.dp(this, 4), 0xCCFFFFFF);
        shutter.setBackground(ring);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                Ui.dp(this, 68), Ui.dp(this, 68));
        slp.leftMargin = Ui.dp(this, 36);
        slp.rightMargin = Ui.dp(this, 36);
        shutter.setLayoutParams(slp);
        shutter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                takePicture();
            }
        });
        bottom.addView(shutter);
        TextView placeholder = Ui.tv(this, "   ", 14, Color.TRANSPARENT, false);
        bottom.addView(placeholder);
        root.addView(bottom, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        setContentView(root);

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 61);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != 61) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (surfaceReady) openCamera();
        } else {
            Ui.toast(this, "需要相机权限才能拍照");
            finish();
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (camera != null) {
            try {
                camera.stopPreview();
                camera.setPreviewDisplay(holder);
                camera.startPreview();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        releaseCamera();
    }

    private void openCamera() {
        if (opened) return;
        try {
            camera = Camera.open(cameraId);
            Camera.Parameters params = camera.getParameters();
            params.setPictureFormat(ImageFormat.JPEG);
            params.setJpegQuality(92);
            camera.setParameters(params);
            camera.setDisplayOrientation(displayOrientation());
            camera.setPreviewDisplay(holder);
            camera.startPreview();
            opened = true;
        } catch (Exception e) {
            Ui.toast(this, "相机打开失败：" + e.getMessage());
            releaseCamera();
        }
    }

    private int displayOrientation() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, info);
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        int degrees = 0;
        if (rotation == android.view.Surface.ROTATION_90) degrees = 90;
        else if (rotation == android.view.Surface.ROTATION_180) degrees = 180;
        else if (rotation == android.view.Surface.ROTATION_270) degrees = 270;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            return (360 - ((info.orientation + degrees) % 360)) % 360;
        }
        return (info.orientation - degrees + 360) % 360;
    }

    private int jpegRotation() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, info);
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        int degrees = 0;
        if (rotation == android.view.Surface.ROTATION_90) degrees = 90;
        else if (rotation == android.view.Surface.ROTATION_180) degrees = 180;
        else if (rotation == android.view.Surface.ROTATION_270) degrees = 270;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            return (info.orientation + degrees) % 360;
        }
        return (info.orientation - degrees + 360) % 360;
    }

    private void takePicture() {
        if (camera == null || taking) return;
        taking = true;
        try {
            Camera.Parameters params = camera.getParameters();
            params.setRotation(jpegRotation());
            camera.setParameters(params);
            camera.takePicture(null, null, new Camera.PictureCallback() {
                @Override
                public void onPictureTaken(byte[] data, Camera camera) {
                    try {
                        if (data == null || data.length == 0) throw new Exception("照片为空");
                        File dir = new File(getCacheDir(), "camera");
                        if (!dir.exists()) dir.mkdirs();
                        File file = new File(dir, "capture_" + System.currentTimeMillis() + ".jpg");
                        FileOutputStream out = new FileOutputStream(file);
                        out.write(data);
                        out.close();
                        DebugLog.i("camera", "in-app capture bytes=" + file.length());
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                setResult(Activity.RESULT_OK, new Intent().setData(Uri.fromFile(file)));
                                finish();
                            }
                        });
                    } catch (Exception e) {
                        taking = false;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Ui.toast(CameraCaptureActivity.this, "保存照片失败：" + e.getMessage());
                                try {
                                    camera.startPreview();
                                } catch (Exception ignored) {
                                }
                            }
                        });
                    }
                }
            });
        } catch (Exception e) {
            taking = false;
            Ui.toast(this, "拍照失败：" + e.getMessage());
        }
    }

    private void releaseCamera() {
        if (camera != null) {
            try {
                camera.stopPreview();
            } catch (Exception ignored) {
            }
            try {
                camera.release();
            } catch (Exception ignored) {
            }
            camera = null;
            opened = false;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        releaseCamera();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseCamera();
    }
}
