package com.example.signspeak;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.formats.proto.LandmarkProto;
import com.google.mediapipe.solutions.hands.Hands;
import com.google.mediapipe.solutions.hands.HandsOptions;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends AppCompatActivity {
    PreviewView previewView;
    TextView textView;
    Hands hands;
    OverlayView overlayView;
    Interpreter tflite;
    private static final String[] CLASS_LABELS = {
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P",
            "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "", "", ""
    };
    float[][] output = new float[1][29];
    private List<String> predictions = new ArrayList<>();
    private static final int FRAME_COUNT = 5;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        textView = findViewById(R.id.modelOutput);
        textView.bringToFront();

        hands = new Hands(this, HandsOptions.builder()
                .setMaxNumHands(1)
                .setRunOnGpu(true)
                .build());

        try {
            tflite = new Interpreter(FileUtil.loadMappedFile(this, "1.tflite"));
        } catch (Exception e) {
            Log.e("Interpreter", e.toString());
        }
        startCamera();
    }
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindPreview(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }
    private void bindPreview(ProcessCameraProvider cameraProvider) {
        Preview preview = new Preview.Builder().build();
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();


        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), new ImageAnalysis.Analyzer() {
            @Override
            public void analyze(@NonNull ImageProxy image) {
                analyzeImage(image);
                image.close();
            }
        });

        preview.setSurfaceProvider(previewView.getSurfaceProvider());
        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
    }
    private void analyzeImage(ImageProxy image) {
        Bitmap bm = image.toBitmap();
        Bitmap bitmap = RotateBitmap(bm, 90);
        long timestamp = image.getImageInfo().getTimestamp();
        try {
            hands.send(bitmap, timestamp);
        } catch (Exception e) {
            Log.e("HandsError", "Error while sending bitmap to hands", e);
        }
        hands.setResultListener(result -> {
            if (result.multiHandLandmarks().isEmpty()) {
                return;
            }

            // Get the first detected hand landmarks.
            List<LandmarkProto.NormalizedLandmark> landmarks = result.multiHandLandmarks().get(0).getLandmarkList();

            // Calculate the center of the hand by averaging all landmarks' X and Y coordinates
            float sumX = 0;
            float sumY = 0;
            for (LandmarkProto.NormalizedLandmark landmark : landmarks) {
                sumX += landmark.getX();
                sumY += landmark.getY();
            }
            float centerX = sumX / landmarks.size();
            float centerY = sumY / landmarks.size();

            // Convert the normalized center coordinates to bitmap pixel coordinates
            int pixelCenterX = (int) (centerX * bitmap.getWidth());
            int pixelCenterY = (int) (centerY * bitmap.getHeight());

            // Create a mutable bitmap to draw on
            Bitmap mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true);

            // Calculate the square size and its position
            int squareSize = 300; // The size of the square to crop (can be adjusted)
            int startX = Math.max(pixelCenterX - squareSize / 2, 0);
            int startY = Math.max(pixelCenterY - squareSize / 2, 0);

            // Ensure the square fits within the image bounds
            int width = Math.min(squareSize, bitmap.getWidth() - startX);
            int height = Math.min(squareSize, bitmap.getHeight() - startY);

            // Crop a square frame from the bitmap centered on the hand
            Bitmap handFrame = Bitmap.createBitmap(mutableBitmap, startX, startY, width, height);
            runModel(handFrame);


        });
    }
    private void runModel(Bitmap handFrame) {
        // Prepare input
        float[][][][] input = bitmapToInputArray(handFrame);

        // Run inference
        tflite.run(input, output);

        // Use AtomicInteger for final-like behavior
        AtomicInteger predictedClassIndex = new AtomicInteger(-1);
        float[] maxConfidence = { -1.0f };

        // Find the class with the highest probability
        for (int i = 0; i < output[0].length; i++) {
            if (output[0][i] > maxConfidence[0]) {
                maxConfidence[0] = output[0][i];
                predictedClassIndex.set(i);
            }
        }

        // Get the predicted class label
        String predictedLabel = CLASS_LABELS[predictedClassIndex.get()];
        predictions.add(predictedLabel);
        if (predictions.size() == FRAME_COUNT) {
            String mostFrequentPrediction = getMostFrequentPrediction(predictions);

            runOnUiThread(() -> {
                if (!mostFrequentPrediction.equals("Nothing")) {
                    // Append the predicted label
                    textView.append(mostFrequentPrediction.equals("Space") ? " " : mostFrequentPrediction);
                }
            });

            // Clear the predictions list for the next 5 frames
            predictions.clear();
        }

    }
    private String getMostFrequentPrediction(List<String> predictions) {
        String mostFrequesntPrediction = "";

        int count = 0;
        int maxCount = 0;

        for(String prediction : predictions){
            count = 1;
            for (String innerPrediction : predictions){
                if(prediction.equals(innerPrediction)) count++;
                if(count>maxCount){
                    maxCount = count;
                    mostFrequesntPrediction = prediction;
                }
            }

        }

        return mostFrequesntPrediction;
    }


    private float[][][][] bitmapToInputArray(Bitmap bitmap) {
        // Tensor shape is assumed to be [1, 224, 224, 3] (batch size 1, 224x224 image, 3 color channels)
        int inputSize = 224; // Adjust to the size your model expects
        float[][][][] input = new float[1][inputSize][inputSize][3];

        Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true);

        for (int y = 0; y < inputSize; y++) {
            for (int x = 0; x < inputSize; x++) {
                int pixel = scaledBitmap.getPixel(x, y);

                // Extract RGB components from the pixel and normalize them (assumes 0-255 pixel range)
                input[0][y][x][0] = ((pixel >> 16) & 0xFF) / 255.0f; // Red
                input[0][y][x][1] = ((pixel >> 8) & 0xFF) / 255.0f;  // Green
                input[0][y][x][2] = (pixel & 0xFF) / 255.0f;         // Blue
            }
        }
        return input;
    }


    public static Bitmap RotateBitmap(Bitmap source, float angle) {
        Matrix matrix = new Matrix();
        matrix.postRotate(angle);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }


}