package org.thosp.yourlocalweather.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;

import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.AsyncHttpResponseHandler;

import org.thosp.yourlocalweather.ConnectionDetector;
import org.thosp.yourlocalweather.utils.AppPreference;
import org.thosp.yourlocalweather.utils.Constants;
import org.thosp.yourlocalweather.utils.LanguageUtil;
import org.thosp.yourlocalweather.utils.PreferenceUtil;
import org.thosp.yourlocalweather.utils.Utils;
import org.thosp.yourlocalweather.widget.ExtLocationWidgetService;
import org.json.JSONException;

import static org.thosp.yourlocalweather.MainActivity.mWeather;

import org.thosp.yourlocalweather.WeatherJSONParser;
import org.thosp.yourlocalweather.model.Weather;
import org.thosp.yourlocalweather.widget.LessWidgetService;
import org.thosp.yourlocalweather.widget.MoreWidgetService;

import java.net.MalformedURLException;

import cz.msebera.android.httpclient.Header;

import static org.thosp.yourlocalweather.utils.LogToFile.appendLog;

public class CurrentWeatherService extends Service {

    private static final String TAG = "WeatherService";

    public static final String ACTION_WEATHER_UPDATE_OK = "org.thosp.yourlocalweather.action.WEATHER_UPDATE_OK";
    public static final String ACTION_WEATHER_UPDATE_FAIL = "org.thosp.yourlocalweather.action.WEATHER_UPDATE_FAIL";
    public static final String ACTION_WEATHER_UPDATE_RESULT = "org.thosp.yourlocalweather.action.WEATHER_UPDATE_RESULT";

    private static AsyncHttpClient client = new AsyncHttpClient();

    private String updateSource;
    private boolean gettingWeatherStarted;
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    Handler timerHandler = new Handler();
    Runnable timerRunnable = new Runnable() {

        @Override
        public void run() {
                        
            if (!gettingWeatherStarted) {
                return;
            }
            
            SharedPreferences mSharedPreferences = getSharedPreferences(Constants.APP_SETTINGS_NAME,
                Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = mSharedPreferences.edit();
            String originalUpdateState = mSharedPreferences.getString(Constants.APP_SETTINGS_UPDATE_SOURCE, "-");
            appendLog(getBaseContext(), TAG, "originalUpdateState:" + originalUpdateState);
            String newUpdateState = originalUpdateState;
            if (originalUpdateState.contains("N")) {
                appendLog(getBaseContext(), TAG, "originalUpdateState contains N");
                newUpdateState = originalUpdateState.replace("N", "L");
            } else if (originalUpdateState.contains("G")) {
                newUpdateState = "L";
            }
            appendLog(getBaseContext(), TAG, "newUpdateState:" + newUpdateState);
            editor.putString(Constants.APP_SETTINGS_UPDATE_SOURCE, newUpdateState);
            editor.apply();
            sendResult(ACTION_WEATHER_UPDATE_FAIL, null);
        }
    };
    
    @Override
    public int onStartCommand(Intent intent, int flags, final int startId) {
        int ret = super.onStartCommand(intent, flags, startId);
        
        if (intent == null) {
            return ret;
        }
        
        if (intent.getExtras() != null) {
            String currentUpdateSource = intent.getExtras().getString("updateSource");
            if(!TextUtils.isEmpty(currentUpdateSource)) {
                updateSource = currentUpdateSource;
            }
        }
        
        ConnectionDetector connectionDetector = new ConnectionDetector(this);
        if (!connectionDetector.isNetworkAvailableAndConnected()) {
            return ret;
        }

        gettingWeatherStarted = true;
        timerHandler.postDelayed(timerRunnable, 20000);
        final Context context = this;
        startRefreshRotation();
        Handler mainHandler = new Handler(Looper.getMainLooper());
        Runnable myRunnable = new Runnable() {
            @Override
            public void run() {
                SharedPreferences preferences = getSharedPreferences(Constants.APP_SETTINGS_NAME, 0);
                final String latitude = preferences.getString(Constants.APP_SETTINGS_LATITUDE, "51.51");
                final String longitude = preferences.getString(Constants.APP_SETTINGS_LONGITUDE, "-0.13");
                final String locale = LanguageUtil.getLanguageName(PreferenceUtil.getLanguage(context));
                final float latInPref = Float.parseFloat(latitude.replace(",", "."));
                final float lonInPref = Float.parseFloat(longitude.replace(",", "."));

                appendLog(context, TAG, "weather get params: latitude:" + latitude + ", longitude" + longitude);
                try {
                    client.get(Utils.getWeatherForecastUrl(Constants.WEATHER_ENDPOINT,
                            latitude.replace(',', '.'),
                            longitude.replace(',', '.'),
                            "metric",
                            locale).toString(), null, new AsyncHttpResponseHandler() {

                        @Override
                        public void onStart() {
                            // called before request is started
                        }

                        @Override
                        public void onSuccess(int statusCode, Header[] headers, byte[] response) {
                            try {
                                String weatherRaw = new String(response);
                                appendLog(context, TAG, "weather got, result:" + weatherRaw);

                                Weather weather = WeatherJSONParser.getWeather(weatherRaw);
                                gettingWeatherStarted = false;
                                timerHandler.removeCallbacksAndMessages(null);

                                SharedPreferences mSharedPreferences = getSharedPreferences(Constants.APP_SETTINGS_NAME,
                                        Context.MODE_PRIVATE);
                                String updateSource = mSharedPreferences.getString(Constants.APP_SETTINGS_UPDATE_SOURCE, "-");
                                if ("-".equals(updateSource)) {
                                    updateSource = "W";
                                }
                                if ((Math.abs(latInPref - weather.coord.getLat()) < 0.01) &&
                                        (Math.abs(lonInPref - weather.coord.getLon()) < 0.01)) {
                                    AppPreference.saveWeather(context, weather, updateSource);
                                    sendResult(ACTION_WEATHER_UPDATE_OK, weather);
                                } else {
                                    appendLog(context, TAG, "Weather not saved because of difference in coord:" +
                                            (latInPref - weather.coord.getLat()) + ", " + (latInPref - weather.coord.getLat()));
                                    sendResult(ACTION_WEATHER_UPDATE_FAIL, null);
                                }
                            } catch (JSONException e) {
                                appendLog(context, TAG, "JSONException:" + e);
                                sendResult(ACTION_WEATHER_UPDATE_FAIL, null);
                            }
                        }

                        @Override
                        public void onFailure(int statusCode, Header[] headers, byte[] errorResponse, Throwable e) {
                            appendLog(context, TAG, "onFailure:" + statusCode);
                            sendResult(ACTION_WEATHER_UPDATE_FAIL, null);
                        }

                        @Override
                        public void onRetry(int retryNo) {
                            // called when request is retried
                        }
                    });
                } catch (MalformedURLException mue) {
                    appendLog(context, TAG, "MalformedURLException:" + mue);
                    sendResult(ACTION_WEATHER_UPDATE_FAIL, null);
                }
            }
        };
        mainHandler.post(myRunnable);
        return ret;
    }

    public void sendResult(String result, Weather weather) {
        stopRefreshRotation();
        if (updateSource == null) {
            return;
        }
        
        switch (updateSource) {
            case "MAIN" : sendIntentToMain(result, weather);break;
            case "LESS_WIDGET" : startService(new Intent(getBaseContext(), LessWidgetService.class));break;
            case "MORE_WIDGET" : startService(new Intent(getBaseContext(), MoreWidgetService.class));break;
            case "EXT_LOC_WIDGET" : startService(new Intent(getBaseContext(), ExtLocationWidgetService.class));break;
            case "NOTIFICATION" : startService(new Intent(getBaseContext(), NotificationService.class));break;
        }
    }
    
    private void sendIntentToMain(String result, Weather weather) {
        mWeather = weather;
        Intent intent = new Intent(ACTION_WEATHER_UPDATE_RESULT);
        if (result.equals(ACTION_WEATHER_UPDATE_OK)) {
            intent.putExtra(ACTION_WEATHER_UPDATE_RESULT, ACTION_WEATHER_UPDATE_OK);
        } else if (result.equals(ACTION_WEATHER_UPDATE_FAIL)) {
            intent.putExtra(ACTION_WEATHER_UPDATE_RESULT, ACTION_WEATHER_UPDATE_FAIL);
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void startRefreshRotation() {
        Intent sendIntent = new Intent("android.intent.action.START_ROTATING_UPDATE");
        sendIntent.setPackage("org.thosp.yourlocalweather");
        startService(sendIntent);
    }

    private void stopRefreshRotation() {
        Intent sendIntent = new Intent("android.intent.action.STOP_ROTATING_UPDATE");
        sendIntent.setPackage("org.thosp.yourlocalweather");
        startService(sendIntent);
    }
}
