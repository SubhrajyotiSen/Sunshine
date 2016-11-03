/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;


public class WeatherWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<WeatherWatchFace.Engine> mWeakReference;

        public EngineHandler(WeatherWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            WeatherWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements MessageApi.MessageListener, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mHourPainText;
        Paint mMinuetTextPaint;
        Paint mSecondTextPaint;
        Paint mAmPmTextPaint;
        Paint mDateTextPaint;
        Paint mWeatherBitmapPaint;
        Paint mMaxTempPaint;
        Paint mMinTempPaint;
        boolean mAmbient;
        Calendar mCalendar;
        Date mDate;
        String mDateString;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        float mXOffset;
        float mYOffset;
        float lineHeight;
        float bitmapOffset;

        int wID;
        double wMax;
        double wMin;

        GoogleApiClient mGoogleApiClient;

        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(WeatherWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = WeatherWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(ContextCompat.getColor(getApplication(), R.color.background));

            mHourPainText = new Paint();
            mMinuetTextPaint = new Paint();
            mSecondTextPaint = new Paint();
            mAmPmTextPaint = new Paint();
            mWeatherBitmapPaint = new Paint();
            mMaxTempPaint = new Paint();
            mMinTempPaint = new Paint();
            mHourPainText = createTextPaint(ContextCompat.getColor(getApplicationContext(), R.color.text));
            mSecondTextPaint = createTextPaint(ContextCompat.getColor(getApplicationContext(), R.color.text));
            mMinuetTextPaint = createTextPaint(ContextCompat.getColor(getApplicationContext(), R.color.text));
            mAmPmTextPaint = createTextPaint(ContextCompat.getColor(getApplicationContext(), R.color.text));
            mDateTextPaint = createTextPaint(ContextCompat.getColor(getApplicationContext(), R.color.text));
            mMaxTempPaint = createTextPaint(ContextCompat.getColor(getApplicationContext(), R.color.text));
            mMinTempPaint = createTextPaint(ContextCompat.getColor(getApplicationContext(), R.color.text));
            mCalendar = Calendar.getInstance();

            mDate = new Date();
            mDateString = java.text.DateFormat.getDateInstance().format(mDate);

            wID = 800;
            wMax = 32;
            wMin = 21;

            mGoogleApiClient = new GoogleApiClient.Builder(getApplicationContext())
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();
            mGoogleApiClient.connect();

        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            WeatherWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            WeatherWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = WeatherWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            float ampmSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_ampm_size_round : R.dimen.digital_text_ampm_size);
            float dateSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_date_size_round : R.dimen.digital_text_date_size);
            float maxTempSize = resources.getDimension(isRound
                    ? R.dimen.max_temp_size_round : R.dimen.max_temp_size);
            float minTempSize = resources.getDimension(isRound
                    ? R.dimen.min_temp_size_round : R.dimen.min_temp_size);
            lineHeight = resources.getDimension(R.dimen.digital_line_height);
            bitmapOffset = isRound ? 45 : 35;

            mHourPainText.setTextSize(textSize);
            mMinuetTextPaint.setTextSize(textSize);
            mSecondTextPaint.setTextSize(textSize);
            mAmPmTextPaint.setTextSize(ampmSize);
            mDateTextPaint.setTextSize(dateSize);
            mMinTempPaint.setTextSize(minTempSize);
            mMaxTempPaint.setTextSize(maxTempSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mHourPainText.setAntiAlias(!inAmbientMode);
                    mMinuetTextPaint.setAntiAlias(!inAmbientMode);
                    mSecondTextPaint.setAntiAlias(!inAmbientMode);
                    mAmPmTextPaint.setAntiAlias(!inAmbientMode);
                    mDateTextPaint.setAntiAlias(!isInAmbientMode());
                    mMaxTempPaint.setAntiAlias(!isInAmbientMode());
                    mMinTempPaint.setAntiAlias(!isInAmbientMode());
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }


        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    // TODO: Add code to handle the tap gesture.
                    Toast.makeText(getApplicationContext(), R.string.message, Toast.LENGTH_SHORT)
                            .show();
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {

            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            mDate.setTime(now);

            int hour = mCalendar.get(Calendar.HOUR);
            if (hour == 0)
                hour = 12;
            String hourString = String.valueOf(hour);
            float x = mXOffset;
            if (hour < 10) {
                hourString = "0".concat(hourString);
            }
            canvas.drawText(hourString, x, mYOffset, mHourPainText);

            int minute = mCalendar.get(Calendar.MINUTE);
            String minuteText;
            if (minute < 10)
                minuteText = ":0".concat(String.valueOf(minute));
            else
                minuteText = ":".concat(String.valueOf(minute));
            x += mHourPainText.measureText(hourString);
            canvas.drawText(minuteText, x, mYOffset, mMinuetTextPaint);

            x += mMinuetTextPaint.measureText(minuteText);
            if (isInAmbientMode()) {
                if (mCalendar.get(Calendar.AM_PM) == Calendar.AM)
                    canvas.drawText(" AM", x, mYOffset, mAmPmTextPaint);
                else
                    canvas.drawText(" PM", x, mYOffset, mAmPmTextPaint);
            }
            else{
                int second = mCalendar.get(Calendar.SECOND);
                if (second < 10)
                    canvas.drawText(":0".concat(String.valueOf(second)), x, mYOffset, mSecondTextPaint);
                else
                    canvas.drawText(":".concat(String.valueOf(second)), x, mYOffset, mSecondTextPaint);
            }

            canvas.drawText(mDateString, mXOffset + mHourPainText.measureText("1"),
                    mYOffset + lineHeight, mDateTextPaint);

            if (!isInAmbientMode()) {
                int drawableID = WatchFaceUtil.getResource(wID);
                if (drawableID != -1) {
                    Bitmap icon = BitmapFactory.decodeResource(getApplicationContext().getResources(),
                            drawableID);
                    canvas.drawBitmap(Bitmap.createScaledBitmap(icon, 70, 70, false), bitmapOffset, 180, null);
                }
                canvas.drawText(String.valueOf(Math.round(wMax)).concat("°"),bitmapOffset + 80, 225, mMaxTempPaint);
                canvas.drawText(String.valueOf(Math.round(wMin)).concat("°"),bitmapOffset + 80 + mMaxTempPaint.measureText("999"), 225, mMinTempPaint);
            }



        }


        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }


        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }


        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Wearable.MessageApi.addListener(mGoogleApiClient, this);
        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

        }

        @Override
        public void onMessageReceived(MessageEvent messageEvent) {
            if (messageEvent.getPath().equals("/weather")){
                byte[] rawData = messageEvent.getData();
                // It's allowed that the message carries only some of the keys used in the config DataItem
                // and skips the ones that we don't want to change.
                DataMap dataMap = DataMap.fromByteArray(rawData);

                wID = dataMap.getInt("0");
                wMax = dataMap.getDouble("1");
                wMin = dataMap.getDouble("2");
                Log.d("WEAR",wID+"");
                Log.d("WEAR",wMax+"");
                Log.d("WEAR",wMin+"");
            }
            invalidate();
        }
    }
}
