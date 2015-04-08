/*
 * AirPlayService
 * Connect SDK
 * 
 * Copyright (c) 2014 LG Electronics.
 * Created by Hyun Kook Khang on 18 Apr 2014
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.connectsdk.service;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.connectsdk.core.ImageInfo;
import com.connectsdk.core.MediaInfo;
import com.connectsdk.core.Util;
import com.connectsdk.discovery.DiscoveryFilter;
import com.connectsdk.etc.helper.DeviceServiceReachability;
import com.connectsdk.etc.helper.HttpMessage;
import com.connectsdk.service.airplay.PListBuilder;
import com.connectsdk.service.airplay.PListParser;
import com.connectsdk.service.capability.CapabilityMethods;
import com.connectsdk.service.capability.MediaControl;
import com.connectsdk.service.capability.MediaPlayer;
import com.connectsdk.service.capability.listeners.ResponseListener;
import com.connectsdk.service.command.ServiceCommand;
import com.connectsdk.service.command.ServiceCommandError;
import com.connectsdk.service.command.ServiceSubscription;
import com.connectsdk.service.config.ServiceConfig;
import com.connectsdk.service.config.ServiceDescription;
import com.connectsdk.service.sessions.LaunchSession;
import com.connectsdk.service.sessions.LaunchSession.LaunchSessionType;

import org.apache.http.protocol.HTTP;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

public class AirPlayService extends DeviceService implements MediaPlayer, MediaControl {
    public static final String X_APPLE_SESSION_ID = "X-Apple-Session-ID";
    public static final String ID = "AirPlay";
    private static final long KEEP_ALIVE_PERIOD = 15000;

    private final static String CHARSET = "UTF-8";

    private String mSessionId;

    private Timer timer;

    @Override
    public CapabilityPriorityLevel getPriorityLevel(Class<? extends CapabilityMethods> clazz) {
        if (clazz.equals(MediaPlayer.class)) {
            return getMediaPlayerCapabilityLevel();
        }
        else if (clazz.equals(MediaControl.class)) {
            return getMediaControlCapabilityLevel();
        }
        return CapabilityPriorityLevel.NOT_SUPPORTED;
    }

    interface PlaybackPositionListener {
        void onGetPlaybackPositionSuccess(long duration, long position);
        void onGetPlaybackPositionFailed(ServiceCommandError error);
    }

    public AirPlayService(ServiceDescription serviceDescription,
            ServiceConfig serviceConfig) throws IOException {
        super(serviceDescription, serviceConfig);
    }

    public static DiscoveryFilter discoveryFilter() {
        return new DiscoveryFilter(ID, "_airplay._tcp.local.");
    }

    @Override
    public MediaControl getMediaControl() {
        return this;
    }

    @Override
    public CapabilityPriorityLevel getMediaControlCapabilityLevel() {
        return CapabilityPriorityLevel.HIGH;
    }

    @Override
    public void play(ResponseListener<Object> listener) {
        Map <String,String> params = new HashMap<String,String>();
        params.put("value", "1.000000");

        String uri = getRequestURL("rate", params);

        ServiceCommand<ResponseListener<Object>> request = new ServiceCommand<ResponseListener<Object>>(this, uri, null, listener);
        request.send();
    }

    @Override
    public void pause(ResponseListener<Object> listener) {
        Map <String,String> params = new HashMap<String,String>();
        params.put("value", "0.000000");

        String uri = getRequestURL("rate", params);

        ServiceCommand<ResponseListener<Object>> request = new ServiceCommand<ResponseListener<Object>>(this, uri, null, listener);
        request.send();
    }

    @Override
    public void stop(ResponseListener<Object> listener) {
        String uri = getRequestURL("stop");

        ServiceCommand<ResponseListener<Object>> request = new ServiceCommand<ResponseListener<Object>>(this, uri, null, listener);
        // TODO This is temp fix for issue https://github.com/ConnectSDK/Connect-SDK-Android/issues/66
        request.send();
        request.send();
//        persistentHttpClient.disconnect();
        stopTimer();
    }

    @Override
    public void rewind(ResponseListener<Object> listener) {
        Map <String,String> params = new HashMap<String,String>();
        params.put("value", "-2.000000");

        String uri = getRequestURL("rate", params);

        ServiceCommand<ResponseListener<Object>> request = new ServiceCommand<ResponseListener<Object>>(this, uri, null, listener);
        request.send();
    }

    @Override
    public void fastForward(ResponseListener<Object> listener) {
        Map <String,String> params = new HashMap<String,String>();
        params.put("value", "2.000000");

        String uri = getRequestURL("rate", params);

        ServiceCommand<ResponseListener<Object>> request = new ServiceCommand<ResponseListener<Object>>(this, uri, null, listener);
        request.send();
    }

    @Override
    public void previous(ResponseListener<Object> listener) {
        Util.postError(listener, ServiceCommandError.notSupported());
    }

    @Override
    public void next(ResponseListener<Object> listener) {
        Util.postError(listener, ServiceCommandError.notSupported());
    }

    @Override
    public void seek(long position, ResponseListener<Object> listener) {
        float pos = ((float) position / 1000); 

        Map <String,String> params = new HashMap<String,String>();
        params.put("position", String.valueOf(pos));

        String uri = getRequestURL("scrub", params);

        ServiceCommand<ResponseListener<Object>> request = new ServiceCommand<ResponseListener<Object>>(this, uri, null, listener);
        request.send();
    }

    @Override
    public void getPosition(final PositionListener listener) {
        getPlaybackPosition(new PlaybackPositionListener() {

            @Override
            public void onGetPlaybackPositionSuccess(long duration, long position) {
                Util.postSuccess(listener, position);
            }

            @Override
            public void onGetPlaybackPositionFailed(ServiceCommandError error) {
                Util.postError(listener, new ServiceCommandError(0, "Unable to get position", null));
            }
        });
    }

    @Override
    public void getPlayState(final PlayStateListener listener) {
        getPlaybackInfo(new ResponseListener<Object>() {

            @Override
            public void onSuccess(Object object) {
                PlayStateStatus playState = PlayStateStatus.Unknown;
                try {
                    JSONObject response = new PListParser().parse(object.toString());
                    if (!response.has("rate")) {
                        playState = PlayStateStatus.Finished;
                    } else {
                        int rate = response.getInt("rate");
                        if (rate == 0) {
                            playState = PlayStateStatus.Paused;
                        } else if (rate == 1) {
                            playState = PlayStateStatus.Playing;
                        }
                    }
                    Util.postSuccess(listener, playState);
                } catch (Exception e) {
                    Util.postError(listener, new ServiceCommandError(500, e.getMessage(), null));
                }
            }

            @Override
            public void onError(ServiceCommandError error) {
                Util.postError(listener, error);
            }
        });
    }

    @Override
    public void getDuration(final DurationListener listener) {
        getPlaybackPosition(new PlaybackPositionListener() {

            @Override
            public void onGetPlaybackPositionSuccess(long duration, long position) {
                Util.postSuccess(listener, duration);
            }

            @Override
            public void onGetPlaybackPositionFailed(ServiceCommandError error) {
                Util.postError(listener, new ServiceCommandError(0, "Unable to get duration", null));
            }
        });
    }

    private void getPlaybackPosition(final PlaybackPositionListener listener) {
        ResponseListener<Object> responseListener = new ResponseListener<Object>() {

            @Override
            public void onSuccess(Object response) {
                String strResponse = (String) response;

                long duration = 0;
                long position = 0;

                StringTokenizer st = new StringTokenizer(strResponse);
                while (st.hasMoreTokens()) {
                    String str = st.nextToken();
                    String value;
                    if (str.contains("duration")) {
                        value = st.nextToken();
                        float f = Float.valueOf(value);
                        duration = (long) f * 1000;
                    }
                    else if (str.contains("position")) {
                        value = st.nextToken();
                        float f = Float.valueOf(value);
                        position = (long) f * 1000;
                    }
                }

                if (listener != null) {
                    listener.onGetPlaybackPositionSuccess(duration, position);
                }
            }

            @Override
            public void onError(ServiceCommandError error) {
                if (listener != null)
                    listener.onGetPlaybackPositionFailed(error);
            }
        };

        String uri = getRequestURL("scrub");

        ServiceCommand<ResponseListener<Object>> request = new ServiceCommand<ResponseListener<Object>>(this, uri, null, responseListener);
        request.setHttpMethod(ServiceCommand.TYPE_GET);
        request.send();
    }

    private void getPlaybackInfo(ResponseListener<Object> listener) {
        String uri = getRequestURL("playback-info");

        ServiceCommand<ResponseListener<Object>> request = new ServiceCommand<ResponseListener<Object>>(this, uri, null, listener);
        request.setHttpMethod(ServiceCommand.TYPE_GET);
        request.send();
    }

    @Override
    public ServiceSubscription<PlayStateListener> subscribePlayState(
            PlayStateListener listener) {
        Util.postError(listener, ServiceCommandError.notSupported());
        return null;
    }

    @Override
    public MediaPlayer getMediaPlayer() {
        return this;
    }

    @Override
    public CapabilityPriorityLevel getMediaPlayerCapabilityLevel() {
        return CapabilityPriorityLevel.HIGH;
    }

    @Override
    public void getMediaInfo(MediaInfoListener listener) {
        Util.postError(listener, ServiceCommandError.notSupported());
    }

    @Override
    public ServiceSubscription<MediaInfoListener> subscribeMediaInfo(
            MediaInfoListener listener) {
        listener.onError(ServiceCommandError.notSupported());
        return null;
    }

    @Override
    public void displayImage(final String url, String mimeType, String title,
            String description, String iconSrc, final LaunchListener listener) {
        Util.runInBackground(new Runnable() {

            @Override
            public void run() {
                ResponseListener<Object> responseListener = new ResponseListener<Object>() {

                    @Override
                    public void onSuccess(Object response) {
                        LaunchSession launchSession = new LaunchSession();
                        launchSession.setService(AirPlayService.this);
                        launchSession.setSessionType(LaunchSessionType.Media);

                        Util.postSuccess(listener, new MediaLaunchObject(launchSession, AirPlayService.this));
                    }

                    @Override
                    public void onError(ServiceCommandError error) {
                        Util.postError(listener, error);
                    }
                };

                String uri = getRequestURL("photo");
                byte[] payload = null;

                try {
                    URL imagePath = new URL(url);
                    HttpURLConnection connection = (HttpURLConnection) imagePath.openConnection();
                    connection.setInstanceFollowRedirects(true);
                    connection.setDoInput(true);
                    connection.connect();

                    int responseCode = connection.getResponseCode();
                    boolean redirect = (responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                            || responseCode == HttpURLConnection.HTTP_MOVED_PERM
                            || responseCode == HttpURLConnection.HTTP_SEE_OTHER);

                    if(redirect) {
                        String newPath = connection.getHeaderField("Location");
                        URL newImagePath = new URL(newPath);
                        connection = (HttpURLConnection) newImagePath.openConnection();
                        connection.setInstanceFollowRedirects(true);
                        connection.setDoInput(true);
                        connection.connect();
                    }

                    InputStream input = connection.getInputStream();
                    Bitmap myBitmap = BitmapFactory.decodeStream(input);

                    ByteArrayOutputStream stream = new ByteArrayOutputStream();
                    myBitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream);
                    payload = stream.toByteArray();
                } catch (MalformedURLException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (Exception e) {
                    e.printStackTrace();
                }

                ServiceCommand<ResponseListener<Object>> request = new ServiceCommand<ResponseListener<Object>>(AirPlayService.this, uri, payload, responseListener);
                request.setHttpMethod(ServiceCommand.TYPE_PUT);
                request.send();
            }
        });
    }

    @Override
    public void displayImage(MediaInfo mediaInfo, LaunchListener listener) {
        String mediaUrl = null;
        String mimeType = null;
        String title = null;
        String desc = null;
        String iconSrc = null;

        if (mediaInfo != null) {
            mediaUrl = mediaInfo.getUrl();
            mimeType = mediaInfo.getMimeType();
            title = mediaInfo.getTitle();
            desc = mediaInfo.getDescription();

            if (mediaInfo.getImages() != null && mediaInfo.getImages().size() > 0) {
                ImageInfo imageInfo = mediaInfo.getImages().get(0);
                iconSrc = imageInfo.getUrl();
            }
        }

        displayImage(mediaUrl, mimeType, title, desc, iconSrc, listener);
    }

    public void playVideo(final String url, String mimeType, String title,
            String description, String iconSrc, boolean shouldLoop,
            final LaunchListener listener) {

        ResponseListener<Object> responseListener = new ResponseListener<Object>() {

            @Override
            public void onSuccess(Object response) {
                LaunchSession launchSession = new LaunchSession();
                launchSession.setService(AirPlayService.this);
                launchSession.setSessionType(LaunchSessionType.Media);

                Util.postSuccess(listener, new MediaLaunchObject(launchSession, AirPlayService.this));
                startTimer();
            }

            @Override
            public void onError(ServiceCommandError error) {
                Util.postError(listener, error);
            }
        };

        String uri = getRequestURL("play");
        String payload = null;

        PListBuilder builder = new PListBuilder();
        builder.putString("Content-Location", url);
        builder.putReal("Start-Position", 0);

        payload = builder.toString();

        ServiceCommand<ResponseListener<Object>> request = new ServiceCommand<ResponseListener<Object>>(this, uri, payload, responseListener);
        request.send();
    }

    @Override
    public void playMedia(String url, String mimeType, String title,
            String description, String iconSrc, boolean shouldLoop,
            LaunchListener listener) {

        if (mimeType.contains("image")) {
            displayImage(url, mimeType, title, description, iconSrc, listener);
        }
        else {
            playVideo(url, mimeType, title, description, iconSrc, shouldLoop, listener);
        }
    }

    @Override
    public void playMedia(MediaInfo mediaInfo, boolean shouldLoop, LaunchListener listener) {
        String mediaUrl = null;
        String mimeType = null;
        String title = null;
        String desc = null;
        String iconSrc = null;

        if (mediaInfo != null) {
            mediaUrl = mediaInfo.getUrl();
            mimeType = mediaInfo.getMimeType();
            title = mediaInfo.getTitle();
            desc = mediaInfo.getDescription();

            if (mediaInfo.getImages() != null && mediaInfo.getImages().size() > 0) {
                ImageInfo imageInfo = mediaInfo.getImages().get(0);
                iconSrc = imageInfo.getUrl();
            }
        }

        playMedia(mediaUrl, mimeType, title, desc, iconSrc, shouldLoop, listener);
    }

    @Override
    public void closeMedia(LaunchSession launchSession,
            ResponseListener<Object> listener) {
        stop(listener);
    }

    @Override
    public void sendCommand(final ServiceCommand<?> serviceCommand) {
        Util.runInBackground(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection urlConnection = null;

                try {
                    URL url = new URL(serviceCommand.getTarget());
                    urlConnection = (HttpURLConnection) url.openConnection();

                    urlConnection.setRequestMethod(serviceCommand.getHttpMethod());
                    urlConnection.setDoInput(true);

                    urlConnection.setRequestProperty(HTTP.USER_AGENT, "ConnectSDK MediaControl/1.0");
                    urlConnection.setRequestProperty(X_APPLE_SESSION_ID, mSessionId);

                    if (serviceCommand.getHttpMethod().equalsIgnoreCase(ServiceCommand.TYPE_POST)) {
                        String payload = (String) serviceCommand.getPayload();
                        if (payload != null)
                            urlConnection.setRequestProperty(HttpMessage.CONTENT_TYPE_HEADER, HttpMessage.CONTENT_TYPE_APPLICATION_PLIST);
                        urlConnection.setDoOutput(true);
                        urlConnection.connect();

                        OutputStream outputStream = urlConnection.getOutputStream();

                        if (payload != null)
                            outputStream.write(payload.getBytes(CHARSET));
                        outputStream.flush();
                        outputStream.close();
                    } else if (serviceCommand.getHttpMethod().equalsIgnoreCase(ServiceCommand.TYPE_PUT)) {
                        byte[] payload = (byte[]) serviceCommand.getPayload();

                        urlConnection.setDoInput(true);
                        urlConnection.connect();

                        OutputStream outputStream = urlConnection.getOutputStream();

                        if (payload != null)
                            outputStream.write(payload);
                        outputStream.flush();
                        outputStream.close();
                    } else if (serviceCommand.getHttpMethod().equalsIgnoreCase(ServiceCommand.TYPE_GET)) {
                        urlConnection.connect();
                    }

                    int code = urlConnection.getResponseCode();
                    if (code == HttpURLConnection.HTTP_OK) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));

                        String line;
                        StringBuffer buffer = new StringBuffer();
                        while ((line = reader.readLine()) != null) {
                            buffer.append(line);
                            buffer.append("\r\n");
                        }
                        reader.close();

                        String response = buffer.toString();

                        Util.postSuccess(serviceCommand.getResponseListener(), response);
                    }
                    else {
                        Util.postError(serviceCommand.getResponseListener(), new ServiceCommandError(urlConnection.getResponseCode(), urlConnection.getResponseMessage(), null));
                    }
                } catch (ProtocolException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    if (urlConnection != null)
                        urlConnection.disconnect();
                }
            }
        });
    }

    @Override
    protected void updateCapabilities() {
        List<String> capabilities = new ArrayList<String>();

        capabilities.add(Display_Image);
        capabilities.add(Play_Video);
        capabilities.add(Play_Audio);
        capabilities.add(Close);

        capabilities.add(Play);
        capabilities.add(Pause);
        capabilities.add(Stop);
        capabilities.add(Position);
        capabilities.add(Duration);
        capabilities.add(PlayState);
        capabilities.add(Seek);
        capabilities.add(Rewind);
        capabilities.add(FastForward);

        setCapabilities(capabilities);
    }

    private String getRequestURL(String command) {
        return getRequestURL(command, null);
    }

    private String getRequestURL(String command, Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        sb.append("http://").append(serviceDescription.getIpAddress()).append(":").append(serviceDescription.getPort());
        sb.append("/").append(command);

        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                String param = String.format("?%s=%s", entry.getKey(), entry.getValue());
                sb.append(param); 
            }
        }

        return sb.toString();
    }

    @Override
    public boolean isConnectable() {
        return true;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void connect() {
        mSessionId = UUID.randomUUID().toString();
        connected = true;
        reportConnected(true);
    }

    @Override
    public void disconnect() {
        stopTimer();
        connected=false;

        if (mServiceReachability != null)
            mServiceReachability.stop();

        Util.runOnUI(new Runnable() {
            @Override
            public void run() {
                if (listener != null)
                    listener.onDisconnect(AirPlayService.this, null);
            }
        });
    }

    @Override
    public void onLoseReachability(DeviceServiceReachability reachability) {
        if (connected) {
            disconnect();
        } else {
            mServiceReachability.stop();
        }
    }

    /**
     * We send periodically a command to keep connection alive and for avoiding 
     * stopping media session
     * 
     * Fix for https://github.com/ConnectSDK/Connect-SDK-Cordova-Plugin/issues/5
     */
    private void startTimer() {
        stopTimer();
        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {

            @Override
            public void run() {
                Log.d("Timer", "Timer");
                getPlaybackPosition(new PlaybackPositionListener() {

                    @Override
                    public void onGetPlaybackPositionSuccess(long duration, long position) {
                        if (position >= duration) {
                            stopTimer();
                        }
                    }

                    @Override
                    public void onGetPlaybackPositionFailed(ServiceCommandError error) {
                    }
                });
            }
        }, KEEP_ALIVE_PERIOD, KEEP_ALIVE_PERIOD);
    }

    private void stopTimer() {
        if (timer != null) {
            timer.cancel();
        }
        timer = null;
    }


}
