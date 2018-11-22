package com.darryncampbell.enterprise.android.iot.client;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnCanceledListener;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;

import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Set;
import java.util.concurrent.Executor;

import androidx.work.Worker;
import androidx.work.WorkerParameters;

import static com.darryncampbell.enterprise.android.iot.client.MQTTInterface.MQTT_ALGORITHM;
import static com.darryncampbell.enterprise.android.iot.client.MQTTInterface.MQTT_CLOUD_REGION;
import static com.darryncampbell.enterprise.android.iot.client.MQTTInterface.MQTT_DEVICE_ID;
import static com.darryncampbell.enterprise.android.iot.client.MQTTInterface.MQTT_PRIVATE_KEY_NAME;
import static com.darryncampbell.enterprise.android.iot.client.MQTTInterface.MQTT_PROJECT_ID;
import static com.darryncampbell.enterprise.android.iot.client.MQTTInterface.MQTT_REGISTRY_ID;
import static com.darryncampbell.enterprise.android.iot.client.MainActivity.TAG;

public class SendRealDataWorker extends Worker implements GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener{

    private Location fusedLocation = null;
    private static GoogleApiClient mGoogleApiClient = null;
    private static FusedLocationProviderClient mFusedLocationClient = null;
    private MQTTInterface mqtt = null;

    public SendRealDataWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        //  Google API client is required to retrieve device location
        if (mGoogleApiClient == null)
            buildGoogleApiClient(context);
        if (!mGoogleApiClient.isConnected())
            mGoogleApiClient.connect();
    }

    protected synchronized void buildGoogleApiClient(Context context) {
        mGoogleApiClient = new GoogleApiClient.Builder(context)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API)
                .build();
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        //  Google API client connected
        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(getApplicationContext());
    }

    @Override
    public void onConnectionSuspended(int i) {
        //  Google API client suspended
        mGoogleApiClient.connect();
        Log.w(TAG, "Connection to Google services suspended");
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        //  Google API client failed
        Log.e(TAG, "Connection to Google services failed");
    }

    @NonNull
    @Override
    public Result doWork()
    {
        //  The details of the MQTT client endpoint are included as input data to the WorkManager job
        int serverEndpoint = getInputData().getInt(MQTTInterface.MQTT_SERVER_ENDPOINT, R.id.radioGCP);
        String deviceId = getInputData().getString(MQTT_DEVICE_ID);
        String projectId = getInputData().getString(MQTT_PROJECT_ID);
        String registryId = getInputData().getString(MQTT_REGISTRY_ID);
        String privateKeyName = getInputData().getString(MQTT_PRIVATE_KEY_NAME);
        String algorithm = getInputData().getString(MQTT_ALGORITHM);
        String cloudRegion = getInputData().getString(MQTT_CLOUD_REGION);
        Log.i(TAG, "Worker alive, trying to send real data to MQTT server");
        Log.v(TAG, "Device ID: " + deviceId + ", project ID" + projectId);
        //  Device info
        String model = Build.MODEL;
        String osVersion = Build.VERSION.RELEASE;
        String patchLevel = Build.VERSION.SECURITY_PATCH;
        String releaseVersion = Build.DISPLAY;
        //  Battery info
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = getApplicationContext().registerReceiver(null, ifilter);
        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        float batteryPct;
        if (level != -1 && scale != -1)
            batteryPct = (level / (float)scale) * 100f;
        else
            batteryPct = -1;
        //  Zebra specific battery health
        int batteryHealthPct = batteryStatus.getIntExtra("health_percentage", -1);

        //  Connect to MQTT server every time we want to send rather rather than try to maintain the connection
        mqtt = new MQTTInterface();
        if (serverEndpoint == R.id.radioGCP)
            if (mqtt != null && deviceId != null &&
                    mqtt.connectToGoogleCloudIot(deviceId, projectId, cloudRegion, registryId, algorithm, privateKeyName))
            {
                updateStatusOnMainActivity("MQTT Connected");
                //  Successfully connected to cloud IOT solution, publish data
                updateFusedLocationAndSend(deviceId, model, (int)batteryPct, batteryHealthPct, osVersion, patchLevel, releaseVersion);
            }
            else if (mqtt != null && deviceId != null)
            {
                //  Connection to cloud has failed
                String connectionErrorMessage = mqtt.getLastConnectionError();
                updateStatusOnMainActivity(connectionErrorMessage);
            }
        return Result.SUCCESS;
    }

    private void updateFusedLocationAndSend(final String deviceId, final String model, final int batteryLevel,
                                            final int batteryHealth, final String osVersion,
                                            final String patchLevel, final String releaseVersion)
    {
        //  Retrieving data via the fused client is asynchronous so retrieve that
        if (mFusedLocationClient != null && ActivityCompat.checkSelfPermission(getApplicationContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            mFusedLocationClient.getLastLocation()
                    .addOnSuccessListener(new OnSuccessListener<Location>() {
                        @Override
                        public void onSuccess(Location location) {
                            Log.v(TAG, "Location manager has returned success");
                            if (location != null) {
                                fusedLocation = location;
                                String latitude = "unknown";
                                String longitude = "unknown";
                                if (fusedLocation != null) {
                                    latitude = new DecimalFormat("#.#######").format(fusedLocation.getLatitude());
                                    longitude = new DecimalFormat("#.#######").format(fusedLocation.getLongitude());
                                }
                                Log.i(TAG, "Sending: (lat)" + latitude + ", (long)" + longitude);
                                Boolean publishSuccess = mqtt.publish(deviceId, model, latitude, longitude, batteryLevel, batteryHealth, osVersion, patchLevel, releaseVersion);
                                if (!publishSuccess)
                                {
                                    String publishError = mqtt.getLastPublishError();
                                    updateStatusOnMainActivity(publishError);
                                }
                                else
                                {
                                    long dt = System.currentTimeMillis();
                                    DateFormat formatter = new SimpleDateFormat("HH:mm:ss");
                                    String formattedTime = formatter.format(dt);
                                    updateStatusOnMainActivity("Real data published to MQTT at " + formattedTime);
                                }
                            }
                        }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override
                        public void onFailure(@NonNull Exception e) {
                            Log.e(TAG, "Location manager has returned failure");
                        }
                    })
                    .addOnCanceledListener(new OnCanceledListener() {
                        @Override
                        public void onCanceled() {
                            Log.w(TAG, "Location manager has returned canceled");
                        }
                    })
                    .addOnCompleteListener(new OnCompleteListener<Location>() {
                        @Override
                        public void onComplete(@NonNull Task<Location> task) {
                            Log.v(TAG, "Location manager has returned completed");
                        }
                    });
        }
        else
        {
            updateStatusOnMainActivity("Real data not published to server.  Need location access");
        }
        //  Regardless of whether the publish succeeded or failed, disconnect from the server
        mqtt.disconnect();
    }

    private void updateStatusOnMainActivity(String message)
    {
        //  If the UI is available and listening, update it with the latest status
        Intent intent = new Intent(MainActivity.LOCAL_BROADCAST_MESSAGE);
        intent.putExtra("message", message);
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
    }

}