package com.depromeet.media;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.session.MediaSessionManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.annotation.RequiresApi;
import android.support.v4.app.NotificationCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.app.NotificationCompat.MediaStyle;
import android.support.v4.media.session.MediaButtonReceiver;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.depromeet.media.domain.Music;

import java.io.IOException;

public class PlayerService extends Service implements MediaPlayer.OnCompletionListener,
    MediaPlayer.OnPreparedListener, MediaPlayer.OnErrorListener, MediaPlayer.OnSeekCompleteListener,
    MediaPlayer.OnInfoListener, MediaPlayer.OnBufferingUpdateListener, AudioManager.OnAudioFocusChangeListener {

  public static final String ACTION_PLAY_AUDIO = "PlayNewAudio";

  public static final String ACTION_PLAY = "ACTION_PLAY";
  public static final String ACTION_PAUSE = "ACTION_PAUSE";
  public static final String ACTION_PREVIOUS = "ACTION_PREVIOUS";
  public static final String ACTION_NEXT = "ACTION_NEXT";
  public static final String ACTION_STOP = "ACTION_STOP";
  private static final String CHANNEL_ID = "ONSONG.PLAYER";

  private MediaPlayer mediaPlayer;

  //MediaSession
  private MediaSessionManager mediaSessionManager;
  private MediaSessionCompat mediaSession;
  private MediaControllerCompat.TransportControls transportControls;

  //AudioPlayer notification ID
  private static final int NOTIFICATION_ID = 101;

  //Used to pause/resume MediaPlayer
  private int resumePosition;

  //AudioFocus
  private AudioManager audioManager;

  // Binder given to clients
  private final IBinder iBinder = new LocalBinder();

  //List of available Audio files
  private int audioIndex = -1;
  private Music activeAudio; //an object on the currently playing audio


  //Handle incoming phone calls
  private boolean ongoingCall = false;
  private PhoneStateListener phoneStateListener;
  private TelephonyManager telephonyManager;


  /**
   * Service lifecycle methods
   */
  @Override
  public IBinder onBind(Intent intent) {
    return iBinder;
  }

  @Override
  public void onCreate() {
    super.onCreate();

    // FIXME
    activeAudio = new Music("Whatever", "Ugly Duck", "HipHop",
        "https://avatars2.githubusercontent.com/u/11613775?v=4",
        "http://depromeet-4th-final.s3.amazonaws.com/music/test.mp3", 60);

    // Perform one-time setup procedures

    // Manage incoming phone calls during playback.
    // Pause MediaPlayer on incoming call,
    // Resume on hangup.
    callStateListener();
    //ACTION_AUDIO_BECOMING_NOISY -- change in audio outputs -- BroadcastReceiver
    registerBecomingNoisyReceiver();
    //Listen for new Audio to play -- BroadcastReceiver
    registPlayNewAudio();
  }

  //The system calls this method when an activity, requests the service be started
  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (requestAudioFocus() == false) {
      stopSelf();
    }

    if (mediaSessionManager == null) {
      try {
        initMediaSession();
        initMediaPlayer();
      } catch (RemoteException e) {
        e.printStackTrace();
        stopSelf();
      }
      buildNotification();
    }

    //Handle Intent action from MediaSession.TransportControls
    handleIncomingActions(intent);
    return super.onStartCommand(intent, flags, startId);
  }

  @Override
  public boolean onUnbind(Intent intent) {
    mediaSession.release();
    removeNotification();
    return super.onUnbind(intent);
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    if (mediaPlayer != null) {
      stopMedia();
      mediaPlayer.release();
    }
    removeAudioFocus();
    //Disable the PhoneStateListener
    if (phoneStateListener != null) {
      telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
    }

    removeNotification();

    //unregister BroadcastReceivers
    unregisterReceiver(becomingNoisyReceiver);
    unregisterReceiver(playNewAudio);
  }

  /**
   * Service Binder
   */
  public class LocalBinder extends Binder {
    public PlayerService getService() {
      // Return this instance of LocalService so clients can call public methods
      return PlayerService.this;
    }
  }


  /**
   * MediaPlayer callback methods
   */
  @Override
  public void onBufferingUpdate(MediaPlayer mp, int percent) {
    //Invoked indicating buffering status of
    //a media resource being streamed over the network.
  }

  @Override
  public void onCompletion(MediaPlayer mp) {
    //Invoked when playback of a media source has completed.
    stopMedia();

    removeNotification();
    //stop the service
    stopSelf();
  }

  @Override
  public boolean onError(MediaPlayer mp, int what, int extra) {
    //Invoked when there has been an error during an asynchronous operation
    switch (what) {
      case MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK:
        Log.d("MediaPlayer Error", "MEDIA ERROR NOT VALID FOR PROGRESSIVE PLAYBACK " + extra);
        break;
      case MediaPlayer.MEDIA_ERROR_SERVER_DIED:
        Log.d("MediaPlayer Error", "MEDIA ERROR SERVER DIED " + extra);
        break;
      case MediaPlayer.MEDIA_ERROR_UNKNOWN:
        Log.d("MediaPlayer Error", "MEDIA ERROR UNKNOWN " + extra);
        break;
    }
    return false;
  }

  @Override
  public boolean onInfo(MediaPlayer mp, int what, int extra) {
    //Invoked to communicate some info
    return false;
  }

  @Override
  public void onPrepared(MediaPlayer mp) {
    //Invoked when the media source is ready for playback.
    playMedia();
  }

  @Override
  public void onSeekComplete(MediaPlayer mp) {
    //Invoked indicating the completion of a seek operation.
  }

  @Override
  public void onAudioFocusChange(int focusState) {

    //Invoked when the audio focus of the system is updated.
    switch (focusState) {
      case AudioManager.AUDIOFOCUS_GAIN:
        // resume playback
        if (mediaPlayer == null) initMediaPlayer();
        else if (!mediaPlayer.isPlaying()) mediaPlayer.start();
        mediaPlayer.setVolume(1.0f, 1.0f);
        break;
      case AudioManager.AUDIOFOCUS_LOSS:
        // Lost focus for an unbounded amount of time: stop playback and release media player
        if (mediaPlayer.isPlaying()) mediaPlayer.stop();
        mediaPlayer.release();
        mediaPlayer = null;
        break;
      case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
        // Lost focus for a short time, but we have to stop
        // playback. We don't release the media player because playback
        // is likely to resume
        if (mediaPlayer.isPlaying()) mediaPlayer.pause();
        break;
      case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
        // Lost focus for a short time, but it's ok to keep playing
        // at an attenuated level
        if (mediaPlayer.isPlaying()) mediaPlayer.setVolume(0.1f, 0.1f);
        break;
    }
  }


  /**
   * AudioFocus
   */
  private boolean requestAudioFocus() {
    audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    int result = audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
    if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
      //Focus gained
      return true;
    }
    //Could not gain focus
    return false;
  }

  private boolean removeAudioFocus() {
    return AudioManager.AUDIOFOCUS_REQUEST_GRANTED == audioManager.abandonAudioFocus(this);
  }


  /**
   * MediaPlayer actions
   */
  private void initMediaPlayer() {
    if (mediaPlayer == null)
      mediaPlayer = new MediaPlayer();//new MediaPlayer instance

    //Set up MediaPlayer event listeners
    mediaPlayer.setOnCompletionListener(this);
    mediaPlayer.setOnErrorListener(this);
    mediaPlayer.setOnPreparedListener(this);
    mediaPlayer.setOnBufferingUpdateListener(this);
    mediaPlayer.setOnSeekCompleteListener(this);
    mediaPlayer.setOnInfoListener(this);
    //Reset so that the MediaPlayer is not pointing to another data source
    mediaPlayer.reset();


    mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
    try {
      // Set the data source to the mediaFile location
      mediaPlayer.setDataSource(activeAudio.getMusicUrl());
    } catch (IOException e) {
      e.printStackTrace();
      stopSelf();
    }
    mediaPlayer.prepareAsync();
  }

  private void playMedia() {
    if (!mediaPlayer.isPlaying()) {
      mediaPlayer.start();
    }
  }

  private void stopMedia() {
    if (mediaPlayer == null) return;
    if (mediaPlayer.isPlaying()) {
      mediaPlayer.stop();
    }
  }

  private void pauseMedia() {
    if (mediaPlayer.isPlaying()) {
      mediaPlayer.pause();
      resumePosition = mediaPlayer.getCurrentPosition();
    }
  }

  private void resumeMedia() {
    if (!mediaPlayer.isPlaying()) {
      mediaPlayer.seekTo(resumePosition);
      mediaPlayer.start();
    }
  }

  private void skipToNext() {
    // TODO read-update chosen music
    stopMedia();
    //reset mediaPlayer
    mediaPlayer.reset();
    initMediaPlayer();
  }

  private void skipToPrevious() {
    if (audioIndex == 0) {
      //if first in playlist
      //set index to the last of audioList
//      audioIndex = audioList.size() - 1;
//      activeAudio = audioList.get(audioIndex);
    } else {
      //get previous in playlist
//      activeAudio = audioList.get(--audioIndex);
    }

    stopMedia();
    //reset mediaPlayer
    mediaPlayer.reset();
    initMediaPlayer();
  }


  /**
   * ACTION_AUDIO_BECOMING_NOISY -- change in audio outputs
   */
  private BroadcastReceiver becomingNoisyReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      //pause audio on ACTION_AUDIO_BECOMING_NOISY
      pauseMedia();
      buildNotification();
    }
  };

  private void registerBecomingNoisyReceiver() {
    //register after getting audio focus
    IntentFilter intentFilter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
    registerReceiver(becomingNoisyReceiver, intentFilter);
  }

  /**
   * Handle PhoneState changes
   */
  private void callStateListener() {
    // Get the telephony manager
    telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
    //Starting listening for PhoneState changes
    phoneStateListener = new PhoneStateListener() {
      @Override
      public void onCallStateChanged(int state, String incomingNumber) {
        switch (state) {
          //if at least one call exists or the phone is ringing
          //pause the MediaPlayer
          case TelephonyManager.CALL_STATE_OFFHOOK:
          case TelephonyManager.CALL_STATE_RINGING:
            if (mediaPlayer != null) {
              pauseMedia();
              ongoingCall = true;
            }
            break;
          case TelephonyManager.CALL_STATE_IDLE:
            // Phone idle. Start playing.
            if (mediaPlayer != null) {
              if (ongoingCall) {
                ongoingCall = false;
                resumeMedia();
              }
            }
            break;
        }
      }
    };
    // Register the listener with the telephony manager
    // Listen for changes to the device call state.
    telephonyManager.listen(phoneStateListener,
        PhoneStateListener.LISTEN_CALL_STATE);
  }

  /**
   * MediaSession and Notification actions
   */
  private void initMediaSession() throws RemoteException {
    if (mediaSessionManager != null) return; //mediaSessionManager exists

    mediaSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
    // Create a new MediaSession
    mediaSession = new MediaSessionCompat(getApplicationContext(), "AudioPlayer");
    //Get MediaSessions transport controls
    transportControls = mediaSession.getController().getTransportControls();
    //set MediaSession -> ready to receive media commands
    mediaSession.setActive(true);
    //indicate that the MediaSession handles transport control commands
    // through its MediaSessionCompat.Callback.
    mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

    //Set mediaSession's MetaData
    updateMetaData();

    // Attach Callback to receive MediaSession updates
    mediaSession.setCallback(new MediaSessionCompat.Callback() {
      // Implement callbacks
      @Override
      public void onPlay() {
        super.onPlay();

        resumeMedia();
//        buildNotification(PlaybackStatus.PLAYING);
      }

      @Override
      public void onPause() {
        super.onPause();

        pauseMedia();
//        buildNotification(PlaybackStatus.PAUSED);
      }

      @Override
      public void onSkipToNext() {
        super.onSkipToNext();

        skipToNext();
        updateMetaData();
//        buildNotification(PlaybackStatus.PLAYING);
      }

      @Override
      public void onSkipToPrevious() {
        super.onSkipToPrevious();

        skipToPrevious();
        updateMetaData();
//        buildNotification(PlaybackStatus.PLAYING);
      }

      @Override
      public void onStop() {
        super.onStop();
        removeNotification();
        //Stop the service
        stopSelf();
      }

      @Override
      public void onSeekTo(long position) {
        super.onSeekTo(position);
      }
    });
  }

  private void updateMetaData() {
    Bitmap albumArt = BitmapFactory.decodeResource(getResources(),
        R.drawable.img_album_01); //replace with medias albumArt
    // Update the current metadata
    mediaSession.setMetadata(new MediaMetadataCompat.Builder()
        .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt)
        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, activeAudio.getArtist())
        .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, activeAudio.getCoverUrl())
        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, activeAudio.getTitle())
        .build());
  }

  private void buildNotification() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      createChannel();
    }
    Bitmap largeIcon = BitmapFactory.decodeResource(getResources(),
        R.drawable.img_album_01); //replace with your own image

    NotificationCompat.Builder notificationBuilder =
        new NotificationCompat.Builder(this, CHANNEL_ID);
    notificationBuilder
        .setStyle(
            new MediaStyle()
                .setMediaSession(mediaSession.getSessionToken())
                .setShowCancelButton(true)
                .setCancelButtonIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP)))
        .setColor(getResources().getColor(android.R.color.black))
        .setLargeIcon(largeIcon)
        .setSmallIcon(android.R.drawable.stat_sys_headset)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setShowWhen(false)
        .setOnlyAlertOnce(true)
        .setContentText(activeAudio.getArtist())
        .setContentTitle(activeAudio.getCoverUrl())
        .setContentInfo(activeAudio.getTitle())
        .setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP));

    ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notificationBuilder.build());
  }

  @RequiresApi(Build.VERSION_CODES.O)
  private void createChannel() {
    NotificationManager
        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    // The id of the channel.
    String id = CHANNEL_ID;
    // The user-visible name of the channel.
    CharSequence name = "Media playback";
    // The user-visible description of the channel.
    String description = "Media playback controls";
    int importance = NotificationManager.IMPORTANCE_HIGH;
    NotificationChannel mChannel = new NotificationChannel(id, name, importance);
    // Configure the notification channel.
    mChannel.setDescription(description);
    mChannel.setShowBadge(false);
    mChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
    mNotificationManager.createNotificationChannel(mChannel);
  }

  private PendingIntent playbackAction(int actionNumber) {
    Intent playbackAction = new Intent(this, PlayerService.class);
    switch (actionNumber) {
      case 0:
        // Play
        playbackAction.setAction(ACTION_PLAY);
        return PendingIntent.getService(this, actionNumber, playbackAction, 0);
      case 1:
        // Pause
        playbackAction.setAction(ACTION_PAUSE);
        return PendingIntent.getService(this, actionNumber, playbackAction, 0);
      case 2:
        // Next track
        playbackAction.setAction(ACTION_NEXT);
        return PendingIntent.getService(this, actionNumber, playbackAction, 0);
      case 3:
        // Previous track
        playbackAction.setAction(ACTION_PREVIOUS);
        return PendingIntent.getService(this, actionNumber, playbackAction, 0);
      default:
        break;
    }
    return null;
  }

  private void removeNotification() {
    NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    notificationManager.cancel(NOTIFICATION_ID);
  }

  private void handleIncomingActions(Intent playbackAction) {
    if (playbackAction == null || playbackAction.getAction() == null) return;

    String actionString = playbackAction.getAction();
    if (actionString.equalsIgnoreCase(ACTION_PLAY)) {
      transportControls.play();
    } else if (actionString.equalsIgnoreCase(ACTION_PAUSE)) {
      transportControls.pause();
    } else if (actionString.equalsIgnoreCase(ACTION_NEXT)) {
      transportControls.skipToNext();
    } else if (actionString.equalsIgnoreCase(ACTION_PREVIOUS)) {
      transportControls.skipToPrevious();
    } else if (actionString.equalsIgnoreCase(ACTION_STOP)) {
      transportControls.stop();
    }
  }


  /**
   * Play new Audio
   */
  private BroadcastReceiver playNewAudio = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      stopMedia();
      mediaPlayer.reset();
      initMediaPlayer();
      updateMetaData();
    }
  };

  private void registPlayNewAudio() {
    //Register playNewMedia receiver
    IntentFilter filter = new IntentFilter(ACTION_PLAY_AUDIO);
    registerReceiver(playNewAudio, filter);
  }

}
