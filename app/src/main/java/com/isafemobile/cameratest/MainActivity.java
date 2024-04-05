package com.isafemobile.cameratest;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {

    private Camera mCamera;
    private SurfaceView mSurfaceView;
    private SurfaceHolder mSurfaceHolder;
    private MediaRecorder mMediaRecorder;
    private boolean isRecording = false;

    private ActivityResultLauncher<Intent> launcher; // Initialise this object in Activity.onCreate()
    private Uri baseDocumentTreeUri;
    String filePath;

    private static final String TAG = "IsafeCameratest";
    private String timeStamp;

    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 201;
    private static final int REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION = 202;

    private static final List<String> CAMERA_CANDIDATES = new ArrayList<>();
    static {
        CAMERA_CANDIDATES.add("net.sourceforge.opencamera");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mSurfaceView = findViewById(R.id.surfaceView);
        mSurfaceHolder = mSurfaceView.getHolder();
        mSurfaceHolder.addCallback(this);

        Button captureButton = findViewById(R.id.captureButton);
        SharedPreferences preferences = getSharedPreferences("com.isafemobile.cameratest", Context.MODE_PRIVATE);
        String filestorageuri = preferences.getString("filestorageuri", null);
        baseDocumentTreeUri = filestorageuri != null ? Uri.parse(filestorageuri) : null;
        Log.d(TAG, "onCreate, baseDocumentTreeUri is " + baseDocumentTreeUri);
        captureButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isRecording) {
                    stopRecording();
                } else {
                    if (baseDocumentTreeUri == null) {
                        launchBaseDirectoryPicker();
                    } else {
                        startRecording();
                    }
                }
            }
        });

        Button captureImageButton = findViewById(R.id.captureImageButton);
        captureImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (baseDocumentTreeUri == null) {
                    launchBaseDirectoryPicker();
                } else {
                    captureImage();
                }
            }
        });

        requestPermissions();

        // You can do the assignment inside onAttach or onCreate, i.e, before the activity is displayed
        launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    baseDocumentTreeUri = Objects.requireNonNull(result.getData()).getData();
                    final int takeFlags = (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    // take persistable Uri Permission for future use
                    getContentResolver().takePersistableUriPermission(result.getData().getData(), takeFlags);
                    preferences.edit().putString("filestorageuri", result.getData().getData().toString()).apply();
                    Log.d(TAG, "ActivityResult, baseDocumentTreeUri is " + baseDocumentTreeUri.getPath());
                    startRecording();
                } else {
                    Log.e("FileUtility", "Some Error Occurred : " + result);
                }
            }
        );
    }

    public void launchBaseDirectoryPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        launcher.launch(intent);
    }

    private void requestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, REQUEST_CAMERA_PERMISSION);
        }
    }

    Uri getImageUri() {
        // Retrieve the DocumentFile instance for the baseDocumentTreeUri
        DocumentFile baseDocumentTree = DocumentFile.fromTreeUri(this, baseDocumentTreeUri);

        // Create a directory named "Images" if it doesn't exist
        DocumentFile imagesDir = baseDocumentTree.createDirectory("Images");

        // Create the image file
        String fileName = "IMG_" + timeStamp + ".jpg";
        DocumentFile imageDocumentFile = imagesDir.createFile("image/jpeg", fileName);

        // Get the content URI for the created image file
        return imageDocumentFile.getUri();
    }

    private Intent enhanceCameraIntent(Context context, Intent baseIntent) {
        PackageManager pm = context.getPackageManager();
        List<Intent> cameraIntents = new ArrayList<>();

        for (String candidate : CAMERA_CANDIDATES) {
            Log.d(TAG, "candidate " + candidate + " installed? " + isPackageInstalled(candidate, pm));
            Intent intent = new Intent(baseIntent).setPackage(candidate);
            if (!pm.queryIntentActivities(intent, 0).isEmpty()) {
                cameraIntents.add(intent);
            }
        }

        Intent chooserIntent = Intent.createChooser(baseIntent, "select Camera");
        chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, cameraIntents.toArray(new Intent[0]));
        return chooserIntent;
    }

    private boolean isPackageInstalled(String packageName, PackageManager pm) {
        try {
            pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void captureImage() {
        timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, getImageUri());
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        Intent cameraIntent = enhanceCameraIntent(this, intent);
        this.startActivityForResult(cameraIntent, 666);

/*
        if (mCamera != null) {
            mCamera.takePicture(null, null, pictureCallback);
        }*/
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        Log.d(TAG, "onActivityResult, requestCode: " + requestCode + " resultCode: " + resultCode + " intent: " + intent);
        super.onActivityResult(requestCode, resultCode, intent);
    }

    Camera.PictureCallback pictureCallback = new Camera.PictureCallback() {
        @Override
        public void onPictureTaken(byte[] data, Camera camera) {
            if (data != null) {
                saveImage(data);
            }
            // Restart the preview after capturing the image
            mCamera.startPreview();
        }
    };

    private void saveImage(byte[] data) {
        // Get the content URI for the created image file
        Uri imageUri = getImageUri();

        try {
            // Write the image data to the output stream
            OutputStream outputStream = getContentResolver().openOutputStream(imageUri);
            outputStream.write(data);
            outputStream.close();
            Toast.makeText(MainActivity.this, "Image saved successfully", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(MainActivity.this, "Failed to save image", Toast.LENGTH_SHORT).show();
        }
    }

    private void startRecording() {
        Log.d(TAG, "startRecording");
        if (mCamera == null) {
            return;
        }

        mMediaRecorder = new MediaRecorder();
        mCamera.unlock();
        mMediaRecorder.setCamera(mCamera);

        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);

        mMediaRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH));
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());

        // Retrieve the DocumentFile instance for the baseDocumentTreeUri
        DocumentFile baseDocumentTree = DocumentFile.fromTreeUri(this, baseDocumentTreeUri);

        // Create a directory named "Videos" if it doesn't exist
        DocumentFile videosDir = baseDocumentTree.createDirectory("Videos");
        // Create the video file
        String fileName = "VID_" + timeStamp + ".mp4";
        DocumentFile videoDocumentFile = videosDir.createFile("video/mp4", fileName);
        // Get the content URI for the created video file
        Uri videoUri = videoDocumentFile.getUri();

        // Get the file descriptor for the video file
        ParcelFileDescriptor pfd;
        try {
            pfd = getContentResolver().openFileDescriptor(videoUri, "rw");
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return;
        }

        mMediaRecorder.setOutputFile(pfd.getFileDescriptor());
        mMediaRecorder.setPreviewDisplay(mSurfaceHolder.getSurface());

        try {
            mMediaRecorder.prepare();
            mMediaRecorder.start();
            isRecording = true;
        } catch (IOException e) {
            e.printStackTrace();
        }
        updateButtonState();
    }

    private void stopRecording() {
        if (isRecording && mMediaRecorder != null) {
            mMediaRecorder.stop();
            mMediaRecorder.reset();
            mMediaRecorder.release();
            mMediaRecorder = null;
            mCamera.lock();
            isRecording = false;
            updateButtonState();
        }
    }

    private void updateButtonState() {
        Button captureButton = findViewById(R.id.captureButton);
        if (isRecording) {
            captureButton.setText("Stop");
        } else {
            captureButton.setText("Capture");
        }
    }

    private void releaseMediaRecorder() {
        if (mMediaRecorder != null) {
            mMediaRecorder.reset();
            mMediaRecorder.release();
            mMediaRecorder = null;
            mCamera.lock();
        }
    }

    private void releaseCamera() {
        if (mCamera != null) {
            mCamera.release();
            mCamera = null;
        }
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        try {
            mCamera = Camera.open();
            mCamera.setPreviewDisplay(mSurfaceHolder);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        if (mSurfaceHolder.getSurface() == null) {
            return;
        }
        try {
            mCamera.stopPreview();
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            mCamera.setPreviewDisplay(mSurfaceHolder);
            mCamera.startPreview();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        releaseMediaRecorder();
        releaseCamera();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case REQUEST_CAMERA_PERMISSION:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Camera permission granted
                    requestAudioAndStoragePermissions();
                } else {
                    // Camera permission denied
                    Toast.makeText(this, "Camera permission is required to use this feature", Toast.LENGTH_SHORT).show();
                    finish();
                }
                break;
            case REQUEST_RECORD_AUDIO_PERMISSION:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Record audio permission granted
                    requestStoragePermission();
                } else {
                    // Record audio permission denied
                    Toast.makeText(this, "Record audio permission is required to use this feature", Toast.LENGTH_SHORT).show();
                    finish();
                }
                break;
            case REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Write external storage permission granted
                } else {
                    // Write external storage permission denied
                    Toast.makeText(this, "Write external storage permission is required to use this feature", Toast.LENGTH_SHORT).show();
                    finish();
                }
                break;
        }
    }

    private void requestAudioAndStoragePermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, REQUEST_RECORD_AUDIO_PERMISSION);
        }
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION);
            }
        }
    }
}
