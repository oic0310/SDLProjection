package com.oec.sdl.projection;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.smartdevicelink.managers.CompletionListener;
import com.smartdevicelink.managers.SdlManager;
import com.smartdevicelink.managers.SdlManagerListener;
import com.smartdevicelink.managers.file.filetypes.SdlArtwork;
import com.smartdevicelink.protocol.enums.FunctionID;
import com.smartdevicelink.proxy.RPCNotification;
import com.smartdevicelink.proxy.rpc.OnHMIStatus;
import com.smartdevicelink.proxy.rpc.VideoStreamingCapability;
import com.smartdevicelink.proxy.rpc.enums.AppHMIType;
import com.smartdevicelink.proxy.rpc.enums.FileType;
import com.smartdevicelink.proxy.rpc.enums.HMILevel;
import com.smartdevicelink.proxy.rpc.enums.SystemCapabilityType;
import com.smartdevicelink.proxy.rpc.listeners.OnRPCNotificationListener;
import com.smartdevicelink.streaming.video.SdlRemoteDisplay;
import com.smartdevicelink.streaming.video.VideoStreamingParameters;
import com.smartdevicelink.transport.BaseTransportConfig;
import com.smartdevicelink.transport.MultiplexTransportConfig;
import com.smartdevicelink.transport.TCPTransportConfig;

import java.util.Vector;

public class SdlService extends Service {

	private static final String TAG 					= "SDL Service";

	private static final String APP_NAME 				= "SDL Display";
	private static final String APP_ID 					= "8678309";

	private static final String ICON_FILENAME 			= "hello_sdl_icon.png";

	private static final int FOREGROUND_SERVICE_ID = 111;

	// TCP/IP transport config
	// The default port is 12345
	// The IP is of the machine that is running SDL Core
	private static final int TCP_PORT = 12345;
	private static final String DEV_MACHINE_IP_ADDRESS = "10.0.0.1";
	//private static final int TCP_PORT = 12345;
	//private static final String DEV_MACHINE_IP_ADDRESS = "192.168.1.105";
	//private static final int TCP_PORT = 13607;
	//private static final String DEV_MACHINE_IP_ADDRESS = "m.sdl.tools";
	// variable to create and call functions of the SyncProxy
	private SdlManager sdlManager = null;

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public void onCreate() {
		Log.d(TAG, "onCreate");
		super.onCreate();

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			enterForeground();
		}
	}

	// Helper method to let the service enter foreground mode
	@SuppressLint("NewApi")
	public void enterForeground() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			NotificationChannel channel = new NotificationChannel(APP_ID, "SdlService", NotificationManager.IMPORTANCE_DEFAULT);
			NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
			if (notificationManager != null) {
				notificationManager.createNotificationChannel(channel);
				Notification serviceNotification = new Notification.Builder(this, channel.getId())
						.setContentTitle("Connected through SDL")
						.setSmallIcon(R.drawable.ic_sdl)
						.build();
				startForeground(FOREGROUND_SERVICE_ID, serviceNotification);
			}
		}
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		startProxy();
		return START_STICKY;
	}

	@Override
	public void onDestroy() {

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			stopForeground(true);
		}

		if (sdlManager != null) {
			sdlManager.dispose();
		}

		super.onDestroy();
	}

	private void startProxy() {

		if (sdlManager == null) {
			Log.i(TAG, "Starting SDL Proxy");

			BaseTransportConfig transport = null;
			if (BuildConfig.TRANSPORT.equals("MULTI")) {
				int securityLevel;
				if (BuildConfig.SECURITY.equals("HIGH")) {
					securityLevel = MultiplexTransportConfig.FLAG_MULTI_SECURITY_HIGH;
				} else if (BuildConfig.SECURITY.equals("MED")) {
					securityLevel = MultiplexTransportConfig.FLAG_MULTI_SECURITY_MED;
				} else if (BuildConfig.SECURITY.equals("LOW")) {
					securityLevel = MultiplexTransportConfig.FLAG_MULTI_SECURITY_LOW;
				} else {
					securityLevel = MultiplexTransportConfig.FLAG_MULTI_SECURITY_OFF;
				}
				transport = new MultiplexTransportConfig(this, APP_ID, securityLevel);
			} else if (BuildConfig.TRANSPORT.equals("TCP")) {
				transport = new TCPTransportConfig(TCP_PORT, DEV_MACHINE_IP_ADDRESS, true);
			} else if (BuildConfig.TRANSPORT.equals("MULTI_HB")) {
				MultiplexTransportConfig mtc = new MultiplexTransportConfig(this, APP_ID, MultiplexTransportConfig.FLAG_MULTI_SECURITY_OFF);
				mtc.setRequiresHighBandwidth(true);
				transport = mtc;
			}

			// NAVIGATIONにしておく
			Vector<AppHMIType> appType = new Vector<>();
			appType.add(AppHMIType.NAVIGATION);


			// The manager listener helps you know when certain events that pertain to the SDL Manager happen
			// Here we will listen for ON_HMI_STATUS and ON_COMMAND notifications
			SdlManagerListener listener = new SdlManagerListener() {
				@Override
				public void onStart() {
					// HMI Status Listener
					sdlManager.addOnRPCNotificationListener(FunctionID.ON_HMI_STATUS, new OnRPCNotificationListener() {
						@Override
						public void onNotified(RPCNotification notification) {

							OnHMIStatus status = (OnHMIStatus) notification;

							//初回起動時の処理
							if (status.getHmiLevel() == HMILevel.HMI_FULL && ((OnHMIStatus) notification).getFirstRun()) {

								if (sdlManager.getVideoStreamManager() != null) {

									//VideoStreamが有効になったら、プロジェクションを開始する
									sdlManager.getVideoStreamManager().start(new CompletionListener() {
										@Override
										public void onComplete(boolean success) {
											if (success) {
												startProjectionMode();
											} else {
												Log.e(TAG, "Failed to start video streaming manager");
											}
										}
									});
								}
							}

							//HMIが終了したらプロジェクションを終了する
							if (status != null && status.getHmiLevel() == HMILevel.HMI_NONE) {
								stopProjectionMode();
							}
						}
					});
				}

				@Override
				public void onDestroy() {
					SdlService.this.stopSelf();
				}

				@Override
				public void onError(String info, Exception e) {
				}
			};

			// Create App Icon, this is set in the SdlManager builder
			SdlArtwork appIcon = new SdlArtwork(ICON_FILENAME, FileType.GRAPHIC_PNG, R.mipmap.ic_launcher, true);

			// The manager builder sets options for your session
			SdlManager.Builder builder = new SdlManager.Builder(this, APP_ID, APP_NAME, listener);
			builder.setAppTypes(appType);
			//変更
			//builder.setTransportType(transport);
			builder.setTransportType(new TCPTransportConfig(TCP_PORT, DEV_MACHINE_IP_ADDRESS, true));
			builder.setAppIcon(appIcon);
			sdlManager = builder.build();
			sdlManager.start();


		}
	}

	/**
	 * プロジェクションモードを開始する
	 */
	private void startProjectionMode(){

		if(sdlManager == null){
			return;
		}

		//画面のサイズをとる
		Object object = sdlManager.getSystemCapabilityManager().getCapability(SystemCapabilityType.VIDEO_STREAMING);
		if(object instanceof VideoStreamingCapability ){
			VideoStreamingCapability capability = (VideoStreamingCapability)object;
			Log.i(TAG, String.format("Display size Width:%d Height:%d",
					capability.getPreferredResolution().getResolutionWidth(),
					capability.getPreferredResolution().getResolutionHeight()));
		}

		//SDL DEVELOPMENT TOOLSの場合、800, 350が返ってくる
		//そのままだと縦長の画像になるため、カタログスペックのサイズを強制的に設定する
		VideoStreamingParameters parameters = new VideoStreamingParameters();
		parameters.getResolution().setResolutionWidth(800);
		parameters.getResolution().setResolutionHeight(480);

		sdlManager.getVideoStreamManager().startRemoteDisplayStream(getApplicationContext(), RemoteDisplay.class, parameters, false);
	}

	/**
	 * プロジェクションモードを停止する
	 */
	private void stopProjectionMode() {

		if(sdlManager == null){
			return;
		}

		sdlManager.getVideoStreamManager().stopStreaming();
	}

	/**
	 * プロジェクション用の画面
	 */
	public static class RemoteDisplay extends SdlRemoteDisplay {

		public RemoteDisplay(Context context, Display display) {
			super(context, display);
		}

		@Override
		protected void onCreate(Bundle savedInstanceState) {
			super.onCreate(savedInstanceState);
			setContentView(R.layout.remote_display);

			final TextView textView = (TextView)this.findViewById(R.id.textView);

			Button button01 = (Button)this.findViewById(R.id.button01);
			button01.setOnTouchListener(new View.OnTouchListener() {
				@Override
				public boolean onTouch(View view, MotionEvent motionEvent) {
					Toast.makeText(view.getContext(),"Touch event received: " + motionEvent.getX(),Toast.LENGTH_SHORT).show();
					switch (motionEvent.getAction()){
						case MotionEvent.ACTION_DOWN:
							textView.setText("Hello Android");

					}

					return true;
				}
			});

			Button button02 = (Button)this.findViewById(R.id.button02);
			button02.setOnTouchListener(new View.OnTouchListener() {
				@Override
				public boolean onTouch(View view, MotionEvent motionEvent) {
					Toast.makeText(view.getContext(),"Touch event received: " + motionEvent.getX(),Toast.LENGTH_SHORT).show();

					switch (motionEvent.getAction()){
						case MotionEvent.ACTION_DOWN:
							textView.setText("Hello SDL");

					}

					return true;
				}
			});

			final ImageView imageView = (ImageView)this.findViewById(R.id.imageView);
			Button button03 = (Button)this.findViewById(R.id.button03);
			button03.setOnTouchListener(new View.OnTouchListener() {
				@Override
				public boolean onTouch(View view, MotionEvent motionEvent) {
					Toast.makeText(view.getContext(),"Touch event received: " + motionEvent.getX(),Toast.LENGTH_SHORT).show();

					switch (motionEvent.getAction()){
						case MotionEvent.ACTION_DOWN:
							imageView.setImageResource(R.drawable.sample02);
					}

					return true;
				}
			});

		}
	}

}
