package xo.freefall;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.util.Log;
import android.view.View;

import com.mbientlab.metawear.AsyncOperation;
import com.mbientlab.metawear.Message;
import com.mbientlab.metawear.MetaWearBleService;
import com.mbientlab.metawear.MetaWearBoard;
import com.mbientlab.metawear.RouteManager;
import com.mbientlab.metawear.UnsupportedModuleException;
import com.mbientlab.metawear.module.Accelerometer;
import com.mbientlab.metawear.module.Debug;
import com.mbientlab.metawear.module.Logging;
import com.mbientlab.metawear.processor.Average;
import com.mbientlab.metawear.processor.Comparison;
import com.mbientlab.metawear.processor.Rss;
import com.mbientlab.metawear.processor.Threshold;

public class MainActivity extends Activity implements ServiceConnection {

    private static final String LOG_TAG = "FreeFallDetection";
    private static final String ACCEL_DATA = "accel_data";
    private static final String FREE_FALL_KEY = "free_fall_key";
    private static final String NO_FREE_FALL_KEY = "no_free_fall_key";
    private enum DataColectMethod
    {
        Stream(1),
        Logging(2);

        private int value;

        private DataColectMethod(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    private MetaWearBleService.LocalBinder serviceBinder;
    private MetaWearBoard mwBoard;
    private Accelerometer accelModule;
    private Debug debugModule;
    private Logging loggingModule;
    private DataColectMethod collectMethod = DataColectMethod.Logging;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getApplicationContext().bindService(new Intent(this, MetaWearBleService.class), this, Context.BIND_AUTO_CREATE);

        findViewById(R.id.start_accel_stream).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.i(LOG_TAG, "Start test");

                if (collectMethod == DataColectMethod.Logging)
                    loggingModule.startLogging(true);

                accelModule.enableAxisSampling();
                accelModule.start();
            }
        });

        findViewById(R.id.stop_accel_stream).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.i(LOG_TAG, "Stop test");

                accelModule.stop();
                accelModule.disableAxisSampling();

                // Log mode
                if (collectMethod == DataColectMethod.Logging) {
                    loggingModule.stopLogging();

                    loggingModule.downloadLog(0.f, new Logging.DownloadHandler() {
                        @Override
                        public void onProgressUpdate(int nEntriesLeft, int totalEntries) {
                            Log.i(LOG_TAG, "Downloading log. nEntriesLeft = " + nEntriesLeft + "  Total = " + totalEntries);
                            if (nEntriesLeft == 0)
                                Log.i(LOG_TAG, "Log download complete");
                        }
                    });
                }
            }
        });

        findViewById(R.id.button_reset).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                debugModule.resetDevice();
            }
        });
    }

    @Override
    public void onDestroy()
    {
        super.onDestroy();

        getApplicationContext().unbindService(this);
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        serviceBinder = (MetaWearBleService.LocalBinder) service;

        String mwMacAddress = "F0:62:91:C0:19:2F";

        BluetoothManager btManager = (BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        BluetoothDevice btDevice = btManager.getAdapter().getRemoteDevice(mwMacAddress);

        mwBoard = serviceBinder.getMetaWearBoard(btDevice);

        switch (collectMethod) {
            case Stream:
                Test_Freefall_Stream();
                break;
            case Logging:
                Test_Freefall_Logging();
                break;
            default:
                break;
        }

        mwBoard.connect();
    }

    private void Test_Freefall_Stream() {
        mwBoard.setConnectionStateHandler(new MetaWearBoard.ConnectionStateHandler() {
            @Override
            public void connected() {
                Log.i(LOG_TAG, "Conntected");

                try {
                    accelModule = mwBoard.getModule(Accelerometer.class);       //provide x, y, z data
                    accelModule.setOutputDataRate(50f); // Set operting freq to 50Hz
                    accelModule.routeData().fromAxes()
                            .process(new Rss())                                     // (x,y,z) magnitude
                            .process(new Average((byte) 4))                          // magnitude avg
                            .process(new Threshold(0.5f, Threshold.OutputMode.BINARY))      // 1 or -1
                            .split()
                            .branch().process(new Comparison(Comparison.Operation.EQ, -1)).stream(FREE_FALL_KEY)
                            .branch().process(new Comparison(Comparison.Operation.EQ, 1)).stream(NO_FREE_FALL_KEY)
                            .end()
                            .commit()
                            .onComplete(new AsyncOperation.CompletionHandler<RouteManager>() {
                                @Override
                                public void success(RouteManager result) {
                                    result.subscribe(FREE_FALL_KEY, new RouteManager.MessageHandler() {
                                        @Override
                                        public void process(Message message) {
                                            Log.i(LOG_TAG, "Entered free fall");
                                        }
                                    });
                                    result.subscribe(NO_FREE_FALL_KEY, new RouteManager.MessageHandler() {
                                        @Override
                                        public void process(Message message) {
                                            Log.i(LOG_TAG, "Stopped free fall");
                                        }
                                    });
                                }
                            });

                    debugModule = mwBoard.getModule(Debug.class);
                } catch (UnsupportedModuleException e) {
                    Log.e(LOG_TAG, "Cannot find module", e);
                }
            }

            @Override
            public void disconnected() {
                Log.i(LOG_TAG, "Disconntected");
            }
        });
    }

    private void Test_Freefall_Logging() {
        mwBoard.setConnectionStateHandler(new MetaWearBoard.ConnectionStateHandler() {
            @Override
            public void connected() {
                Log.i(LOG_TAG, "Conntected");

                try {
                    accelModule = mwBoard.getModule(Accelerometer.class);       //provide x, y, z data
                    accelModule.setOutputDataRate(50f);                           // Set operting freq to 50Hz
                    accelModule.routeData().fromAxes()
                            .process(new Rss())                                    // (x,y,z) magnitude
                            .process(new Average((byte) 4))                          // magnitude avg
                            .process(new Threshold(0.5f, Threshold.OutputMode.BINARY))      // 1 or -1
                            .split()
                            .branch().process(new Comparison(Comparison.Operation.EQ, -1)).log(FREE_FALL_KEY)
                            .branch().process(new Comparison(Comparison.Operation.EQ, 1)).log(NO_FREE_FALL_KEY)
                            .end()
                            .commit()
                            .onComplete(new AsyncOperation.CompletionHandler<RouteManager>() {
                                @Override
                                public void success(RouteManager result) {
                                    result.setLogMessageHandler(FREE_FALL_KEY, new RouteManager.MessageHandler() {
                                        @Override
                                        public void process(Message message) {
                                            Log.i(LOG_TAG, message.toString() + ": Entered free fall");
                                        }
                                    });
                                    result.setLogMessageHandler(NO_FREE_FALL_KEY, new RouteManager.MessageHandler() {
                                        @Override
                                        public void process(Message message) {
                                            Log.i(LOG_TAG, message.toString() + ": Stopped free fall");
                                        }
                                    });
                                }
                            });

                    debugModule = mwBoard.getModule(Debug.class);
                    loggingModule = mwBoard.getModule(Logging.class);
                } catch (UnsupportedModuleException e) {
                    Log.e(LOG_TAG, "Cannot find module", e);
                }
            }

            @Override
            public void disconnected() {
                Log.i(LOG_TAG, "Disconntected");
            }
        });
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {

    }
}
