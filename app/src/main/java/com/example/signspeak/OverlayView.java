package com.example.signspeak;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public class OverlayView extends View {
    private Bitmap handFrame;
    private Paint paint;

    public OverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint = new Paint();
    }

    // Method to set the cropped hand frame
    public void setHandFrame(Bitmap handFrame) {
        this.handFrame = handFrame;
        invalidate(); // Triggers a redraw of the view
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (handFrame != null) {
            // Draw the cropped frame on the canvas
            canvas.drawBitmap(handFrame, 0, 0, paint);
        }
    }
}
