package com.example.android.sunshine.app;

import android.util.Log;

import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

public class WeatherListener extends WearableListenerService{

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        super.onMessageReceived(messageEvent);

        if (messageEvent.getPath().equals("/weather")){
            byte[] rawData = messageEvent.getData();
            // It's allowed that the message carries only some of the keys used in the config DataItem
            // and skips the ones that we don't want to change.
            DataMap dataMap = DataMap.fromByteArray(rawData);

            int id = dataMap.getInt("0");
            double max = dataMap.getDouble("1");
            double min = dataMap.getDouble("2");
            Log.d("WEAR",id+"");
            Log.d("WEAR",max+"");
            Log.d("WEAR",min+"");
        }
    }

}
