package com.rebenew.musicParty.syncserver.model;

/**
 * Estado de reproducción ligero para sincronización.
 * Solo contiene información necesaria para sync, no duplica estado.
 */
public class PlaybackState {
    private String currentTrackId;
    private String currentTrackTitle;
    private long positionMs;
    private boolean isPlaying;
    private long timestamp;
    private Long durationMs; // nullable

    // Constructor simplificado
    public PlaybackState(String currentTrackId, String currentTrackTitle,
                         long positionMs, boolean isPlaying, Long durationMs) {
        this.currentTrackId = currentTrackId;
        this.currentTrackTitle = currentTrackTitle;
        this.positionMs = positionMs;
        this.isPlaying = isPlaying;
        this.durationMs = durationMs;
        this.timestamp = System.currentTimeMillis();
    }

    // Metodo de fabricación desde RoomSession
    public static PlaybackState fromRoomSession(RoomSession session) {
        TrackEntry currentTrack = session.getNowPlayingTrack();
        return new PlaybackState(
                currentTrack != null ? currentTrack.trackId() : null,
                currentTrack != null ? currentTrack.title() : null,
                session.getCurrentPlaybackPosition(),
                session.getState() == RoomState.ACTIVE,
                null // duration podría venir de YouTube API
        );
    }

    // Getters (sin setters para inmutabilidad)
    public String getCurrentTrackId() { return currentTrackId; }
    public String getCurrentTrackTitle() { return currentTrackTitle; }
    public long getPositionMs() { return positionMs; }
    public boolean isPlaying() { return isPlaying; }
    public long getTimestamp() { return timestamp; }
    public Long getDurationMs() { return durationMs; }
}