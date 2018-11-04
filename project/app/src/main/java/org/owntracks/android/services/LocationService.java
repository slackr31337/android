package org.owntracks.android.services;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.location.Location;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.StyleSpan;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingClient;
import com.google.android.gms.location.GeofencingEvent;
import com.google.android.gms.location.GeofencingRequest;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.owntracks.android.R;
import org.owntracks.android.data.WaypointModel;
import org.owntracks.android.data.repos.ContactsRepo;
import org.owntracks.android.data.repos.LocationRepo;
import org.owntracks.android.data.repos.WaypointsRepo;
import org.owntracks.android.messages.MessageLocation;
import org.owntracks.android.messages.MessageTransition;
import org.owntracks.android.model.FusedContact;
import org.owntracks.android.services.worker.Scheduler;
import org.owntracks.android.support.DateFormatter;
import org.owntracks.android.support.Events;
import org.owntracks.android.support.GeocodingProvider;
import org.owntracks.android.support.Preferences;
import org.owntracks.android.support.Runner;
import org.owntracks.android.ui.map.MapActivity;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import dagger.android.DaggerService;
import timber.log.Timber;

import static android.provider.ContactsContract.Directory.PACKAGE_NAME;

public class LocationService extends DaggerService implements OnCompleteListener<Location> {
    private static final int INTENT_REQUEST_CODE_LOCATION = 1263;
    private static final int INTENT_REQUEST_CODE_GEOFENCE = 1264;
    private static final int INTENT_REQUEST_CODE_CLEAR_EVENTS = 1263;

    private static final int NOTIFICATION_ID_ONGOING = 1;
    private static final String NOTIFICATION_CHANNEL_ONGOING = "O";

    private static final int NOTIFICATION_ID_EVENT_GROUP = 2;
    private static final String NOTIFICATION_CHANNEL_EVENTS = "E";

    private static int notificationEventsID = 3;

    private final String NOTIFICATION_GROUP_EVENTS = "events";

    // NEW ACTIONS ALSO HAVE TO BE ADDED TO THE SERVICE INTENT FILTER
    public static final String INTENT_ACTION_CLEAR_NOTIFICATIONS = "C";
    public static final String INTENT_ACTION_SEND_LOCATION_PING = "LP";
    public static final String INTENT_ACTION_SEND_LOCATION_USER = "LU";
    public static final String INTENT_ACTION_SEND_WAYPOINTS = "W";
    public static final String INTENT_ACTION_SEND_EVENT_CIRCULAR = "EC";
    public static final String INTENT_ACTION_REREQUEST_LOCATION_UPDATES = "RRLU";
    public static final String INTENT_ACTION_CHANGE_MONITORING = "CM";

    public static final String ACTION_BROADCAST = PACKAGE_NAME + ".broadcast";
    public static final String EXTRA_LOCATION = PACKAGE_NAME + ".location";


    private FusedLocationProviderClient mFusedLocationClient;
    private GeofencingClient mGeofencingClient;

    private LocationCallback locationCallback;
    private MessageLocation lastLocationMessage;
    private MessageProcessor.EndpointState lastEndpointState = MessageProcessor.EndpointState.INITIAL;


    private NotificationCompat.Builder activeNotificationBuilder;
    private NotificationCompat.Builder notificationBuilderEvents;
    private NotificationManager notificationManager;

    private NotificationManagerCompat notificationManagerCompat;

    private final LinkedList<Spannable> activeNotifications = new LinkedList<>();
    private int lastQueueLength = 0;
    private Notification stackNotification;

    @Inject
    Preferences preferences;

    @Inject
    protected EventBus eventBus;

    @Inject
    protected Scheduler scheduler;

    @Inject
    protected LocationProcessor locationProcessor;

    @Inject
    protected GeocodingProvider geocodingProvider;

    @Inject
    protected ContactsRepo contactsRepo;

    @Inject
    LocationRepo locationRepo;

    @Inject
    protected Runner runner;

    @Inject
    protected WaypointsRepo waypointsRepo;

    private Handler serviceHandler;


    @Override
    public void onCreate() {
        super.onCreate();
        Timber.v("Preferences instance: %s", preferences);

        //preferences = App.getPreferences();
        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        mGeofencingClient = LocationServices.getGeofencingClient(this);
        notificationManagerCompat = NotificationManagerCompat.from(this); //getSystemService(Context.NOTIFICATION_SERVICE);
        HandlerThread handlerThread = new HandlerThread(LocationService.class.getSimpleName());
        handlerThread.start();
        serviceHandler = new Handler(handlerThread.getLooper());
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                super.onLocationResult(locationResult);
                onLocationChanged(locationResult.getLastLocation());
            }
        };

        setupNotificationChannels();
        sendOngoingNotification();

        setupLocationRequest();
        setupLocationPing();

        setupGeofences();

        eventBus.register(this);
        eventBus.postSticky(new Events.ServiceStarted());
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Timber.i("LocationService started");
        super.onStartCommand(intent, flags, startId);

        if (intent != null) {
            handleIntent(intent);
        }

        return START_STICKY;
    }

    private void handleIntent(@NonNull Intent intent) {
        if (intent.getAction() != null) {
            Timber.v("intent received with action:%s", intent.getAction());

            switch (intent.getAction()) {
                case INTENT_ACTION_SEND_LOCATION_PING:
                    locationProcessor.publishLocationMessage(MessageLocation.REPORT_TYPE_PING);
                    return;
                case INTENT_ACTION_SEND_LOCATION_USER:
                    locationProcessor.publishLocationMessage(MessageLocation.REPORT_TYPE_USER);
                    return;
                case INTENT_ACTION_SEND_EVENT_CIRCULAR:
                    onGeofencingEvent(GeofencingEvent.fromIntent(intent));
                    return;
                case INTENT_ACTION_SEND_WAYPOINTS:
                    locationProcessor.publishWaypointsMessage();
                    return;
                case INTENT_ACTION_CLEAR_NOTIFICATIONS:
                    clearEventStackNotification();
                    return;
                case INTENT_ACTION_REREQUEST_LOCATION_UPDATES:
                    setupLocationRequest();
                    return;
                case INTENT_ACTION_CHANGE_MONITORING:
                    preferences.setMonitoringNext();
                    return;
                default:
                    Timber.v("unhandled intent action received: %s", intent.getAction());
            }
        }
    }

    public void setupNotificationChannels() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }

        // Android O requires a Notification Channel.
        CharSequence name = getString(R.string.app_name);

        NotificationChannelGroup notificationChannelGroup = new NotificationChannelGroup("TEST", name);
        notificationManager.createNotificationChannelGroup(notificationChannelGroup);

        // Create the channel for the notification
        NotificationChannel ongoingNotificationChannel =
                new NotificationChannel(NOTIFICATION_CHANNEL_ONGOING, name, NotificationManager.IMPORTANCE_DEFAULT);
        ongoingNotificationChannel.setGroup(notificationChannelGroup.getId());

        // Set the Notification Channel for the Notification Manager.
        notificationManager.createNotificationChannel(ongoingNotificationChannel);

        NotificationChannel eventsNotificationChannel =
                new NotificationChannel(NOTIFICATION_CHANNEL_ONGOING, name, NotificationManager.IMPORTANCE_DEFAULT);

        eventsNotificationChannel.setGroup(notificationChannelGroup.getId());


        notificationManager.createNotificationChannel(eventsNotificationChannel);
    }

    private void sendOngoingNotification() {
        notificationManager.notify(NOTIFICATION_ID_ONGOING, getOngoingNotification());
    }


    public String getMonitoringLabel(int mode) {
        switch (mode) {
            case LocationProcessor.MONITORING_QUIET:
                return getString(R.string.monitoring_quiet);
            case LocationProcessor.MONITORING_MANUAL:
                return getString(R.string.monitoring_manual);
            case LocationProcessor.MONITORING_SIGNIFFICANT:
                return getString(R.string.monitoring_signifficant);
            case LocationProcessor.MONITORING_MOVE:
                return getString(R.string.monitoring_move);
        }
        return getString(R.string.na);
    }

    private void sendEventNotification(MessageTransition message) {
        NotificationCompat.Builder builder = getEventsNotificationBuilder();

        if (builder == null) {
            Timber.e("no builder returned");
            return;
        }

        FusedContact c = contactsRepo.getById(message.getContactKey());

        long when = message.getTst() * 1000;
        String location = message.getDesc();

        if (location == null) {
            location = getString(R.string.aLocation);
        }
        String title = message.getTid();
        if (c != null)
            title = c.getFusedName();
        else if (title == null) {
            title = message.getContactKey();
        }

        String text = String.format("%s %s", getString(message.getTransition() == Geofence.GEOFENCE_TRANSITION_ENTER ? R.string.transitionEntering : R.string.transitionLeaving), location);


        notificationBuilderEvents.setContentTitle(title);
        notificationBuilderEvents.setContentText(text);
        notificationBuilderEvents.setWhen(when);
        notificationBuilderEvents.setShowWhen(true);
        notificationBuilderEvents.setGroup(NOTIFICATION_GROUP_EVENTS);
        // Deliver notification
        Notification n = notificationBuilderEvents.build();

        Timber.v("sending new transition notification");
        notificationManagerCompat.notify(notificationEventsID++, n);
        //notificationManagerCompat.notify(NOTIFICATION_TAG_EVENTS_STACK, System.currentTimeMillis() / 1000, n) ;
        sendEventStackNotification(title, text, when);
    }


    private void sendEventStackNotification(String title, String text, long when) {
        if (Build.VERSION.SDK_INT >= 23) {
            Timber.v("SDK_INT >= 23, building stack notification");

            String whenStr = DateFormatter.formatDate(TimeUnit.MILLISECONDS.toSeconds((when)));

            Spannable newLine = new SpannableString(String.format("%s %s %s", whenStr, title, text));
            newLine.setSpan(new StyleSpan(Typeface.BOLD), 0, whenStr.length() + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

            activeNotifications.push(newLine);
            Timber.v("groupedNotifications: %s", activeNotifications.size());

            // since we assume the most recent notification was delivered just prior to calling this method,
            // we check that previous notifications in the group include at least 2 notifications
            if (activeNotifications.size() > 1) {

                NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_EVENTS);
                String summary = getString(R.string.notificationEventsTitle, activeNotifications.size());
                builder.setContentTitle(getString(R.string.events));
                builder.setContentText(summary);
                builder.setGroup(NOTIFICATION_GROUP_EVENTS); // same as group of single notifications
                builder.setGroupSummary(true);
                builder.setColor(getColor(R.color.primary));
                builder.setAutoCancel(true);
                builder.setPriority(NotificationCompat.PRIORITY_DEFAULT);
                builder.setSmallIcon(R.drawable.ic_notification);
                builder.setDefaults(Notification.DEFAULT_ALL);
                // for every previously sent notification that met our above requirements,
                // insert a new line containing its title to the inbox style notification extender
                NotificationCompat.InboxStyle inbox = new NotificationCompat.InboxStyle();
                inbox.setSummaryText(summary);


                // Append new notification to existing
                CharSequence cs[] = null;

                if (stackNotification != null) {
                    cs = (CharSequence[]) stackNotification.extras.get(NotificationCompat.EXTRA_TEXT_LINES);
                }

                if (cs == null) {
                    cs = new CharSequence[0];
                }

                for (int i = 0; i < cs.length && i < 19; i++) {
                    inbox.addLine(cs[i]);
                }
                inbox.addLine(newLine);

                builder.setNumber(cs.length + 1);
                builder.setStyle(inbox);
                builder.setContentIntent(PendingIntent.getActivity(this, (int) System.currentTimeMillis() / 1000, new Intent(this, MapActivity.class), PendingIntent.FLAG_ONE_SHOT));
                builder.setDeleteIntent(PendingIntent.getService(this, INTENT_REQUEST_CODE_CLEAR_EVENTS, (new Intent(this, LocationService.class)).setAction(INTENT_ACTION_CLEAR_NOTIFICATIONS), PendingIntent.FLAG_ONE_SHOT));

                stackNotification = builder.build();
                notificationManagerCompat.notify(NOTIFICATION_GROUP_EVENTS, NOTIFICATION_ID_EVENT_GROUP, stackNotification);
            }
        }
    }

    private void clearEventStackNotification() {
        Timber.v("clearing notification stack");
        activeNotifications.clear();

    }

    // TODO: Move to somewere else
    private void setupLocationPing() {
        scheduler.scheduleLocationPing();
    }

    private void onGeofencingEvent(@Nullable final GeofencingEvent event) {

        if (event == null) {
            Timber.e("geofencingEvent null or hasError");
            return;
        }

        if (event.hasError()) {
            Timber.e("geofencingEvent hasError: %s", event.getErrorCode());
            return;
        }

        final int transition = event.getGeofenceTransition();
        for (int index = 0; index < event.getTriggeringGeofences().size(); index++) {
            WaypointModel w = waypointsRepo.get(Long.parseLong(event.getTriggeringGeofences().get(index).getRequestId()));
            if (w == null) {
                Timber.e("waypoint id %s not found for geofence event", event.getTriggeringGeofences().get(index).getRequestId());
                continue;
            }
            locationProcessor.onWaypointTransition(w, event.getTriggeringLocation(), transition, MessageTransition.TRIGGER_CIRCULAR);
        }
    }

    public void onLocationChanged(@Nullable Location location) {
        if (location != null && location.getTime() > locationRepo.getCurrentLocationTime()) {
            Timber.v("location update received: %f lat: %f lon: %f", location.getAccuracy(), location.getLatitude(), location.getLongitude());

            // Put location to the processor for transmission
            locationProcessor.onLocationChanged(location);

            Intent intent = new Intent(ACTION_BROADCAST);
            intent.putExtra(EXTRA_LOCATION, location);
            LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);

            if (serviceIsRunningInForeground(this)) {
                notificationManager.notify(NOTIFICATION_ID_ONGOING, getOngoingNotification());
            }
        }
    }

    @SuppressWarnings("MissingPermission")
    private void setupLocationRequest() {
        if (missingLocationPermission()) {
            Timber.e("missing location permission");
            return;
        }

        if (mFusedLocationClient == null) {
            Timber.e("FusedLocationClient not available");
            return;
        }
        int monitoring = preferences.getMonitoring();
        Timber.v("requesting location updates for monitoring mode %s", monitoring);

        LocationRequest request = preferences.getMonitoring() == LocationProcessor.MONITORING_MOVE ? getHighPowerLocationRequest() : getBalancedPowerLocationRequest();

        mFusedLocationClient.removeLocationUpdates(getLocationPendingIntent());
        mFusedLocationClient.requestLocationUpdates(request, locationCallback, runner.getBackgroundHandler().getLooper());
        mFusedLocationClient.getLastLocation().addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null) {
                onLocationChanged(task.getResult());
            } else {
                Timber.e("Failed to get last location");
            }
        });
    }

    private PendingIntent getLocationPendingIntent() {
        Intent locationIntent = new Intent(getApplicationContext(), LocationService.class);
        return PendingIntent.getBroadcast(getApplicationContext(), INTENT_REQUEST_CODE_LOCATION, locationIntent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent getGeofencePendingIntent() {
        Intent geofeneIntent = new Intent(this, LocationService.class);
        geofeneIntent.setAction(INTENT_ACTION_SEND_EVENT_CIRCULAR);
        return PendingIntent.getBroadcast(this, INTENT_REQUEST_CODE_GEOFENCE, geofeneIntent, PendingIntent.FLAG_UPDATE_CURRENT);
    }


    private LocationRequest getBalancedPowerLocationRequest() {
        Timber.v("Providing balanced power location request");
        return new LocationRequest()
                .setInterval(TimeUnit.SECONDS.toMillis(preferences.getLocatorInterval()))
                .setFastestInterval(TimeUnit.SECONDS.toMillis(10))
                .setMaxWaitTime(2 * TimeUnit.SECONDS.toMillis(preferences.getLocatorInterval()))
                .setSmallestDisplacement(preferences.getLocatorDisplacement())
                .setPriority(LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY);
    }

    private LocationRequest getHighPowerLocationRequest() {
        Timber.v("Providing high power power location request");
        return new LocationRequest()
                .setInterval(TimeUnit.SECONDS.toMillis(10))
                .setFastestInterval(TimeUnit.SECONDS.toMillis(2))
                .setMaxWaitTime(TimeUnit.SECONDS.toMillis(10))
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
    }


    @SuppressWarnings("MissingPermission")
    private void setupGeofences() {
        if (missingLocationPermission()) {
            Timber.e("missing location permission");
            return;
        }

        Timber.v("loader thread:%s, isMain:%s", Looper.myLooper(), Looper.myLooper() == Looper.getMainLooper());

        LinkedList<Geofence> geofences = new LinkedList<>();
        List<WaypointModel> loadedWaypoints = waypointsRepo.getAllWithGeofences();


        for (WaypointModel w : loadedWaypoints) {
            Timber.v("desc:%s", w.getDescription());

            geofences.add(new Geofence.Builder()
                    .setRequestId(Long.toString(w.getId()))
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER | Geofence.GEOFENCE_TRANSITION_EXIT)
                    .setNotificationResponsiveness((int) TimeUnit.MINUTES.toMillis(2))
                    .setCircularRegion(w.getGeofenceLatitude(), w.getGeofenceLongitude(), w.getGeofenceRadius())
                    .setExpirationDuration(Geofence.NEVER_EXPIRE).build());
        }

        if (geofences.size() > 0) {
            GeofencingRequest.Builder b = new GeofencingRequest.Builder();
            b.setInitialTrigger(Geofence.GEOFENCE_TRANSITION_ENTER);
            GeofencingRequest request = b.addGeofences(geofences).build();
            mGeofencingClient.addGeofences(request, getGeofencePendingIntent());
        }
    }

    private boolean missingLocationPermission() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_DENIED;
    }

    private void removeGeofences() {
        mGeofencingClient.removeGeofences(getGeofencePendingIntent());
    }

    @SuppressWarnings("unused")
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onEvent(Events.WaypointAdded e) {
        locationProcessor.publishWaypointMessage(e.getWaypointModel()); // TODO: move to waypointsRepo
        if (e.getWaypointModel().hasGeofence()) {
            removeGeofences();
            setupGeofences();
        }
    }

    @SuppressWarnings("unused")
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onEvent(Events.WaypointUpdated e) {
        locationProcessor.publishWaypointMessage(e.getWaypointModel()); // TODO: move to waypointsRepo
        removeGeofences();
        setupGeofences();
    }

    @SuppressWarnings("unused")
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onEvent(Events.WaypointRemoved e) {
        if (e.getWaypointModel().hasGeofence()) {
            removeGeofences();
            setupGeofences();
        }
    }

    @SuppressWarnings("unused")
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onEvent(Events.ModeChanged e) {
        removeGeofences();
        setupGeofences();
        sendOngoingNotification();
    }

    @SuppressWarnings("unused")
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onEvent(Events.MonitoringChanged e) {
        setupLocationRequest();
        sendOngoingNotification();
    }

    @SuppressWarnings("unused")
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onEvent(MessageTransition message) {
        Timber.v("transition isIncoming:%s topic:%s", message.isIncoming(), message.getTopic());
        if (message.isIncoming())
            sendEventNotification(message);
    }

    @SuppressWarnings("unused")
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onEvent(MessageLocation m) {
        Timber.v("MessageLocation received %s, %s, outgoing: %s ",
                m.getLatLng(), lastLocationMessage != null ? lastLocationMessage.getLatLng() : "null",
                m.isOutgoing());
        if (m.isDelivered() && (lastLocationMessage == null || lastLocationMessage.getTst() <= m.getTst())) {
            this.lastLocationMessage = m;
            sendOngoingNotification();
            geocodingProvider.resolve(m, this);
        }
    }

    public void onGeocodingProviderResult(MessageLocation m) {
        if (m == lastLocationMessage) {
            sendOngoingNotification();
        }
    }

    @SuppressWarnings("unused")
    @Subscribe(sticky = true)
    public void onEvent(MessageProcessor.EndpointState state) {
        Timber.v("endpoint state changed %s", state.getLabel(this));
        this.lastEndpointState = state;
        sendOngoingNotification();
    }

    @SuppressWarnings("unused")
    @Subscribe(sticky = true)
    public void onEvent(Events.QueueChanged e) {
        this.lastQueueLength = e.getNewLength();
        sendOngoingNotification();
    }

    @SuppressWarnings("unused")
    @Subscribe(sticky = true)
    public void onEvent(Events.PermissionGranted event) {
        Timber.v("location permission granted");
        removeGeofences();
        setupGeofences();

        try {
            Timber.v("Getting last location");
            mFusedLocationClient.getLastLocation().addOnCompleteListener(this);
        } catch (SecurityException ignored) {
        }

    }


    public NotificationCompat.Builder getEventsNotificationBuilder() {
        if (!preferences.getNotificationEvents())
            return null;

        Timber.v("building notification builder");

        if (notificationBuilderEvents != null)
            return notificationBuilderEvents;

        Timber.v("builder not present, lazy building");
        notificationBuilderEvents = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_EVENTS);

        Intent openIntent = new Intent(this, MapActivity.class);
        openIntent.setAction("android.intent.action.MAIN");
        openIntent.addCategory("android.intent.category.LAUNCHER");
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        notificationBuilderEvents.setContentIntent(openPendingIntent);
        //notificationBuilderEvents.setDeleteIntent(ServiceProxy.getBroadcastIntentForService(this.context, ServiceProxy.SERVICE_NOTIFICATION, ServiceNotification.INTENT_ACTION_CANCEL_EVENT_NOTIFICATION, null));
        notificationBuilderEvents.setSmallIcon(R.drawable.ic_notification);
        notificationBuilderEvents.setAutoCancel(true);
        notificationBuilderEvents.setShowWhen(true);
        notificationBuilderEvents.setPriority(NotificationCompat.PRIORITY_DEFAULT);
        notificationBuilderEvents.setCategory(NotificationCompat.CATEGORY_SERVICE);
        notificationBuilderEvents.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            notificationBuilderEvents.setColor(getColor(R.color.primary));
        }

        return notificationBuilderEvents;
    }


    @Override
    public void onComplete(@NonNull Task<Location> task) {
        onLocationChanged(task.getResult());
    }

    private final IBinder mBinder = new LocalBinder();


    public class LocalBinder extends Binder {
        public LocationService getService() {
            return LocationService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        // Called when a client comes to the foreground  and binds with this service. The service
        // should cease to be a foreground service when that happens.
        Timber.v("in onBind()");
        stopForeground(true);
        return mBinder;
    }

    @Override
    public void onRebind(Intent intent) {
        // Called when a client returns to the foreground and binds once again with this service.
        // The service should cease to be a foreground service when that happens.
        Timber.v("in onRebind()");
        stopForeground(true);
        super.onRebind(intent);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Timber.v("Last client unbound from service");

        startForeground(NOTIFICATION_ID_ONGOING, getOngoingNotification());


        return true; // Ensures onRebind() is called when a client re-binds.
    }

    @Override
    public void onDestroy() {
        serviceHandler.removeCallbacksAndMessages(null);
    }

    private Notification getOngoingNotification() {
        // TODO: Set the correct options for the ongoing notification. Do we want a notification
        // click to start the MapActivity, or is the button good enough?
        String title;

        if (this.lastLocationMessage != null && preferences.getNotificationLocation()) {
            title = this.lastLocationMessage.getGeocoder();
        } else {
            title = getString(R.string.app_name);
        }

        CharSequence contentText;
        if (lastEndpointState == MessageProcessor.EndpointState.CONNECTED || lastEndpointState == MessageProcessor.EndpointState.IDLE) {
            contentText = getMonitoringLabel(preferences.getMonitoring());
        } else {
            contentText = lastEndpointState.getLabel(this);
        }

        int priority = preferences.getNotificationHigherPriority() ? NotificationCompat.PRIORITY_DEFAULT : NotificationCompat.PRIORITY_MIN;

        Intent resultIntent = new Intent(this, MapActivity.class);
        resultIntent.setAction("android.intent.action.MAIN");
        resultIntent.addCategory("android.intent.category.LAUNCHER");
        resultIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent activityPendingIntent = PendingIntent.getActivity(this, 0,
                resultIntent, 0);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ONGOING)
                .addAction(R.drawable.ic_mylocation, "Show Map", activityPendingIntent)

                .setContentText(contentText)
                .setContentTitle(title)
                .setOngoing(true)
                .setPriority(priority)
                .setSmallIcon(R.drawable.ic_notification)
                .setTicker(contentText)
                .setWhen(System.currentTimeMillis());

        // Set the Channel ID for Android O.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(NOTIFICATION_CHANNEL_ONGOING); // Channel ID
        }

        return builder.build();
    }

    public boolean serviceIsRunningInForeground(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(
                Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(
                Integer.MAX_VALUE)) {
            if (getClass().getName().equals(service.service.getClassName())) {
                if (service.foreground) {
                    return true;
                }
            }
        }
        return false;
    }
}
